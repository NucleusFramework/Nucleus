#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>
#include <jni.h>
#include <math.h>
#include <stdatomic.h>

// Associated object keys
static const char kTitleBarConstraintsKey      = 0;
static const char kTitleBarHeightKey           = 1;
static const char kFullscreenObserverKey       = 2;
static const char kFullscreenButtonsKey        = 3;
static const char kZoomResponderKey            = 5;
static const char kDragViewKey                 = 6;
static const char kNewFullscreenControlsKey    = 7;
static const char kMenuBarOffsetKey            = 8;
static const char kMenuBarMonitorKey           = 9;
static const char kMenuBarLastRawOffsetKey     = 10;
static const char kLargeCornerRadiusKey        = 11;

static const char kRTLKey                     = 13;

static const float kMinHeightForFullSize = 28.0f;
static const float kDefaultButtonOffset  = 23.0f;
// Extra left margin when the invisible toolbar is present (26pt corner radius).
// Matches the button inset used by Apple apps with a toolbar (e.g. Finder, Safari).
static const float kToolbarExtraInset    = 6.0f;
// Maximum horizontal margin for the first traffic-light button.
// Capped at the default title bar height (40pt) / 2 so that increasing
// the title bar height beyond the default doesn't push buttons further right.
static const float kDefaultTitleBarHeight = 40.0f;
static const float kMaxButtonLeftMargin   = kDefaultTitleBarHeight / 2.0f;
// Pre-Tahoe native traffic-lights: 20 pt between button centers, and the
// standard buttons keep their natural 14x16 pt frame (12 pt visible circle).
static const float kLegacyButtonOffset    = 20.0f;

// macOS 26 (Tahoe) introduced larger, wider-spaced traffic-lights, the large
// corner radius and the Safari-style fullscreen title bar. Everything gated
// on this check falls back to the classic pre-Tahoe chrome (issue #310).
static BOOL isTahoeOrLater(void) {
    static BOOL result = NO;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSOperatingSystemVersion v = (NSOperatingSystemVersion){26, 0, 0};
        result = [[NSProcessInfo processInfo] isOperatingSystemAtLeastVersion:v];
    });
    return result;
}

static float defaultButtonOffset(void) {
    return isTahoeOrLater() ? kDefaultButtonOffset : kLegacyButtonOffset;
}

// _adjustWindowToScreen swizzle state
static IMP sOriginalAdjustWindowToScreen = NULL;


// Forward declarations
static void applyConstraints(NSWindow *window, float height);
static void removeExistingConstraints(NSWindow *window);
static void installFullScreenButtons(NSWindow *window, float titleBarHeight);
static void removeFullScreenButtons(NSWindow *window);
static void updateFullScreenButtonsPosition(NSWindow *window);
static void ensureAdjustWindowSwizzle(NSWindow *window);
static void installZoomButtonResponder(NSWindow *window);
static void removeZoomButtonResponder(NSWindow *window);
static void ensureDragView(NSWindow *window);
static void removeDragView(NSWindow *window);
static void installMenuBarMonitor(NSWindow *window);
static void removeMenuBarMonitor(NSWindow *window);
static void neutralizeToolbarFullScreenWindows(void);

// ─── JVM caching for native → Java callbacks ────────────────────────────────────

static JavaVM *sJVM = NULL;
static jclass sBridgeClass = NULL;       // global ref
static jmethodID sOnOffsetChanged = NULL;
// Prevents JNI callbacks after JVM shutdown begins.
// Set to true in ensureJVMCached, cleared by nativeShutdown.
static atomic_bool sCallbacksEnabled = ATOMIC_VAR_INIT(false);
// Set to true in nativeShutdown — prevents all pending dispatch_async blocks
// from touching windows/AppKit during JVM teardown.
static atomic_bool sShutdownInProgress = ATOMIC_VAR_INIT(false);

static void ensureJVMCached(JNIEnv *env) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        (*env)->GetJavaVM(env, &sJVM);
        jclass local = (*env)->FindClass(env,
            "dev/nucleusframework/window/utils/macos/JniMacTitleBarBridge");
        if (local) {
            sBridgeClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            sOnOffsetChanged = (*env)->GetStaticMethodID(
                env, sBridgeClass, "onMenuBarOffsetChanged", "(JF)V");
            atomic_store(&sCallbacksEnabled, true);
        }
    });
}

// Calls JniMacTitleBarBridge.onMenuBarOffsetChanged(nsWindowPtr, offset).
// MUST be called only from the macOS main thread (AppKit run loop).
// Attaches the main thread to the JVM as a daemon on first call;
// subsequent calls reuse the attached env. The main thread is never
// detached — it lives for the entire lifetime of the application.
// Guarded by sCallbacksEnabled to prevent crashes during JVM shutdown.
static void notifyMenuBarOffsetChanged(NSWindow *window, float offset) {
    if (!atomic_load(&sCallbacksEnabled)) return;
    if (!sJVM || !sBridgeClass || !sOnOffsetChanged) return;

    JNIEnv *env = NULL;
    jint status = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) != JNI_OK) {
            // JVM is shutting down — disable further callbacks
            atomic_store(&sCallbacksEnabled, false);
            return;
        }
    } else if (status != JNI_OK) {
        return;
    }
    if (!env) return;

    // Double-check after potentially blocking on attach
    if (!atomic_load(&sCallbacksEnabled)) return;

    (*env)->CallStaticVoidMethod(env, sBridgeClass, sOnOffsetChanged,
                                 (jlong)(uintptr_t)window, (jfloat)offset);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

// ─── Fullscreen buttons container ───────────────────────────────────────────────

// Custom NSView that hosts replacement traffic-light buttons in the content view
// during fullscreen, mirroring JBR's AWTButtonsView.
// Copies of _NSThemeWidget draw inactive-gray when they are not the window's
// real title-bar buttons, so at rest we paint the standard active colours
// ourselves and reveal native close/zoom on hover (issue #531). Miniaturise
// is disabled: performMiniaturize: is a no-op in fullscreen.
@interface NucleusButtonsView : NSView {
    BOOL _dispatching;
    BOOL _mouseInside;
}
- (void)applyHoverState;
@end

// Standard Big Sur+ traffic-light sRGB fills (see NucleusTaoButtonsView).
static NSColor *jniTrafficCloseColor(void) {
    return [NSColor colorWithSRGBRed:1.0 green:95.0 / 255.0 blue:87.0 / 255.0 alpha:1.0];
}
static NSColor *jniTrafficZoomColor(void) {
    return [NSColor colorWithSRGBRed:40.0 / 255.0 green:200.0 / 255.0 blue:64.0 / 255.0 alpha:1.0];
}
static NSColor *jniTrafficDisabledColor(NSView *view) {
    BOOL dark = NO;
    if (@available(macOS 10.14, *)) {
        NSAppearanceName name = [view.effectiveAppearance
            bestMatchFromAppearancesWithNames:@[ NSAppearanceNameDarkAqua, NSAppearanceNameAqua ]];
        dark = [name isEqualToString:NSAppearanceNameDarkAqua];
    }
    return dark ? [NSColor colorWithWhite:0.40 alpha:1.0]
                : [NSColor colorWithWhite:0.80 alpha:1.0];
}

static void jniFillTrafficCircle(NSView *button, NSColor *color) {
    NSRect r = button.frame;
    CGFloat d = fmin(MIN(r.size.width, r.size.height),
                     isTahoeOrLater() ? 14.0 : 12.0);
    NSRect oval = NSMakeRect(NSMidX(r) - d / 2.0, NSMidY(r) - d / 2.0, d, d);
    [color setFill];
    [[NSBezierPath bezierPathWithOvalInRect:oval] fill];
}

@implementation NucleusButtonsView

- (BOOL)isOpaque {
    return NO;
}

- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    for (NSTrackingArea *ta in self.trackingAreas) {
        [self removeTrackingArea:ta];
    }
    NSTrackingArea *ta = [[NSTrackingArea alloc]
        initWithRect:NSZeroRect
             options:(NSTrackingMouseEnteredAndExited |
                      NSTrackingActiveInKeyWindow |
                      NSTrackingInVisibleRect)
               owner:self
            userInfo:nil];
    [self addTrackingArea:ta];
}

- (void)applyHoverState {
    NSArray<NSView *> *buttons = self.subviews;
    if (buttons.count < 3) return;
    NSButton *closeBtn = (NSButton *)buttons[0];
    NSButton *minBtn = (NSButton *)buttons[1];
    NSButton *zoomBtn = (NSButton *)buttons[2];
    minBtn.enabled = NO;
    minBtn.hidden = YES;
    closeBtn.hidden = !_mouseInside;
    zoomBtn.hidden = !_mouseInside;
    [closeBtn setHighlighted:_mouseInside];
    [zoomBtn setHighlighted:_mouseInside];
    [self setNeedsDisplay:YES];
}

- (void)mouseEntered:(NSEvent *)event {
    if (_dispatching) return;
    _dispatching = YES;
    _mouseInside = YES;
    [super mouseEntered:event];
    [self applyHoverState];
    // Skip miniaturise (index 1) — it is disabled in fullscreen.
    NSArray<NSView *> *buttons = self.subviews;
    if (buttons.count >= 1) [buttons[0] mouseEntered:event];
    if (buttons.count >= 3) [buttons[2] mouseEntered:event];
    _dispatching = NO;
}

- (void)mouseExited:(NSEvent *)event {
    if (_dispatching) return;
    _dispatching = YES;
    _mouseInside = NO;
    [super mouseExited:event];
    [self applyHoverState];
    NSArray<NSView *> *buttons = self.subviews;
    if (buttons.count >= 1) [buttons[0] mouseExited:event];
    if (buttons.count >= 3) [buttons[2] mouseExited:event];
    _dispatching = NO;
}

- (void)drawRect:(NSRect)dirtyRect {
    (void)dirtyRect;
    NSArray<NSView *> *buttons = self.subviews;
    if (buttons.count < 3) return;
    if (!_mouseInside) {
        jniFillTrafficCircle(buttons[0], jniTrafficCloseColor());
        jniFillTrafficCircle(buttons[1], jniTrafficDisabledColor(self));
        jniFillTrafficCircle(buttons[2], jniTrafficZoomColor());
    } else {
        jniFillTrafficCircle(buttons[1], jniTrafficDisabledColor(self));
    }
}

// Private AppKit hook: standard window buttons ask their superview whether
// the traffic-light group is hovered before drawing the glyphs. Without it,
// pre-Tahoe systems never show the symbols on hover (mirrors JBR's
// AWTButtonsView). Miniaturise is never in the group — it is disabled.
- (BOOL)_mouseInGroup:(NSButton *)button {
    if (self.subviews.count >= 2 && button == self.subviews[1]) return NO;
    return _mouseInside;
}

@end

// ─── Fullscreen observer ────────────────────────────────────────────────────────

@interface NucleusFSObserver : NSObject
@property (nonatomic, weak) NSWindow *window;
@end

@implementation NucleusFSObserver

- (instancetype)initWithWindow:(NSWindow *)window {
    self = [super init];
    if (self) {
        _window = window;
        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        [nc addObserver:self selector:@selector(willEnterFullScreen:)
                   name:NSWindowWillEnterFullScreenNotification object:window];
        [nc addObserver:self selector:@selector(didEnterFullScreen:)
                   name:NSWindowDidEnterFullScreenNotification object:window];
        [nc addObserver:self selector:@selector(willExitFullScreen:)
                   name:NSWindowWillExitFullScreenNotification object:window];
        [nc addObserver:self selector:@selector(didExitFullScreen:)
                   name:NSWindowDidExitFullScreenNotification object:window];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

// About to enter fullscreen — remove constraints and drag view so macOS can animate cleanly
- (void)willEnterFullScreen:(NSNotification *)note {
    NSWindow *w = self.window;
    if (!w) return;

    removeDragView(w);
    removeExistingConstraints(w);
    // Remove toolbar before fullscreen animation to avoid white band glitch
    if ([objc_getAssociatedObject(w, &kLargeCornerRadiusKey) boolValue]) {
        w.toolbar = nil;
    }
    // Restore the standard chrome so AppKit's fullscreen animation can run.
    // Tahoe-only: on older macOS this briefly reveals the opaque native
    // title bar sliding to the top during the transition (issue #310).
    if (isTahoeOrLater()) {
        [w setTitlebarAppearsTransparent:NO];
        [w setTitleVisibility:NSWindowTitleVisible];
    }
    [w setMovable:YES];
}

// Finished entering fullscreen — install replacement buttons in the content view
- (void)didEnterFullScreen:(NSNotification *)note {
    NSWindow *w = self.window;
    if (!w) return;

    NSNumber *storedHeight = objc_getAssociatedObject(w, &kTitleBarHeightKey);
    float height = storedHeight ? [storedHeight floatValue] : kMinHeightForFullSize;

    installFullScreenButtons(w, height);

    // Reinstall the toolbar (removed in willEnterFullScreen to avoid a white
    // band glitch during the animation) so 26pt corners show in fullscreen too.
    if ([objc_getAssociatedObject(w, &kLargeCornerRadiusKey) boolValue] && !w.toolbar) {
        NSToolbar *toolbar = [[NSToolbar alloc] initWithIdentifier:@"NucleusToolbar"];
        toolbar.showsBaselineSeparator = NO;
        w.toolbar = toolbar;
    }

    // Install menu bar monitor if newFullscreenControls is enabled.
    BOOL newControls = [objc_getAssociatedObject(w, &kNewFullscreenControlsKey) boolValue];
    if (newControls) {
        installMenuBarMonitor(w);
    }

    // Hide the native titlebar container to prevent it from intercepting
    // click events that should reach the Compose content view. On non-notch
    // screens in fullscreen the titlebar sits at y=0, overlapping with the
    // Compose title bar area. Our replacement buttons (NucleusButtonsView)
    // live in the contentView and remain unaffected.
    {
        NSView *btn = [w standardWindowButton:NSWindowCloseButton];
        NSView *tb = btn ? btn.superview : nil;
        NSView *tbc = tb ? tb.superview : nil;
        if (tbc) {
            [tbc setHidden:YES];
        }
    }

    // The system may create NSToolbarFullScreenWindow lazily (e.g. on the
    // next run-loop cycle). Schedule a deferred neutralization pass.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        neutralizeToolbarFullScreenWindows();
    });
}

// About to exit fullscreen — remove replacement buttons, hide native title bar
// and hide the standard traffic lights so they don't appear at the wrong
// position during the transition animation
- (void)willExitFullScreen:(NSNotification *)note {
    NSWindow *w = self.window;
    if (!w) return;

    removeMenuBarMonitor(w);
    removeFullScreenButtons(w);

    // Restore the native titlebar container (hidden in didEnterFullScreen)
    // so the exit-fullscreen animation can use it.
    {
        NSView *btn = [w standardWindowButton:NSWindowCloseButton];
        NSView *tb = btn ? btn.superview : nil;
        NSView *tbc = tb ? tb.superview : nil;
        if (tbc && [tbc isHidden]) {
            [tbc setHidden:NO];
        }
    }

    [w setTitlebarAppearsTransparent:YES];
    [w setTitleVisibility:NSWindowTitleHidden];

    // Hide standard buttons during transition to prevent position glitch
    [[w standardWindowButton:NSWindowCloseButton] setHidden:YES];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:YES];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:YES];
}

// Finished exiting fullscreen — restore constraints, then reveal the buttons
- (void)didExitFullScreen:(NSNotification *)note {
    NSWindow *w = self.window;
    if (!w) return;

    NSNumber *storedHeight = objc_getAssociatedObject(w, &kTitleBarHeightKey);
    if (!storedHeight) return;

    float height = [storedHeight floatValue];
    [w setMovable:NO];
    ensureDragView(w);

    // Reinstall the invisible toolbar for 26pt corner radius (removed in
    // willEnterFullScreen to avoid a white band glitch during animation).
    if ([objc_getAssociatedObject(w, &kLargeCornerRadiusKey) boolValue] && !w.toolbar) {
        NSToolbar *toolbar = [[NSToolbar alloc] initWithIdentifier:@"NucleusToolbar"];
        toolbar.showsBaselineSeparator = NO;
        // Keep toolbar.visible = YES (default) so macOS renders 26pt corners
                // even in maximized mode. Combined with titlebarAppearsTransparent,
                // the empty toolbar is visually invisible.
        w.toolbar = toolbar;
    }

    applyConstraints(w, height);

    // Reveal buttons now that constraints are in place
    [[w standardWindowButton:NSWindowCloseButton] setHidden:NO];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:NO];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:NO];
}

@end

// ─── Zoom button responder ──────────────────────────────────────────────────────

// Temporarily re-enables window.movable when the mouse enters the zoom button,
// allowing macOS 15 window tiling to work even though movable is normally NO.
// Mirrors JBR's AWTWindowZoomButtonMouseResponder.
@interface NucleusZoomButtonResponder : NSObject
@property (nonatomic, weak) NSWindow *window;
@property (nonatomic, strong) NSTrackingArea *trackingArea;
@end

@implementation NucleusZoomButtonResponder

- (instancetype)initWithWindow:(NSWindow *)window {
    self = [super init];
    if (self) {
        _window = window;
        NSView *zoomButton = [window standardWindowButton:NSWindowZoomButton];
        if (zoomButton) {
            // NSTrackingInVisibleRect keeps the rect in sync with the button's
            // current bounds, so constraint updates don't leave a stale hit area.
            _trackingArea = [[NSTrackingArea alloc]
                initWithRect:NSZeroRect
                     options:(NSTrackingMouseEnteredAndExited |
                              NSTrackingActiveInKeyWindow |
                              NSTrackingInVisibleRect)
                       owner:self
                    userInfo:nil];
            [zoomButton addTrackingArea:_trackingArea];
        }
    }
    return self;
}

- (void)dealloc {
    if (_trackingArea) {
        NSView *zoomButton = _window ? [_window standardWindowButton:NSWindowZoomButton] : nil;
        if (zoomButton) {
            [zoomButton removeTrackingArea:_trackingArea];
        }
    }
}

- (void)mouseEntered:(NSEvent *)event {
    NSWindow *w = self.window;
    if (w && ![w isMovable]) {
        [w setMovable:YES];
    }
}

- (void)mouseExited:(NSEvent *)event {
    NSWindow *w = self.window;
    if (w && objc_getAssociatedObject(w, &kTitleBarHeightKey)) {
        [w setMovable:NO];
    }
}

@end

// ─── Native drag view ───────────────────────────────────────────────────────────

// Native NSView placed in the titlebar that handles window dragging via
// performWindowDragWithEvent: and double-click zoom/minimize.
// Mirrors JBR's AWTWindowDragView. All events are forwarded to the content
// view so AWT/Compose can process them normally.
// Pure pass-through view: forwards every event to the content view so
// AWT/Compose can process them. Window dragging is initiated by Compose
// via nativeStartWindowDrag when it detects an unconsumed drag, exactly
// mirroring JBR's forceHitTest approach where the decision lives in Compose.
@interface NucleusDragView : NSView
@property (atomic, strong) NSEvent *lastMouseDownEvent;
@end

@implementation NucleusDragView

- (BOOL)acceptsFirstMouse:(NSEvent *)event {
    return YES;
}

- (BOOL)shouldDelayWindowOrderingForEvent:(NSEvent *)event {
    return [[self.window contentView] shouldDelayWindowOrderingForEvent:event];
}

- (void)mouseDown:(NSEvent *)event {
    self.lastMouseDownEvent = event;
    [[self.window contentView] mouseDown:event];
}

- (void)mouseUp:(NSEvent *)event {
    self.lastMouseDownEvent = nil;
    [[self.window contentView] mouseUp:event];
}

- (void)mouseDragged:(NSEvent *)event {
    [[self.window contentView] mouseDragged:event];
}

- (void)mouseMoved:(NSEvent *)event {
    [[self.window contentView] mouseMoved:event];
}

- (void)rightMouseDown:(NSEvent *)event {
    [[self.window contentView] rightMouseDown:event];
}

- (void)rightMouseUp:(NSEvent *)event {
    [[self.window contentView] rightMouseUp:event];
}

- (void)rightMouseDragged:(NSEvent *)event {
    [[self.window contentView] rightMouseDragged:event];
}

- (void)otherMouseDown:(NSEvent *)event {
    [[self.window contentView] otherMouseDown:event];
}

- (void)otherMouseUp:(NSEvent *)event {
    [[self.window contentView] otherMouseUp:event];
}

- (void)otherMouseDragged:(NSEvent *)event {
    [[self.window contentView] otherMouseDragged:event];
}

- (void)mouseEntered:(NSEvent *)event {
    [[self.window contentView] mouseEntered:event];
}

- (void)mouseExited:(NSEvent *)event {
    [[self.window contentView] mouseExited:event];
}

- (void)scrollWheel:(NSEvent *)event {
    [[self.window contentView] scrollWheel:event];
}

@end


// ─── Fullscreen button helpers ──────────────────────────────────────────────────

// Neutralizes NSToolbarFullScreenWindow instances by hiding their content
// and making them pass-through for mouse events. This prevents the system's
// fullscreen title bar overlay from intercepting clicks that should reach
// the Compose content view — especially on non-notch screens where the
// overlay sits directly over the custom title bar area.
static void neutralizeToolbarFullScreenWindows(void) {
    Class cls = NSClassFromString(@"NSToolbarFullScreenWindow");
    if (!cls) return;
    for (NSWindow *win in [NSApp windows]) {
        if ([win isKindOfClass:cls]) {
            if (![win ignoresMouseEvents]) {
                [win setIgnoresMouseEvents:YES];
            }
            if (![win.contentView isHidden]) {
                [win.contentView setHidden:YES];
            }
        }
    }
}

// Computes button size and positions matching the constraint-based layout
// used in floating mode (applyConstraints), so there is no visual jump
// when transitioning between fullscreen and floating.
static void computeButtonMetrics(float titleBarHeight, float *outBtnWidth, float *outBtnHeight, float *outOffset) {
    float shrinkFactor = fminf(titleBarHeight / kMinHeightForFullSize, 1.0f);
    *outBtnWidth  = fminf(titleBarHeight * 0.5f, kMinHeightForFullSize * 0.5f);
    if (isTahoeOrLater()) {
        *outBtnHeight = (*outBtnWidth) * (14.0f / 12.0f) - 2.0f;
    } else {
        // Keep the pre-Tahoe native 14x16 pt aspect so the glyphs aren't
        // squashed on older macOS.
        *outBtnHeight = (*outBtnWidth) * (16.0f / 14.0f);
    }
    *outOffset    = shrinkFactor * defaultButtonOffset();
}

// Creates replacement traffic-light buttons in the content view,
// mirroring JBR's setWindowFullScreenControls.
// Button positions match the constraint-based layout used in floating mode.
static void installFullScreenButtons(NSWindow *window, float titleBarHeight) {
    // Don't double-install
    if (objc_getAssociatedObject(window, &kFullscreenButtonsKey)) return;

    NSView *origClose = [window standardWindowButton:NSWindowCloseButton];
    if (!origClose) return;

    // Neutralize the system's fullscreen title bar overlay
    neutralizeToolbarFullScreenWindows();

    // Compute button metrics matching floating mode
    float btnWidth, btnHeight, offset;
    computeButtonMetrics(titleBarHeight, &btnWidth, &btnHeight, &offset);

    // Create container spanning the full title bar height at the top of the content view
    BOOL isRTL = [objc_getAssociatedObject(window, &kRTLKey) boolValue];
    NucleusButtonsView *container = [[NucleusButtonsView alloc] init];
    NSView *parent = window.contentView;
    CGFloat y = parent.frame.size.height - titleBarHeight;
    float margin = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin);
    float containerWidth = margin + 2.0f * offset + btnWidth;
    CGFloat containerX = isRTL
        ? parent.frame.size.width - containerWidth
        : 0;
    [container setFrame:NSMakeRect(containerX, y, containerWidth, titleBarHeight)];

    // Drop FullScreen from the mask: copies built with it draw as inactive
    // gray and the miniaturise widget is born disabled (issue #531).
    NSUInteger masks = [window styleMask] & ~NSWindowStyleMaskFullScreen;

    // Create replacement buttons positioned with the same formula as applyConstraints.
    // In RTL mode, buttons are mirrored inside the container.
    NSArray<NSNumber *> *buttonTypes = @[
        @(NSWindowCloseButton), @(NSWindowMiniaturizeButton), @(NSWindowZoomButton)
    ];
    SEL actions[] = { @selector(performClose:), @selector(performMiniaturize:), @selector(toggleFullScreen:) };

    for (NSUInteger idx = 0; idx < 3; idx++) {
        NSButton *btn = [NSWindow standardWindowButton:[buttonTypes[idx] unsignedIntegerValue]
                                          forStyleMask:masks];
        CGFloat centerX;
        if (isRTL) {
            centerX = containerWidth - margin - idx * offset;
        } else {
            centerX = margin + idx * offset;
        }
        CGFloat centerY = titleBarHeight / 2.0f;
        [btn setFrame:NSMakeRect(centerX - btnWidth / 2.0f, centerY - btnHeight / 2.0f,
                                 btnWidth, btnHeight)];
        if (idx == 1) {
            // Miniaturise is a no-op while the window is fullscreen.
            [btn setEnabled:NO];
            [btn setTarget:nil];
            [btn setAction:NULL];
        } else {
            [btn setTarget:window];
            [btn setAction:actions[idx]];
        }
        [btn setHidden:YES];
        [container addSubview:btn];
    }

    [parent addSubview:container];
    [container applyHoverState];

    objc_setAssociatedObject(window, &kFullscreenButtonsKey, container,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// Removes the replacement fullscreen buttons.
static void removeFullScreenButtons(NSWindow *window) {
    NucleusButtonsView *container = objc_getAssociatedObject(window, &kFullscreenButtonsKey);
    if (!container) return;

    [container removeFromSuperview];
    objc_setAssociatedObject(window, &kFullscreenButtonsKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// Returns the last raw menu bar offset stored by the native event monitor.
// Thread-safe: objc_getAssociatedObject uses internal locking.
static float getMenuBarOffsetForWindow(NSWindow *window) {
    NSNumber *stored = objc_getAssociatedObject(window, &kMenuBarLastRawOffsetKey);
    return stored ? [stored floatValue] : 0.0f;
}

// ─── Menu bar event monitor ─────────────────────────────────────────────────────

// Installs observers that detect menu bar visibility changes:
// 1) NSEvent local monitor — catches mouse-triggered menu bar show/hide.
// 2) NSMenuDidBeginTrackingNotification — catches keyboard-triggered menu
//    activation (Control+F2 / Fn+Control+F2), independent of mouse events.
// 3) NSMenuDidEndTrackingNotification — catches when menu tracking ends
//    and the menu bar may be about to hide.
//
// All handlers run on the macOS main thread, so AppKit reads are safe.
// When the offset changes, Kotlin is notified via JNI callback.
static void installMenuBarMonitor(NSWindow *window) {
    // Safari-style fullscreen title bar (slide down with the menu bar) is a
    // Tahoe-era behaviour; on older macOS it produces a phantom padding and
    // a seam line in the title-bar area (issue #310 B4/C).
    if (!isTahoeOrLater()) return;
    removeMenuBarMonitor(window);

    __weak NSWindow *weakWindow = window;

    // Shared check block — reads the current menu bar state and notifies
    // Kotlin via JNI callback if the offset changed since last check.
    void (^checkMenuBar)(void) = ^{
        if (atomic_load(&sShutdownInProgress)) return;
        NSWindow *w = weakWindow;
        if (!w) return;
        if (!(w.styleMask & NSWindowStyleMaskFullScreen)) return;

        // Re-neutralize in case the system re-created the overlay window
        neutralizeToolbarFullScreenWindows();

        float offset = 0.0f;

        // On screens with a notch (MacBook Pro 14"/16") the menu bar
        // lives permanently in the notch area — no offset needed, the
        // title bar sits flush at the top of the usable content area.
        // On non-notch screens the menu bar slides in/out dynamically,
        // so we offset by its height when visible.
        NSScreen *screen = w.screen;
        BOOL hasNotch = NO;
        if (@available(macOS 12.0, *)) {
            hasNotch = screen && screen.safeAreaInsets.top > 0;
        }

        if (!hasNotch && [NSMenu menuBarVisible]) {
            NSMenu *mainMenu = [[NSApplication sharedApplication] mainMenu];
            if (mainMenu) offset = (float)[mainMenu menuBarHeight];
        }

        NSNumber *lastRaw = objc_getAssociatedObject(w, &kMenuBarLastRawOffsetKey);
        float lastOffset = lastRaw ? [lastRaw floatValue] : -1.0f;

        if (offset != lastOffset) {
            objc_setAssociatedObject(w, &kMenuBarLastRawOffsetKey, @(offset),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            notifyMenuBarOffsetChanged(w, offset);
        }
    };

    // (1) Mouse event monitor
    id eventMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:
        (NSEventMaskMouseMoved | NSEventMaskLeftMouseDown |
         NSEventMaskLeftMouseUp | NSEventMaskLeftMouseDragged |
         NSEventMaskMouseEntered | NSEventMaskMouseExited)
        handler:^NSEvent *(NSEvent *event) {
            checkMenuBar();
            return event;
        }];

    // (2) + (3) Notification observers for keyboard-triggered menu tracking
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    id beginObserver = [nc addObserverForName:NSMenuDidBeginTrackingNotification
                                      object:nil
                                       queue:[NSOperationQueue mainQueue]
                                  usingBlock:^(NSNotification *note) {
        checkMenuBar();
    }];
    id endObserver = [nc addObserverForName:NSMenuDidEndTrackingNotification
                                    object:nil
                                     queue:[NSOperationQueue mainQueue]
                                usingBlock:^(NSNotification *note) {
        checkMenuBar();
    }];

    // Store all observers in a dictionary for cleanup.
    NSDictionary *monitors = @{
        @"event": eventMonitor,
        @"beginTracking": beginObserver,
        @"endTracking": endObserver,
    };
    objc_setAssociatedObject(window, &kMenuBarMonitorKey, monitors,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    // Fire an initial check so the offset is notified immediately — especially
    // important on notch screens where the offset is constant and won't change
    // in response to mouse/keyboard events.
    checkMenuBar();
}

static void removeMenuBarMonitor(NSWindow *window) {
    NSDictionary *monitors = objc_getAssociatedObject(window, &kMenuBarMonitorKey);
    if (monitors) {
        id eventMonitor = monitors[@"event"];
        if (eventMonitor) [NSEvent removeMonitor:eventMonitor];
        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        id begin = monitors[@"beginTracking"];
        if (begin) [nc removeObserver:begin];
        id end = monitors[@"endTracking"];
        if (end) [nc removeObserver:end];
    }
    objc_setAssociatedObject(window, &kMenuBarMonitorKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(window, &kMenuBarLastRawOffsetKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    // Clear the Compose-side offset so stale values don't linger if the
    // monitor is re-installed later (e.g. newFullscreenControls toggled).
    objc_setAssociatedObject(window, &kMenuBarOffsetKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// Repositions the fullscreen button container (called from layout passes).
// Uses the same metrics as installFullScreenButtons / applyConstraints.
// When newFullscreenControls is active, accounts for the menu bar offset
// so buttons move down with the title bar when the menu bar appears.
static void updateFullScreenButtonsPosition(NSWindow *window) {
    NucleusButtonsView *container = objc_getAssociatedObject(window, &kFullscreenButtonsKey);
    if (!container) return;

    // Re-neutralize in case the system re-created the overlay window
    neutralizeToolbarFullScreenWindows();

    NSView *parent = window.contentView;
    if (!parent) return;

    NSNumber *storedHeight = objc_getAssociatedObject(window, &kTitleBarHeightKey);
    float titleBarHeight = storedHeight ? [storedHeight floatValue] : kMinHeightForFullSize;

    float btnWidth, btnHeight, offset;
    computeButtonMetrics(titleBarHeight, &btnWidth, &btnHeight, &offset);

    // Read the menu bar offset stored by Compose via nativeSetMenuBarOffset.
    NSNumber *storedMenuBarOffset = objc_getAssociatedObject(window, &kMenuBarOffsetKey);
    float menuBarOffset = storedMenuBarOffset ? [storedMenuBarOffset floatValue] : 0.0f;

    BOOL isRTL = [objc_getAssociatedObject(window, &kRTLKey) boolValue];
    float margin = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin);
    float containerWidth = margin + 2.0f * offset + btnWidth;
    CGFloat y = parent.frame.size.height - titleBarHeight - menuBarOffset;
    CGFloat containerX = isRTL
        ? parent.frame.size.width - containerWidth
        : 0;
    [container setFrame:NSMakeRect(containerX, y, containerWidth, titleBarHeight)];

    // Reposition each button inside the container
    NSArray<NSView *> *buttons = [container subviews];
    for (NSUInteger idx = 0; idx < buttons.count && idx < 3; idx++) {
        NSView *btn = buttons[idx];
        CGFloat centerX;
        if (isRTL) {
            centerX = containerWidth - margin - idx * offset;
        } else {
            centerX = margin + idx * offset;
        }
        CGFloat centerY = titleBarHeight / 2.0f;
        [btn setFrame:NSMakeRect(centerX - btnWidth / 2.0f, centerY - btnHeight / 2.0f,
                                 btnWidth, btnHeight)];
    }
    [container applyHoverState];
}

// ─── _adjustWindowToScreen swizzle ──────────────────────────────────────────────

// macOS calls _adjustWindowToScreen for window snapping/tiling near screen edges.
// Since we set movable=NO, this callback is blocked. Override to temporarily
// re-enable movable (mirrors JBR's AWTWindow_Normal._adjustWindowToScreen).
// Re-entrancy guard prevents crashes when the original IMP or
// updateFullScreenButtonsPosition triggers another _adjustWindowToScreen call
// on older macOS versions.
static BOOL sInAdjustWindow = NO;

static void nucleus_adjustWindowToScreen(id self, SEL _cmd) {
    if (sInAdjustWindow) {
        // Re-entrant call — just forward to the original implementation
        if (sOriginalAdjustWindowToScreen) {
            ((void (*)(id, SEL))sOriginalAdjustWindowToScreen)(self, _cmd);
        }
        return;
    }
    sInAdjustWindow = YES;

    NSNumber *storedHeight = objc_getAssociatedObject(self, &kTitleBarHeightKey);
    BOOL needsRestore = storedHeight && ![(NSWindow *)self isMovable];

    if (needsRestore) {
        [(NSWindow *)self setMovable:YES];
    }

    if (sOriginalAdjustWindowToScreen) {
        ((void (*)(id, SEL))sOriginalAdjustWindowToScreen)(self, _cmd);
    }

    updateFullScreenButtonsPosition((NSWindow *)self);

    if (needsRestore) {
        [(NSWindow *)self setMovable:NO];
    }

    sInAdjustWindow = NO;
}

// Called only from the main queue (via dispatch_async in nativeApplyTitleBar),
// so no synchronization is needed beyond the idempotency check.
static void ensureAdjustWindowSwizzle(NSWindow *window) {
    Class cls = object_getClass(window);
    SEL sel = NSSelectorFromString(@"_adjustWindowToScreen");
    Method method = class_getInstanceMethod(cls, sel);
    if (!method) return;
    // Already swizzled (this class or an ancestor we already patched)
    if (method_getImplementation(method) == (IMP)nucleus_adjustWindowToScreen) return;
    sOriginalAdjustWindowToScreen = method_getImplementation(method);
    method_setImplementation(method, (IMP)nucleus_adjustWindowToScreen);
}

// ─── Zoom button responder helpers ──────────────────────────────────────────────

static void installZoomButtonResponder(NSWindow *window) {
    if (objc_getAssociatedObject(window, &kZoomResponderKey)) return;

    NucleusZoomButtonResponder *responder =
        [[NucleusZoomButtonResponder alloc] initWithWindow:window];
    objc_setAssociatedObject(window, &kZoomResponderKey, responder,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

static void removeZoomButtonResponder(NSWindow *window) {
    objc_setAssociatedObject(window, &kZoomResponderKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// ─── Drag view helpers ──────────────────────────────────────────────────────────

// Installs the drag view once in the titlebar. Subsequent calls are no-ops.
// The drag view persists across constraint updates so an in-progress drag
// is never interrupted by Compose layout passes.
static void ensureDragView(NSWindow *window) {
    if (objc_getAssociatedObject(window, &kDragViewKey)) return;

    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    if (!closeBtn) return;
    NSView *titlebar = closeBtn.superview;
    if (!titlebar) return;

    NucleusDragView *dragView = [[NucleusDragView alloc] init];
    [titlebar addSubview:dragView positioned:NSWindowBelow relativeTo:closeBtn];
    objc_setAssociatedObject(window, &kDragViewKey, dragView, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

static void removeDragView(NSWindow *window) {
    NucleusDragView *dragView = objc_getAssociatedObject(window, &kDragViewKey);
    if (!dragView) return;
    [dragView removeFromSuperview];
    objc_setAssociatedObject(window, &kDragViewKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// ─── Constraint helpers ─────────────────────────────────────────────────────────

static void removeExistingConstraints(NSWindow *window) {
    NSMutableArray *existing = objc_getAssociatedObject(window, &kTitleBarConstraintsKey);
    if (!existing) return;

    [NSLayoutConstraint deactivateConstraints:existing];
    objc_setAssociatedObject(window, &kTitleBarConstraintsKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    // Note: drag view is NOT removed here — it persists across constraint
    // updates so an in-progress drag is never interrupted.

    // Restore autoresizing mask so AppKit can manage layout again
    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    if (!closeBtn) return;
    NSView *titlebar          = closeBtn.superview;
    NSView *titlebarContainer = titlebar ? titlebar.superview : nil;

    if (titlebarContainer) {
        titlebarContainer.translatesAutoresizingMaskIntoConstraints = YES;
    }
    if (titlebar) {
        titlebar.translatesAutoresizingMaskIntoConstraints = YES;
    }
    closeBtn.translatesAutoresizingMaskIntoConstraints = YES;
    NSView *miniBtn = [window standardWindowButton:NSWindowMiniaturizeButton];
    NSView *zoomBtn = [window standardWindowButton:NSWindowZoomButton];
    if (miniBtn) miniBtn.translatesAutoresizingMaskIntoConstraints = YES;
    if (zoomBtn) zoomBtn.translatesAutoresizingMaskIntoConstraints = YES;
}

static void applyConstraints(NSWindow *window, float height) {
    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    NSView *miniBtn  = [window standardWindowButton:NSWindowMiniaturizeButton];
    NSView *zoomBtn  = [window standardWindowButton:NSWindowZoomButton];
    if (!closeBtn || !miniBtn || !zoomBtn) return;

    NSView *titlebar          = closeBtn.superview;
    NSView *titlebarContainer = titlebar ? titlebar.superview : nil;
    NSView *themeFrame        = titlebarContainer ? titlebarContainer.superview : nil;
    if (!themeFrame) return;

    removeExistingConstraints(window);

    NSMutableArray *constraints = [NSMutableArray array];

    titlebarContainer.translatesAutoresizingMaskIntoConstraints = NO;
    [constraints addObjectsFromArray:@[
        [titlebarContainer.leftAnchor  constraintEqualToAnchor:themeFrame.leftAnchor],
        [titlebarContainer.widthAnchor constraintEqualToAnchor:themeFrame.widthAnchor],
        [titlebarContainer.topAnchor   constraintEqualToAnchor:themeFrame.topAnchor],
        [titlebarContainer.heightAnchor constraintEqualToConstant:height],
    ]];

    titlebar.translatesAutoresizingMaskIntoConstraints = NO;
    [constraints addObjectsFromArray:@[
        [titlebar.leftAnchor   constraintEqualToAnchor:titlebarContainer.leftAnchor],
        [titlebar.rightAnchor  constraintEqualToAnchor:titlebarContainer.rightAnchor],
        [titlebar.topAnchor    constraintEqualToAnchor:titlebarContainer.topAnchor],
        [titlebar.bottomAnchor constraintEqualToAnchor:titlebarContainer.bottomAnchor],
    ]];

    // Add constraints for the drag view (installed once by ensureDragView)
    NucleusDragView *dragView = objc_getAssociatedObject(window, &kDragViewKey);
    if (dragView) {
        dragView.translatesAutoresizingMaskIntoConstraints = NO;
        [constraints addObjectsFromArray:@[
            [dragView.leftAnchor   constraintEqualToAnchor:titlebarContainer.leftAnchor],
            [dragView.rightAnchor  constraintEqualToAnchor:titlebarContainer.rightAnchor],
            [dragView.topAnchor    constraintEqualToAnchor:titlebarContainer.topAnchor],
            [dragView.bottomAnchor constraintEqualToAnchor:titlebarContainer.bottomAnchor],
        ]];
    }

    BOOL isRTL = [objc_getAssociatedObject(window, &kRTLKey) boolValue];
    float shrinkFactor = fminf(height / kMinHeightForFullSize, 1.0f);
    float offset       = shrinkFactor * defaultButtonOffset();
    float extraInset   = window.toolbar ? kToolbarExtraInset : 0.0f;
    float margin       = fminf(height / 2.0f, kMaxButtonLeftMargin) + extraInset;

    NSLayoutAnchor *anchorEdge = isRTL
        ? titlebarContainer.rightAnchor
        : titlebarContainer.leftAnchor;

    // Pre-Tahoe keeps the native 14x16 pt button aspect (no -2 pt trim).
    CGFloat sizeRatio    = isTahoeOrLater() ? (14.0 / 12.0) : (16.0 / 14.0);
    CGFloat sizeConstant = isTahoeOrLater() ? -2.0 : 0.0;

    NSArray *buttons = @[closeBtn, miniBtn, zoomBtn];
    [buttons enumerateObjectsUsingBlock:^(NSView *btn, NSUInteger idx, BOOL *stop) {
        btn.translatesAutoresizingMaskIntoConstraints = NO;
        float c = margin + idx * offset;
        [constraints addObjectsFromArray:@[
            [btn.widthAnchor  constraintLessThanOrEqualToAnchor:titlebarContainer.heightAnchor
                                                     multiplier:0.5],
            [btn.heightAnchor constraintEqualToAnchor:btn.widthAnchor
                                           multiplier:sizeRatio
                                             constant:sizeConstant],
            [btn.centerYAnchor constraintEqualToAnchor:titlebarContainer.topAnchor
                                              constant:height / 2.0f],
            [btn.centerXAnchor constraintEqualToAnchor:anchorEdge
                                              constant:(isRTL ? -c : c)],
        ]];
    }];

    [NSLayoutConstraint activateConstraints:constraints];
    objc_setAssociatedObject(window, &kTitleBarConstraintsKey, constraints,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

static void ensureFullscreenObserver(NSWindow *window) {
    NucleusFSObserver *existing = objc_getAssociatedObject(window, &kFullscreenObserverKey);
    if (existing) return;

    NucleusFSObserver *observer = [[NucleusFSObserver alloc] initWithWindow:window];
    objc_setAssociatedObject(window, &kFullscreenObserverKey, observer,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

static void removeFullscreenObserver(NSWindow *window) {
    objc_setAssociatedObject(window, &kFullscreenObserverKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// ─── NSWindow pointer extraction from AWT Window ────────────────────────────────

// Extracts the native NSWindow pointer from a java.awt.Window via JNI.
// Uses direct field access to Component.peer (bypasses module system entirely).
// JNI GetFieldID/GetObjectField don't check module boundaries or access modifiers,
// so this works in both standard JVM and GraalVM native-image.
static jlong getNSWindowPtrFromAWTWindow(JNIEnv *env, jobject awtWindow) {
    if (!awtWindow) return 0;

    // Direct field access: java.awt.Component.peer (package-private field)
    // JNI doesn't check access modifiers, so this works regardless of module system.
    jclass componentClass = (*env)->FindClass(env, "java/awt/Component");
    if (!componentClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    jfieldID peerField = (*env)->GetFieldID(env, componentClass,
        "peer", "Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, componentClass);
    if (!peerField || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    jobject peer = (*env)->GetObjectField(env, awtWindow, peerField);
    if (!peer) return 0;

    // peer.getPlatformWindow() — LWWindowPeer method
    jclass peerClass = (*env)->GetObjectClass(env, peer);
    jmethodID getPlatformWindow = (*env)->GetMethodID(env, peerClass,
        "getPlatformWindow", "()Lsun/lwawt/PlatformWindow;");
    (*env)->DeleteLocalRef(env, peerClass);
    if (!getPlatformWindow || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return 0;
    }

    jobject platformWindow = (*env)->CallObjectMethod(env, peer, getPlatformWindow);
    (*env)->DeleteLocalRef(env, peer);
    if (!platformWindow || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    // platformWindow.ptr — declared in CFRetainedResource, an ancestor of CPlatformWindow.
    // Walk the hierarchy rather than assuming a fixed depth, so JBR refactors don't silently break this.
    jfieldID ptrField = NULL;
    jclass cls = (*env)->GetObjectClass(env, platformWindow);
    while (cls) {
        ptrField = (*env)->GetFieldID(env, cls, "ptr", "J");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            ptrField = NULL;
            jclass parent = (*env)->GetSuperclass(env, cls);
            (*env)->DeleteLocalRef(env, cls);
            cls = parent;
        } else {
            (*env)->DeleteLocalRef(env, cls);
            break;
        }
    }

    if (!ptrField) {
        (*env)->DeleteLocalRef(env, platformWindow);
        return 0;
    }

    jlong result = (*env)->GetLongField(env, platformWindow, ptrField);
    (*env)->DeleteLocalRef(env, platformWindow);
    return result;
}

// ─── JNI exports ────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeGetNSWindowPtr(
    JNIEnv *env, jclass clazz, jobject awtWindow) {
    return getNSWindowPtrFromAWTWindow(env, awtWindow);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeApplyTitleBar(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr, jfloat heightPt) {

    if (nsWindowPtr == 0) return 0.0f;

    // This is a synchronous JNI call, so the calling Java thread holds a reference
    // to the window's Java peer, keeping the NSWindow alive for the duration.
    // objc_getAssociatedObject is thread-safe for reads, so no dispatch to main needed here.
    NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
    BOOL largeRadius = [objc_getAssociatedObject(window, &kLargeCornerRadiusKey) boolValue];
    float extraInset = largeRadius ? kToolbarExtraInset : 0.0f;

    float shrink     = fminf(heightPt / kMinHeightForFullSize, 1.0f);
    float btnOffset  = shrink * defaultButtonOffset();
    float leftMargin = fminf(heightPt / 2.0f, kMaxButtonLeftMargin) + extraInset;
    float leftInset  = 2.0f * leftMargin + 2.0f * btnOffset;
    float capturedHeight = heightPt;

    // Capture the raw pointer value — do NOT create a __weak reference here.
    // This function is called from a Java thread, and if the NSWindow has
    // already been deallocated on the main thread, creating a __weak
    // reference would crash in objc_initWeak (EXC_BAD_ACCESS).
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            // Verify the window is still alive by checking NSApp.windows.
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;

            // Store the desired height for fullscreen restore
            objc_setAssociatedObject(w, &kTitleBarHeightKey,
                                     @(capturedHeight), OBJC_ASSOCIATION_RETAIN_NONATOMIC);

            ensureFullscreenObserver(w);
            ensureAdjustWindowSwizzle(w);
            installZoomButtonResponder(w);

            if ((w.styleMask & NSWindowStyleMaskFullScreen) != 0) {
                // In fullscreen: update replacement button positions
                updateFullScreenButtonsPosition(w);
                return;
            }

            [w setTitlebarAppearsTransparent:YES];
            [w setTitleVisibility:NSWindowTitleHidden];
            [w setMovable:NO];
            ensureDragView(w);
            applyConstraints(w, capturedHeight);
        }
    });

    return leftInset;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeResetTitleBar(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    // Capture the raw pointer value — do NOT create a __weak reference here.
    // This function is called from a Java thread, and if the NSWindow has
    // already been deallocated on the main thread, creating a __weak
    // reference would crash in objc_initWeak (EXC_BAD_ACCESS).
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            // Verify the window is still alive by checking NSApp.windows.
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            removeMenuBarMonitor(w);
            removeFullScreenButtons(w);
            removeFullscreenObserver(w);
            removeZoomButtonResponder(w);
            removeDragView(w);
            removeExistingConstraints(w);
            objc_setAssociatedObject(w, &kTitleBarHeightKey, nil,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            objc_setAssociatedObject(w, &kNewFullscreenControlsKey, nil,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            objc_setAssociatedObject(w, &kMenuBarOffsetKey, nil,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            objc_setAssociatedObject(w, &kLargeCornerRadiusKey, nil,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            objc_setAssociatedObject(w, &kRTLKey, nil,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            w.toolbar = nil;
            [w setTitlebarAppearsTransparent:NO];
            [w setTitleVisibility:NSWindowTitleVisible];
            [w setMovable:YES];
        }
    });
}

// Called from Kotlin on each layout pass during fullscreen to keep
// the replacement buttons positioned correctly.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeUpdateFullScreenButtons(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            updateFullScreenButtonsPosition(w);
        }
    });
}

// Performs the macOS title bar double-click action (zoom or minimize)
// respecting the user's system preference (AppleActionOnDoubleClick).
// Called from Compose when an unconsumed double-click is detected.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativePerformTitleBarDoubleClickAction(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            NSString *action = [[NSUserDefaults standardUserDefaults]
                stringForKey:@"AppleActionOnDoubleClick"];
            if (action && [action caseInsensitiveCompare:@"Minimize"] == NSOrderedSame) {
                [w performMiniaturize:nil];
            } else if (!action || [action caseInsensitiveCompare:@"None"] != NSOrderedSame) {
                [w performZoom:nil];
            }
        }
    });
}

// Initiates a native window drag using the saved mouseDown event.
// Called from the EDT when Compose detects an unconsumed drag in the title bar.
// This mirrors JBR's forceHitTest(false) path where Compose decides the drag.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeStartWindowDrag(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    // Read associated objects while the window is guaranteed alive (synchronous JNI call).
    NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
    NucleusDragView *dragView = objc_getAssociatedObject(window, &kDragViewKey);
    if (!dragView) return;

    NSEvent *event = dragView.lastMouseDownEvent;
    if (!event) return;
    dragView.lastMouseDownEvent = nil;

    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            // Temporarily re-enable movable so performWindowDragWithEvent:
            // works on macOS < 26 where the system expects movable=YES.
            // Mirrors JBR's forceHitTest(false) approach.
            NSNumber *storedHeight = objc_getAssociatedObject(w, &kTitleBarHeightKey);
            BOOL needsRestore = storedHeight && ![w isMovable];
            if (needsRestore) [w setMovable:YES];
            [w performWindowDragWithEvent:event];
            if (needsRestore) [w setMovable:NO];
        }
    });
}

// Stores the newFullscreenControls flag on the window.
// When enabled, the title bar and its traffic-light buttons are pushed down
// by the menu bar height whenever the auto-hidden menu bar becomes visible
// in fullscreen — mirroring Safari's fullscreen title bar behavior.
// Also installs/removes the menu bar event monitor if already in fullscreen.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeSetNewFullscreenControls(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr, jboolean enabled) {

    if (nsWindowPtr == 0) return;
    ensureJVMCached(env);
    void *rawPtr = (void *)nsWindowPtr;
    // Force-disable on pre-Tahoe systems — see installMenuBarMonitor.
    BOOL flag = (BOOL)enabled && isTahoeOrLater();
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            objc_setAssociatedObject(w, &kNewFullscreenControlsKey, @(flag),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            // Install or remove monitor if already in fullscreen.
            if (w.styleMask & NSWindowStyleMaskFullScreen) {
                if (flag) {
                    installMenuBarMonitor(w);
                } else {
                    removeMenuBarMonitor(w);
                }
            }
        }
    });
}

// Returns the last known menu bar offset in points.
// Reads the value stored by the native event monitor (thread-safe).
JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeGetMenuBarOffset(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return 0.0f;
    NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
    return getMenuBarOffsetForWindow(window);
}

// Stores the current menu bar offset (in points) as seen by Compose.
// Called from the polling loop so that nativeUpdateFullScreenButtons
// can position the traffic-light buttons at the same Y offset,
// keeping native buttons and Compose title bar perfectly in sync.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeSetMenuBarOffset(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr, jfloat offsetPt) {

    if (nsWindowPtr == 0) return;
    void *rawPtr = (void *)nsWindowPtr;
    // Immediately reposition buttons on the main queue.
    // Store the offset and reposition atomically on the main thread to avoid
    // a race with window disposal (objc_setAssociatedObject on a freed object).
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            objc_setAssociatedObject(w, &kMenuBarOffsetKey, @(offsetPt),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            updateFullScreenButtonsPosition(w);
        }
    });
}

// Installs an NSEvent local monitor that detects menu bar visibility
// changes on every mouse event and notifies Kotlin via JNI callback.
// Event-driven: no timer, no polling.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeInstallMenuBarMonitor(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    ensureJVMCached(env);
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            installMenuBarMonitor(w);
        }
    });
}

// Removes the native event monitor and clears the stored raw offset.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeRemoveMenuBarMonitor(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {

    if (nsWindowPtr == 0) return;
    // Capture the raw pointer value — do NOT create a __weak reference here.
    // This function is called from a Java thread, and if the NSWindow has
    // already been deallocated on the main thread, creating a __weak
    // reference would crash in objc_initWeak (EXC_BAD_ACCESS).
    void *rawPtr = (void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            // Verify the window is still alive by checking NSApp.windows.
            for (NSWindow *w in [NSApp windows]) {
                if ((__bridge void *)w == rawPtr) {
                    removeMenuBarMonitor(w);
                    return;
                }
            }
        }
    });
}

// Installs or removes an invisible NSToolbar to trigger macOS 26pt corner radius.
// Also stores the preference so the fullscreen observer can manage the toolbar
// around fullscreen transitions (remove before enter, reinstall after).
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeSetLargeCornerRadius(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr, jboolean enabled) {

    if (nsWindowPtr == 0) return;
    void *rawPtr = (void *)nsWindowPtr;
    // Pre-Tahoe systems do not draw the new corners even with a toolbar
    // attached; force-disable so we don't install a useless toolbar that
    // shifts the buttons (kToolbarExtraInset) and spawns the AppKit
    // NSToolbarFullScreenWindow overlay in fullscreen (issue #310).
    BOOL flag = (enabled == JNI_TRUE) && isTahoeOrLater();

    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            objc_setAssociatedObject(w, &kLargeCornerRadiusKey, @(flag),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            if (flag) {
                if (!w.toolbar) {
                    // Enable full-size content view and transparent title bar BEFORE
                    // adding the toolbar, so AppKit treats the toolbar as part of the
                    // existing content area instead of growing the window frame to
                    // accommodate it. Without this, assigning w.toolbar expands the
                    // frame height by the toolbar chrome, which later causes Compose's
                    // center alignment to be off by half that extra height.
                    [w setStyleMask:([w styleMask] | NSWindowStyleMaskFullSizeContentView)];
                    [w setTitlebarAppearsTransparent:YES];
                    NSToolbar *toolbar = [[NSToolbar alloc] initWithIdentifier:@"NucleusToolbar"];
                    toolbar.showsBaselineSeparator = NO;
                    // Keep toolbar.visible = YES (default) so macOS renders 26pt corners
                    // even in maximized mode. Combined with titlebarAppearsTransparent,
                    // the empty toolbar is visually invisible.
                    w.toolbar = toolbar;
                }
            } else if (w.toolbar) {
                w.toolbar = nil;
                // Symmetrically revert the style/appearance changes applied
                // when the toolbar was installed, so toggling the modifier
                // off at runtime restores the standard title bar instead of
                // leaving a transparent / full-size-content-view residue.
                [w setStyleMask:([w styleMask] & ~NSWindowStyleMaskFullSizeContentView)];
                [w setTitlebarAppearsTransparent:NO];
            }
            // Re-apply constraints so button positions update for the new inset
            NSNumber *storedHeight = objc_getAssociatedObject(w, &kTitleBarHeightKey);
            if (storedHeight && !(w.styleMask & NSWindowStyleMaskFullScreen)) {
                applyConstraints(w, [storedHeight floatValue]);
            }
        }
    });
}

// Disables native → JVM callbacks and removes all menu bar monitors.
// Must be called from a JVM shutdown hook (on a Java thread) before the JVM
// starts tearing down, to prevent notifyMenuBarOffsetChanged from calling
// CallStaticVoidMethod on a half-destroyed JVM.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeShutdown(
    JNIEnv *env, jclass clazz) {

    // Signal all pending dispatch_async blocks to bail out immediately.
    atomic_store(&sShutdownInProgress, true);

    // Immediately prevent any further JNI callbacks from the main thread.
    atomic_store(&sCallbacksEnabled, false);

    // Asynchronously remove all menu bar monitors on the main queue.
    // dispatch_async (not dispatch_sync) avoids a deadlock: if a previously
    // queued dispatch_async block is already executing on the main thread
    // (past its sShutdownInProgress check), dispatch_sync would block this
    // thread while the JVM tears down concurrently, causing the in-flight
    // block to access invalid state → SIGSEGV → abort.
    // The atomic flags set above already prevent any JNI callback or
    // meaningful work, so synchronous cleanup is unnecessary.
    dispatch_async(dispatch_get_main_queue(), ^{
        for (NSWindow *w in [NSApp windows]) {
            if (objc_getAssociatedObject(w, &kMenuBarMonitorKey)) {
                removeMenuBarMonitor(w);
            }
        }
    });
}

// Sets the RTL (right-to-left) flag on the window.
// When enabled, the traffic-light buttons are positioned on the right side
// of the title bar, mirroring the layout for RTL locales (Hebrew, Arabic, etc.).
// Re-applies constraints immediately so the change is visible without delay.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_utils_macos_JniMacTitleBarBridge_nativeSetRTL(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr, jboolean rtl) {

    if (nsWindowPtr == 0) return;
    void *rawPtr = (void *)nsWindowPtr;
    BOOL flag = (rtl == JNI_TRUE);

    dispatch_async(dispatch_get_main_queue(), ^{
        if (atomic_load(&sShutdownInProgress)) return;
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            objc_setAssociatedObject(w, &kRTLKey, @(flag),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            // Re-apply constraints so buttons move to the correct side
            NSNumber *storedHeight = objc_getAssociatedObject(w, &kTitleBarHeightKey);
            if (storedHeight) {
                if (w.styleMask & NSWindowStyleMaskFullScreen) {
                    updateFullScreenButtonsPosition(w);
                } else {
                    applyConstraints(w, [storedHeight floatValue]);
                }
            }
        }
    });
}

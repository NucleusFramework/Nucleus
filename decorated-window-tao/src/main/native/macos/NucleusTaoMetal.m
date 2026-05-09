// NucleusTaoMetal.m — ObjC helper that turns the NSView created by Tao into a
// Metal-rendering surface usable from Skiko, plus traffic-light button
// repositioning to match a custom Compose-drawn title bar height.
//
// Compiled into libnucleus_tao_metal.dylib by build.sh, separate from the Rust
// crate so we keep ObjC/AppKit out of the Rust build pipeline.
//
// The pipeline per frame is:
//
//   1. JVM calls beginFrame(layerHandle)        →  drawable + texture + size
//   2. JVM wraps texture in a Skia Surface and renders the ComposeScene
//   3. JVM calls flushAndSubmit() on the Surface (Metal command buffer fires)
//   4. JVM calls present(layerHandle, drawable) →  presents drawable on screen

#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#import <objc/runtime.h>
#import <stdatomic.h>
#import <stdio.h>
#import <jni.h>

#define NTLOG(fmt, ...) do { (void)0; } while (0)

// Associated-object key used to hang the constraints array on each NSWindow
// so we can deactivate the previous set before applying a new one.
static const char kTaoConstraintsKey = 1;
// Associated-object key for the fullscreen transition observer.
static const char kTaoFSObserverKey  = 2;
// Associated-object key holding the attachment handle for the window so the
// fullscreen observer can reattach the CAMetalLayer post-transition.
static const char kTaoAttachmentKey  = 3;
// Associated-object key holding the last title-bar height applied by
// applyButtonConstraints, so the FS observer can re-apply on didExitFullScreen.
static const char kTaoTitleBarHeightKey = 4;
// Holds the replacement-buttons container we install in the contentView when
// the window enters fullscreen. Removed on exit. Mirrors decorated-window-jni's
// `kFullscreenButtonsKey`.
static const char kTaoFullscreenButtonsKey = 5;
// Tracks whether the invisible NSToolbar (for macOS 26 large corner radius)
// was installed before a fullscreen transition started. We have to remove it
// on willEnter to avoid AppKit's "white band" animation glitch and reinstall
// it on didEnter / didExit.
static const char kTaoHadToolbarKey = 6;
// RTL flag for traffic-light positioning. When YES, applyButtonConstraints
// anchors the buttons to titlebarContainer.rightAnchor (mirrored layout).
static const char kTaoButtonsRtlKey = 7;
// Compose-side menu-bar offset (in points) — pushed down from Kotlin via
// nativeSetMenuBarOffset and read back by updateFullScreenButtonsPosition so
// the replacement traffic-light container follows the animated title bar.
static const char kTaoMenuBarOffsetKey = 8;
// Holds the NSEvent local monitor + NSMenu tracking observers installed by
// installMenuBarMonitor, keyed on the NSWindow.
static const char kTaoMenuBarMonitorKey = 9;
// Last raw offset reported by the menu bar monitor — used to debounce
// notifyMenuBarOffsetChanged so we only fire on actual transitions.
static const char kTaoMenuBarLastRawOffsetKey = 10;
// newFullscreenControls preference. When YES and the window is in
// fullscreen, the FS observer installs a menu-bar monitor; the title bar
// (and traffic-lights) animate down with the auto-hidden menu bar.
static const char kTaoNewFullscreenControlsKey = 11;
// NSView pointer (boxed in NSNumber) cached on the NSWindow — captured at
// install time so the menu-bar monitor block can route the JNI callback
// using the same opaque key Kotlin used to subscribe.
static const char kTaoNsViewPtrKey = 12;

// Same metrics as decorated-window-jni's applyConstraints — keeps the
// traffic-lights at the same offsets Apple's own apps use.
static const float kMinHeightForFullSize = 28.0f;
static const float kDefaultButtonOffset  = 23.0f;
static const float kToolbarExtraInset    = 6.0f;
static const float kMaxButtonLeftMargin  = 40.0f / 2.0f;

// Forward declarations — definitions live further down the file but the FS
// observer @implementation needs to call into them.
static void removeButtonConstraints(NSWindow *window);
static void applyButtonConstraints(NSWindow *window, float titleBarHeight);
static void installFullScreenButtons(NSWindow *window, float titleBarHeight);
static void removeFullScreenButtons(NSWindow *window);
static void updateFullScreenButtonsPosition(NSWindow *window);
static void installMenuBarMonitor(NSView *view);
static void removeMenuBarMonitor(NSWindow *window);

// ── JVM caching for native → Java callbacks ──────────────────────────────
// Mirrors the corresponding block in decorated-window-jni's JniMacTitleBar.m.
// The menu-bar monitor fires from the AppKit main run loop and needs to call
// `NativeMetalBridge.onMenuBarOffsetChanged(long, float)` on the JVM side.
// We cache the JavaVM, the bridge class (as a global ref), and the static
// method ID — all once, gated by sCallbacksEnabled so a shutdown hook can
// silence callbacks before the JVM tears down.

static JavaVM *sMetalJVM = NULL;
static jclass sMetalBridgeClass = NULL;       // global ref
static jmethodID sMetalOnOffsetChanged = NULL;
static atomic_bool sMetalCallbacksEnabled = ATOMIC_VAR_INIT(false);
static atomic_bool sMetalShutdownInProgress = ATOMIC_VAR_INIT(false);

static void ensureMetalJVMCached(JNIEnv *env) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        (*env)->GetJavaVM(env, &sMetalJVM);
        jclass local = (*env)->FindClass(env,
            "io/github/kdroidfilter/nucleus/window/tao/NativeMetalBridge");
        if (local) {
            sMetalBridgeClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            sMetalOnOffsetChanged = (*env)->GetStaticMethodID(
                env, sMetalBridgeClass, "onMenuBarOffsetChanged", "(JF)V");
            atomic_store(&sMetalCallbacksEnabled, true);
        }
    });
}

// Calls NativeMetalBridge.onMenuBarOffsetChanged(nsViewPtr, offset).
// MUST be invoked from the macOS main thread. Attaches the main thread to
// the JVM as a daemon on first call; never detaches (the main thread lives
// the whole app lifetime). Guarded by sMetalCallbacksEnabled so a shutdown
// hook can silence callbacks before JVM teardown.
static void notifyMenuBarOffsetChanged(jlong nsViewPtr, float offset) {
    if (!atomic_load(&sMetalCallbacksEnabled)) return;
    if (!sMetalJVM || !sMetalBridgeClass || !sMetalOnOffsetChanged) return;

    JNIEnv *env = NULL;
    jint status = (*sMetalJVM)->GetEnv(sMetalJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sMetalJVM)->AttachCurrentThreadAsDaemon(sMetalJVM, (void **)&env, NULL) != JNI_OK) {
            atomic_store(&sMetalCallbacksEnabled, false);
            return;
        }
    } else if (status != JNI_OK) {
        return;
    }
    if (!env) return;
    if (!atomic_load(&sMetalCallbacksEnabled)) return;

    (*env)->CallStaticVoidMethod(env, sMetalBridgeClass, sMetalOnOffsetChanged,
                                 nsViewPtr, (jfloat)offset);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static void reinstallToolbarIfNeeded(NSWindow *window) {
    NSNumber *had = objc_getAssociatedObject(window, &kTaoHadToolbarKey);
    if (![had boolValue] || window.toolbar != nil) return;
    NSToolbar *t = [[NSToolbar alloc] initWithIdentifier:@"NucleusTaoToolbar"];
    t.showsBaselineSeparator = NO;
    window.toolbar = t;
}

// ── Layer handle struct retained on the heap ─────────────────────────────

typedef struct {
    CAMetalLayer *layer;
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    NSView *view;
    // 0 = normal; 1 = inside an AppKit fullscreen transition. Render path
    // skips nextDrawable while non-zero so we don't block on a paused
    // swapchain (Apple holds drawables for the duration of the animation).
    atomic_int in_transition;
    // 0 = window is in normal mode; 1 = window is in macOS fullscreen.
    // Read by the Kotlin layer (via nativeIsFullscreen) so it can hide its
    // custom Compose title bar — AppKit auto-shows its own native one.
    atomic_int is_fullscreen;
} NucleusTaoMetalAttachment;

#define HANDLE_OF(ptr) ((NucleusTaoMetalAttachment *)(uintptr_t)(ptr))

// ── Replacement traffic-light buttons for fullscreen ────────────────────
//
// Ported from decorated-window-jni's NucleusButtonsView + installFullScreenButtons.
// In fullscreen, AppKit's auto-hiding native title bar would either disappear
// the buttons or, on non-notch screens, overlap our Compose title bar. JNI's
// solution (matching JBR's AWTButtonsView) is to:
//   1. Hide the AppKit titlebar container while in fullscreen,
//   2. Install a custom NSView in the contentView containing 3 NSButtons
//      created via [NSWindow standardWindowButton:forStyleMask:] — they look
//      identical to the real traffic-lights and accept the standard actions.

@interface NucleusTaoButtonsView : NSView {
    BOOL _dispatching;
}
@end

@implementation NucleusTaoButtonsView
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
- (void)mouseEntered:(NSEvent *)event {
    if (_dispatching) return;
    _dispatching = YES;
    [super mouseEntered:event];
    for (NSView *btn in self.subviews) [btn mouseEntered:event];
    _dispatching = NO;
}
- (void)mouseExited:(NSEvent *)event {
    if (_dispatching) return;
    _dispatching = YES;
    [super mouseExited:event];
    for (NSView *btn in self.subviews) [btn mouseExited:event];
    _dispatching = NO;
}
@end

static void computeButtonMetrics(float titleBarHeight,
                                 float *outBtnWidth, float *outBtnHeight,
                                 float *outOffset) {
    float shrinkFactor = fminf(titleBarHeight / kMinHeightForFullSize, 1.0f);
    *outBtnWidth  = fminf(titleBarHeight * 0.5f, kMinHeightForFullSize * 0.5f);
    *outBtnHeight = (*outBtnWidth) * (14.0f / 12.0f) - 2.0f;
    *outOffset    = shrinkFactor * kDefaultButtonOffset;
}

static void installFullScreenButtons(NSWindow *window, float titleBarHeight) {
    if (objc_getAssociatedObject(window, &kTaoFullscreenButtonsKey)) return;
    if ([window standardWindowButton:NSWindowCloseButton] == nil) return;

    float btnWidth, btnHeight, offset;
    computeButtonMetrics(titleBarHeight, &btnWidth, &btnHeight, &offset);

    NSNumber *rtlNum = objc_getAssociatedObject(window, &kTaoButtonsRtlKey);
    BOOL rtl = rtlNum != nil && rtlNum.boolValue;

    // newFullscreenControls — when the system menu bar slides in, the title
    // bar (and these replacement buttons) follow it down by `menuBarOffset`.
    NSNumber *menuOffsetNum = objc_getAssociatedObject(window, &kTaoMenuBarOffsetKey);
    float menuBarOffset = menuOffsetNum != nil ? menuOffsetNum.floatValue : 0.0f;

    NucleusTaoButtonsView *container = [[NucleusTaoButtonsView alloc] init];
    NSView *parent = window.contentView;
    CGFloat y = parent.frame.size.height - titleBarHeight - menuBarOffset;
    float margin = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin);
    float containerWidth = margin + 2.0f * offset + btnWidth;
    // RTL: anchor the container to the right edge of contentView and let it
    // follow horizontal resizes via NSViewMinXMargin. LTR: anchor to left.
    CGFloat containerX = rtl ? (parent.frame.size.width - containerWidth) : 0.0f;
    [container setFrame:NSMakeRect(containerX, y, containerWidth, titleBarHeight)];
    container.autoresizingMask = rtl
        ? (NSViewMinXMargin | NSViewMinYMargin)
        : NSViewMinYMargin;

    NSUInteger masks = [window styleMask];
    NSArray<NSNumber *> *types = @[
        @(NSWindowCloseButton), @(NSWindowMiniaturizeButton), @(NSWindowZoomButton)
    ];
    SEL actions[] = {
        @selector(performClose:),
        @selector(performMiniaturize:),
        @selector(toggleFullScreen:),
    };

    for (NSUInteger idx = 0; idx < 3; idx++) {
        NSButton *btn = [NSWindow standardWindowButton:[types[idx] unsignedIntegerValue]
                                          forStyleMask:masks];
        // RTL: close (idx 0) sits at the right edge of the container,
        // miniaturise + zoom step inward leftwards. LTR keeps the original
        // left-anchored layout.
        CGFloat centerX = rtl
            ? (containerWidth - margin - idx * offset)
            : (margin + idx * offset);
        CGFloat centerY = titleBarHeight / 2.0f;
        [btn setFrame:NSMakeRect(centerX - btnWidth / 2.0f,
                                 centerY - btnHeight / 2.0f,
                                 btnWidth, btnHeight)];
        [btn setTarget:window];
        [btn setAction:actions[idx]];
        [container addSubview:btn];
    }

    [parent addSubview:container];
    objc_setAssociatedObject(window, &kTaoFullscreenButtonsKey, container,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

static void removeFullScreenButtons(NSWindow *window) {
    NucleusTaoButtonsView *container =
        objc_getAssociatedObject(window, &kTaoFullscreenButtonsKey);
    if (container == nil) return;
    [container removeFromSuperview];
    objc_setAssociatedObject(window, &kTaoFullscreenButtonsKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// Repositions the existing replacement-buttons container according to the
// stored title-bar height + menu-bar offset. Called every time Compose
// pushes a new offset (animateDpAsState frame) so the traffic-lights stay
// pixel-aligned with the Compose-drawn title bar.
//
// Mirrors `decorated-window-jni`'s `updateFullScreenButtonsPosition`.
static void updateFullScreenButtonsPosition(NSWindow *window) {
    NucleusTaoButtonsView *container =
        objc_getAssociatedObject(window, &kTaoFullscreenButtonsKey);
    if (container == nil) return;
    NSView *parent = window.contentView;
    if (parent == nil) return;

    NSNumber *storedHeight = objc_getAssociatedObject(window, &kTaoTitleBarHeightKey);
    float titleBarHeight = storedHeight != nil ? storedHeight.floatValue : kMinHeightForFullSize;

    float btnWidth, btnHeight, offset;
    computeButtonMetrics(titleBarHeight, &btnWidth, &btnHeight, &offset);

    NSNumber *menuOffsetNum = objc_getAssociatedObject(window, &kTaoMenuBarOffsetKey);
    float menuBarOffset = menuOffsetNum != nil ? menuOffsetNum.floatValue : 0.0f;

    NSNumber *rtlNum = objc_getAssociatedObject(window, &kTaoButtonsRtlKey);
    BOOL rtl = rtlNum != nil && rtlNum.boolValue;

    float margin = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin);
    float containerWidth = margin + 2.0f * offset + btnWidth;
    CGFloat y = parent.frame.size.height - titleBarHeight - menuBarOffset;
    CGFloat containerX = rtl ? (parent.frame.size.width - containerWidth) : 0.0f;
    [container setFrame:NSMakeRect(containerX, y, containerWidth, titleBarHeight)];

    NSArray<NSView *> *buttons = container.subviews;
    for (NSUInteger idx = 0; idx < buttons.count && idx < 3; idx++) {
        NSView *btn = buttons[idx];
        CGFloat centerX = rtl
            ? (containerWidth - margin - idx * offset)
            : (margin + idx * offset);
        CGFloat centerY = titleBarHeight / 2.0f;
        [btn setFrame:NSMakeRect(centerX - btnWidth / 2.0f,
                                 centerY - btnHeight / 2.0f,
                                 btnWidth, btnHeight)];
    }
}

// ── Menu bar event monitor ──────────────────────────────────────────────
//
// In macOS fullscreen on non-notch screens the system menu bar auto-hides;
// it slides back in when the cursor reaches the top of the screen, or when
// the user presses Control+F2 to keyboard-focus it. We need to react to
// both so the Compose title bar can animate down with the menu bar.
//
// (1) An NSEvent local monitor catches mouse-driven show/hide via the
//     mouse-move/down/up/drag/enter/exit masks.
// (2) NSMenuDidBeginTracking / DidEndTracking notifications catch
//     keyboard-driven show/hide (independent of mouse).
//
// All handlers run on the AppKit main thread, so AppKit reads are safe.
// Each transition is debounced against `kTaoMenuBarLastRawOffsetKey` so we
// only fire `notifyMenuBarOffsetChanged` on actual changes.
//
// Mirrors `decorated-window-jni`'s `installMenuBarMonitor` /
// `removeMenuBarMonitor`.
static void installMenuBarMonitor(NSView *view) {
    NSWindow *window = view.window;
    if (window == nil) return;
    removeMenuBarMonitor(window);

    // Cache the NSView pointer on the window so the JNI callback can route
    // by the same opaque key Kotlin used to subscribe to the StateFlow.
    jlong nsViewPtr = (jlong)(uintptr_t)(__bridge void *)view;
    objc_setAssociatedObject(window, &kTaoNsViewPtrKey, @(nsViewPtr),
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    __weak NSWindow *weakWindow = window;

    void (^checkMenuBar)(void) = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSWindow *w = weakWindow;
        if (w == nil) return;
        if (!(w.styleMask & NSWindowStyleMaskFullScreen)) return;

        float offset = 0.0f;
        NSScreen *screen = w.screen;
        BOOL hasNotch = NO;
        if (@available(macOS 12.0, *)) {
            hasNotch = screen != nil && screen.safeAreaInsets.top > 0;
        }
        if (!hasNotch && [NSMenu menuBarVisible]) {
            NSMenu *mainMenu = NSApp.mainMenu;
            if (mainMenu != nil) offset = (float)mainMenu.menuBarHeight;
        }

        NSNumber *lastRaw = objc_getAssociatedObject(w, &kTaoMenuBarLastRawOffsetKey);
        float lastOffset = lastRaw != nil ? lastRaw.floatValue : -1.0f;
        if (offset != lastOffset) {
            objc_setAssociatedObject(w, &kTaoMenuBarLastRawOffsetKey, @(offset),
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            NSNumber *boxed = objc_getAssociatedObject(w, &kTaoNsViewPtrKey);
            if (boxed != nil) {
                notifyMenuBarOffsetChanged(boxed.longLongValue, offset);
            }
        }
    };

    // (1) Mouse event monitor.
    id eventMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:
        (NSEventMaskMouseMoved | NSEventMaskLeftMouseDown |
         NSEventMaskLeftMouseUp | NSEventMaskLeftMouseDragged |
         NSEventMaskMouseEntered | NSEventMaskMouseExited)
        handler:^NSEvent *(NSEvent *event) {
            checkMenuBar();
            return event;
        }];

    // (2) + (3) Keyboard-driven menu tracking.
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    id beginObserver = [nc addObserverForName:NSMenuDidBeginTrackingNotification
                                       object:nil
                                        queue:NSOperationQueue.mainQueue
                                   usingBlock:^(NSNotification *note) {
        checkMenuBar();
    }];
    id endObserver = [nc addObserverForName:NSMenuDidEndTrackingNotification
                                     object:nil
                                      queue:NSOperationQueue.mainQueue
                                 usingBlock:^(NSNotification *note) {
        checkMenuBar();
    }];

    NSDictionary *monitors = @{
        @"event": eventMonitor,
        @"beginTracking": beginObserver,
        @"endTracking": endObserver,
    };
    objc_setAssociatedObject(window, &kTaoMenuBarMonitorKey, monitors,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    // Fire one initial check so the offset is published immediately —
    // important on notch screens where it stays 0 forever otherwise.
    checkMenuBar();
}

static void removeMenuBarMonitor(NSWindow *window) {
    NSDictionary *monitors = objc_getAssociatedObject(window, &kTaoMenuBarMonitorKey);
    if (monitors != nil) {
        id eventMonitor = monitors[@"event"];
        if (eventMonitor != nil) [NSEvent removeMonitor:eventMonitor];
        NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
        id begin = monitors[@"beginTracking"];
        if (begin != nil) [nc removeObserver:begin];
        id end = monitors[@"endTracking"];
        if (end != nil) [nc removeObserver:end];
    }
    objc_setAssociatedObject(window, &kTaoMenuBarMonitorKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(window, &kTaoMenuBarLastRawOffsetKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    // Drop the Compose-side offset too so a stale value can't linger when
    // the monitor is re-installed later (e.g. newFullscreenControls toggle).
    objc_setAssociatedObject(window, &kTaoMenuBarOffsetKey, nil,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// ── Fullscreen transition observer ───────────────────────────────────────
//
// AppKit's animated `toggleFullScreen:` reorders the contentView hierarchy
// during the transition: it temporarily moves the view into a new fullscreen
// NSWindow, then moves it back. During that move, our CAMetalLayer can lose
// its host and the next drawable acquisition crashes. The observer re-asserts
// the layer attachment on `DidEnter` / `DidExit`, plus the `framebufferOnly`
// / `backgroundColor` invariants in case AppKit reset them.

@interface NucleusTaoFSObserver : NSObject
- (instancetype)initWithView:(NSView *)view;
@end

@implementation NucleusTaoFSObserver {
    NSView *_view; // weak
}

- (instancetype)initWithView:(NSView *)view {
    if ((self = [super init])) {
        _view = view;
        NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
        [nc addObserver:self selector:@selector(willEnterFS:)
                   name:NSWindowWillEnterFullScreenNotification object:view.window];
        [nc addObserver:self selector:@selector(didEnterFS:)
                   name:NSWindowDidEnterFullScreenNotification object:view.window];
        [nc addObserver:self selector:@selector(willExitFS:)
                   name:NSWindowWillExitFullScreenNotification object:view.window];
        [nc addObserver:self selector:@selector(didExitFS:)
                   name:NSWindowDidExitFullScreenNotification object:view.window];
    }
    return self;
}

- (void)dealloc {
    [NSNotificationCenter.defaultCenter removeObserver:self];
}

- (void)reattach {
    NSValue *boxed = objc_getAssociatedObject(_view.window, &kTaoAttachmentKey);
    if (boxed == nil) return;
    NucleusTaoMetalAttachment *att = (NucleusTaoMetalAttachment *) boxed.pointerValue;
    if (att == NULL || att->layer == nil) return;
    // Re-assert the layer wiring; cheap if AppKit already restored it.
    // Same order rule as in setup: layer first, then wantsLayer.
    _view.layer = att->layer;
    _view.wantsLayer = YES;
    att->layer.frame = _view.bounds;
    att->layer.drawableSize = CGSizeMake(_view.bounds.size.width  * att->layer.contentsScale,
                                         _view.bounds.size.height * att->layer.contentsScale);
}

- (NucleusTaoMetalAttachment *)attachment {
    NSValue *boxed = objc_getAssociatedObject(_view.window, &kTaoAttachmentKey);
    if (boxed == nil) return NULL;
    return (NucleusTaoMetalAttachment *) boxed.pointerValue;
}

- (void)setTransition:(int)v {
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL) atomic_store(&att->in_transition, v);
}

- (void)willEnterFS:(NSNotification *)n {
    NTLOG("FS willEnter — restore default chrome + remove constraints + drop toolbar");
    [self setTransition:1];
    NSWindow *w = _view.window;
    if (w == nil) return;
    // Drop any in-flight menu bar offset so the monitor (re)installed in
    // didEnterFS starts from a known baseline.
    removeMenuBarMonitor(w);
    removeButtonConstraints(w);
    // Restore the standard chrome so AppKit's fullscreen animation can run.
    w.titlebarAppearsTransparent = NO;
    w.titleVisibility = NSWindowTitleVisible;
    w.movableByWindowBackground = NO;
    // Drop the invisible toolbar to avoid AppKit's white-band glitch.
    if (w.toolbar != nil) {
        objc_setAssociatedObject(w, &kTaoHadToolbarKey, @YES,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        w.toolbar = nil;
    }
    // Anchor the drawable top-left so AppKit's snapshot-stretch animation
    // doesn't enlarge our 36 dp title bar visually. The unfilled area picks
    // up the layer / window background.
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL && att->layer != nil) {
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        att->layer.contentsGravity = kCAGravityTopLeft;
        [CATransaction commit];
    }
}
- (void)willExitFS:(NSNotification *)n {
    NTLOG("FS willExit");
    [self setTransition:1];
    NSWindow *w = _view.window;
    if (w == nil) return;
    // Tear down the menu bar monitor before the exit animation so AppKit
    // can transition its native chrome without our monitor racing it.
    removeMenuBarMonitor(w);
    // Same gravity trick as willEnterFS: pin the drawable top-left so the
    // shrink-animation doesn't crop the content visually.
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL && att->layer != nil) {
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        att->layer.contentsGravity = kCAGravityTopLeft;
        [CATransaction commit];
    }
    // Tear down replacement buttons + un-hide the AppKit titlebar container so
    // AppKit can drive the exit animation against its standard chrome.
    removeFullScreenButtons(w);
    NSView *btn = [w standardWindowButton:NSWindowCloseButton];
    NSView *tbc = btn ? btn.superview.superview : nil;
    if (tbc != nil && tbc.hidden) tbc.hidden = NO;
    // Hide the standard buttons during the exit animation so they don't
    // appear at the wrong (default) position before our constraints kick in.
    [[w standardWindowButton:NSWindowCloseButton] setHidden:YES];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:YES];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:YES];
    w.titlebarAppearsTransparent = YES;
    w.titleVisibility = NSWindowTitleHidden;
}

- (void)didEnterFS:(NSNotification *)n {
    NTLOG("FS didEnter — reattach + install fullscreen buttons + hide titlebar");
    [self reattach];
    [self setTransition:0];
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL) {
        atomic_store(&att->is_fullscreen, 1);
        if (att->layer != nil) {
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            att->layer.contentsGravity = kCAGravityResize;
            [CATransaction commit];
        }
    }
    NSWindow *w = _view.window;
    if (w == nil) return;
    // Install replacement traffic-light buttons inside the contentView so
    // they remain visible when AppKit auto-hides the native title bar (and
    // they don't disappear with our custom Compose title bar in fullscreen).
    NSNumber *h = objc_getAssociatedObject(w, &kTaoTitleBarHeightKey);
    float height = h ? [h floatValue] : kMinHeightForFullSize;
    installFullScreenButtons(w, height);
    // Reinstall the invisible toolbar so the macOS 26 large-corner-radius
    // treatment carries over into fullscreen.
    reinstallToolbarIfNeeded(w);
    // Hide the AppKit titlebar container to prevent it from intercepting
    // clicks meant for our Compose content (the contentView spans the full
    // window in fullscreen due to FullSizeContentView).
    NSView *btn = [w standardWindowButton:NSWindowCloseButton];
    NSView *tbc = btn ? btn.superview.superview : nil;
    if (tbc != nil) tbc.hidden = YES;
    // newFullscreenControls — install the menu bar monitor so the title bar
    // and traffic-lights animate down as the system menu bar slides in.
    NSNumber *newCtrls = objc_getAssociatedObject(w, &kTaoNewFullscreenControlsKey);
    if (newCtrls != nil && newCtrls.boolValue) {
        installMenuBarMonitor(_view);
    }
}

- (void)didExitFS:(NSNotification *)n {
    NTLOG("FS didExit — reattach + restore chrome + reapply constraints");
    [self reattach];
    [self setTransition:0];
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL) {
        atomic_store(&att->is_fullscreen, 0);
        if (att->layer != nil) {
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            att->layer.contentsGravity = kCAGravityResize;
            [CATransaction commit];
        }
    }
    NSWindow *w = _view.window;
    if (w == nil) return;
    // Re-show the standard buttons (hidden in willExitFS).
    [[w standardWindowButton:NSWindowCloseButton] setHidden:NO];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:NO];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:NO];
    // Reinstall the invisible toolbar (corner radius) and reapply the
    // button-centering constraints for our custom title bar height.
    reinstallToolbarIfNeeded(w);
    NSNumber *h = objc_getAssociatedObject(w, &kTaoTitleBarHeightKey);
    if (h != nil) applyButtonConstraints(w, [h floatValue]);
}

@end

// ── Java POJO accessors (cached on first use) ────────────────────────────

static jclass     gFrameClass        = NULL;
static jmethodID  gFrameConstructor  = NULL;

static void ensureFrameClassLoaded(JNIEnv *env) {
    if (gFrameClass != NULL) return;
    jclass local = (*env)->FindClass(env, "io/github/kdroidfilter/nucleus/window/tao/render/MetalFrame");
    if (local == NULL) return;
    gFrameClass = (jclass) (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    // ctor (long drawablePtr, long texturePtr, int widthPx, int heightPx, float scale)
    gFrameConstructor = (*env)->GetMethodID(env, gFrameClass, "<init>", "(JJIIF)V");
}

// ── JNI entry points ─────────────────────────────────────────────────────
// Symbol naming follows io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeAttach(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {

    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    NTLOG("nativeAttach view=%p", view);
    if (view == nil) return 0;

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (device == nil) return 0;

    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.device = device;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = YES;
    layer.contentsScale = view.window.backingScaleFactor > 0
        ? view.window.backingScaleFactor
        : [NSScreen mainScreen].backingScaleFactor;

    dispatch_block_t setup = ^{
        // Apple docs (NSView.layer): a custom layer must be assigned BEFORE
        // wantsLayer is set to YES, otherwise AppKit creates its default
        // backing layer first and may revert to it during animated
        // transitions (e.g. toggleFullScreen:).
        view.layer = layer;
        view.wantsLayer = YES;
        layer.frame = view.bounds;
    };
    if ([NSThread isMainThread]) setup();
    else                          dispatch_sync(dispatch_get_main_queue(), setup);

    NucleusTaoMetalAttachment *att = (NucleusTaoMetalAttachment *)
        calloc(1, sizeof(NucleusTaoMetalAttachment));
    att->layer  = layer;       // ARC retains via the strong field
    att->device = device;
    att->queue  = [device newCommandQueue];
    att->view   = view;

    // Install fullscreen observer so the CAMetalLayer wiring survives an
    // AppKit toggleFullScreen: transition. We hang the attachment pointer
    // (boxed in NSValue) on the window so the observer can reach it.
    dispatch_block_t installObserver = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        NSValue *boxed = [NSValue valueWithPointer:att];
        objc_setAssociatedObject(win, &kTaoAttachmentKey, boxed,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        if (objc_getAssociatedObject(win, &kTaoFSObserverKey) == nil) {
            NucleusTaoFSObserver *observer = [[NucleusTaoFSObserver alloc] initWithView:view];
            objc_setAssociatedObject(win, &kTaoFSObserverKey, observer,
                                     OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        }
    };
    if ([NSThread isMainThread]) installObserver();
    else                          dispatch_sync(dispatch_get_main_queue(), installObserver);

    NTLOG("nativeAttach done att=%p layer=%p device=%p", att, att->layer, att->device);
    return (jlong)(uintptr_t)att;
}

/* Companion to nativeAttach for overlay surfaces (popup NSPanels, in-window
 * overlay subviews, …): same Metal pipeline (begin/present/resize/detach
 * are interchangeable with the regular handle), but the underlying
 * CAMetalLayer is created with `opaque = NO` so a Compose scene rendered
 * into it can leave alpha-zero regions where the surface beneath shows
 * through. We also skip the fullscreen observer install — overlays are
 * children of a host NSView whose own observer already manages the FS
 * dance. */
JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeAttachOverlay(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return 0;

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (device == nil) return 0;

    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.device = device;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = YES;
    // Crucial: opaque=NO so an alpha-cleared Compose render lets the host
    // (panel content view background, sibling subviews, etc.) bleed
    // through wherever the scene didn't paint.
    layer.opaque = NO;
    layer.contentsScale = view.window.backingScaleFactor > 0
        ? view.window.backingScaleFactor
        : [NSScreen mainScreen].backingScaleFactor;

    dispatch_block_t setup = ^{
        // Layer-hosted views (`view.layer = layer`) require the developer
        // to manually keep `layer.position` in sync with the host view's
        // `frame.origin` — AppKit only auto-syncs that for layer-BACKED
        // views (where AppKit creates the backing layer itself). When the
        // overlay is positioned at e.g. frame.origin (16, 16), the hosted
        // CAMetalLayer keeps position (0, 0), so the rendered Compose
        // surface ends up offset by exactly the host view's frame.origin
        // from where the AppKit-managed sibling subview renders.
        //
        // Switch to layer-BACKED + sublayer: AppKit owns the host view's
        // backing layer and keeps it correctly placed; our CAMetalLayer
        // is a sublayer with `frame = view.bounds`, autoresizes via
        // `layoutManager` so it follows live-resize without explicit
        // re-positioning.
        view.wantsLayer = YES;
        layer.frame = view.bounds;
        layer.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
        [view.layer addSublayer:layer];
    };
    if ([NSThread isMainThread]) setup();
    else                          dispatch_sync(dispatch_get_main_queue(), setup);

    NucleusTaoMetalAttachment *att = (NucleusTaoMetalAttachment *)
        calloc(1, sizeof(NucleusTaoMetalAttachment));
    att->layer  = layer;
    att->device = device;
    att->queue  = [device newCommandQueue];
    att->view   = view;
    return (jlong)(uintptr_t)att;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeConfigureChrome(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        // Make the window's content area cover the entire frame (including the
        // title bar region) and make the title bar transparent. The standard
        // traffic-light buttons remain visible because they are part of the
        // NSWindow chrome itself, not of the title bar background.
        win.styleMask |= NSWindowStyleMaskFullSizeContentView;
        win.titlebarAppearsTransparent = YES;
        win.titleVisibility = NSWindowTitleHidden;
        win.movableByWindowBackground = NO;
        // Disable native window dragging entirely. Without this, AppKit's
        // NSTitlebarContainerView intercepts mouse-downs in the title-bar
        // area (the top ~28pt of the window — where our Compose-driven
        // title bar lives) and starts a window drag before Compose ever
        // sees the events. Mirrors `decorated-window-jni`'s
        // `JniMacTitleBar.m` which does the same. We re-enable
        // `movable` momentarily inside `nucleus_tao_start_window_drag`
        // because `performWindowDragWithEvent:` requires `isMovable=YES`
        // on macOS < 26.
        [win setMovable:NO];
        // Neutral light background shown through the CAMetalLayer until the
        // first frame is presented. Matches the typical Compose Desktop /
        // AWT default so apps that don't paint their own background look
        // identical to the JNI/JBR backends.
        win.backgroundColor = [NSColor whiteColor];
        // Native fullscreen is allowed; the green traffic-light button
        // triggers AppKit's animated toggleFullScreen:. A NucleusTaoFSObserver
        // installed in nativeAttach below re-asserts the CAMetalLayer wiring
        // after the transition completes (AppKit reparents the contentView,
        // which can otherwise leave the layer detached and crash the next
        // nextDrawable call).
        NSWindowCollectionBehavior cb = win.collectionBehavior;
        cb |= NSWindowCollectionBehaviorFullScreenPrimary;
        win.collectionBehavior = cb;
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/**
 * Deactivates the constraint set previously installed by applyButtonConstraints
 * and restores autoresizing on the AppKit private title-bar views, so AppKit
 * regains control of layout for fullscreen animations and other transitions.
 *
 * Ported from `decorated-window-jni`'s `removeExistingConstraints` — without
 * this, AppKit's animated `toggleFullScreen:` deadlocks because our manual
 * NSLayoutConstraints conflict with AppKit's animation constraints on the
 * same `NSTitlebarContainerView`.
 */
static void removeButtonConstraints(NSWindow *window) {
    NSArray *existing = objc_getAssociatedObject(window, &kTaoConstraintsKey);
    if (existing != nil) {
        [NSLayoutConstraint deactivateConstraints:existing];
        objc_setAssociatedObject(window, &kTaoConstraintsKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }

    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    if (closeBtn == nil) return;
    NSView *titlebar          = closeBtn.superview;
    NSView *titlebarContainer = titlebar ? titlebar.superview : nil;

    if (titlebarContainer != nil) {
        titlebarContainer.translatesAutoresizingMaskIntoConstraints = YES;
    }
    if (titlebar != nil) {
        titlebar.translatesAutoresizingMaskIntoConstraints = YES;
    }
    closeBtn.translatesAutoresizingMaskIntoConstraints = YES;
    NSView *miniBtn = [window standardWindowButton:NSWindowMiniaturizeButton];
    NSView *zoomBtn = [window standardWindowButton:NSWindowZoomButton];
    if (miniBtn != nil) miniBtn.translatesAutoresizingMaskIntoConstraints = YES;
    if (zoomBtn != nil) zoomBtn.translatesAutoresizingMaskIntoConstraints = YES;
}

/**
 * Repositions the standard NSWindow buttons (close / miniaturise / zoom) so
 * they are vertically centred inside a custom-height title bar drawn by
 * Compose. Without this the buttons stay at AppKit's default ~7pt-from-top
 * offset, looking misaligned with a 32-44pt custom bar.
 *
 * Ported from `decorated-window-jni`'s `applyConstraints`, simplified for
 * our case (no RTL, no fullscreen-button replacement, single drag view).
 */
static void applyButtonConstraints(NSWindow *window, float titleBarHeight) {
    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    NSView *miniBtn  = [window standardWindowButton:NSWindowMiniaturizeButton];
    NSView *zoomBtn  = [window standardWindowButton:NSWindowZoomButton];
    if (closeBtn == nil || miniBtn == nil || zoomBtn == nil) return;

    NSView *titlebar          = closeBtn.superview;
    NSView *titlebarContainer = titlebar ? titlebar.superview : nil;
    NSView *themeFrame        = titlebarContainer ? titlebarContainer.superview : nil;
    if (themeFrame == nil) return;

    // Tear down our previously-applied constraint set + restore autoresizing.
    removeButtonConstraints(window);

    // Remember the height so the FS observer can re-apply on didExitFullScreen.
    objc_setAssociatedObject(window, &kTaoTitleBarHeightKey, @(titleBarHeight),
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    NSMutableArray *constraints = [NSMutableArray array];

    titlebarContainer.translatesAutoresizingMaskIntoConstraints = NO;
    [constraints addObjectsFromArray:@[
        [titlebarContainer.leftAnchor   constraintEqualToAnchor:themeFrame.leftAnchor],
        [titlebarContainer.widthAnchor  constraintEqualToAnchor:themeFrame.widthAnchor],
        [titlebarContainer.topAnchor    constraintEqualToAnchor:themeFrame.topAnchor],
        [titlebarContainer.heightAnchor constraintEqualToConstant:titleBarHeight],
    ]];

    titlebar.translatesAutoresizingMaskIntoConstraints = NO;
    [constraints addObjectsFromArray:@[
        [titlebar.leftAnchor   constraintEqualToAnchor:titlebarContainer.leftAnchor],
        [titlebar.rightAnchor  constraintEqualToAnchor:titlebarContainer.rightAnchor],
        [titlebar.topAnchor    constraintEqualToAnchor:titlebarContainer.topAnchor],
        [titlebar.bottomAnchor constraintEqualToAnchor:titlebarContainer.bottomAnchor],
    ]];

    float shrinkFactor = fminf(titleBarHeight / kMinHeightForFullSize, 1.0f);
    float offset       = shrinkFactor * kDefaultButtonOffset;
    float extraInset   = window.toolbar ? kToolbarExtraInset : 0.0f;
    float margin       = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin) + extraInset;

    NSNumber *rtlNum = objc_getAssociatedObject(window, &kTaoButtonsRtlKey);
    BOOL rtl = rtlNum != nil && rtlNum.boolValue;

    NSArray *buttons = @[closeBtn, miniBtn, zoomBtn];
    [buttons enumerateObjectsUsingBlock:^(NSView *btn, NSUInteger idx, BOOL *stop) {
        btn.translatesAutoresizingMaskIntoConstraints = NO;
        float c = margin + idx * offset;
        [constraints addObjectsFromArray:@[
            [btn.widthAnchor   constraintLessThanOrEqualToAnchor:titlebarContainer.heightAnchor
                                                      multiplier:0.5],
            [btn.heightAnchor  constraintEqualToAnchor:btn.widthAnchor
                                            multiplier:14.0 / 12.0
                                              constant:-2.0],
            [btn.centerYAnchor constraintEqualToAnchor:titlebarContainer.topAnchor
                                              constant:titleBarHeight / 2.0f],
        ]];
        if (rtl) {
            // Mirror to the right edge: close button at the rightmost position,
            // miniaturise + zoom step inward by `offset` per index.
            [constraints addObject:
                [btn.centerXAnchor constraintEqualToAnchor:titlebarContainer.rightAnchor
                                                 constant:-c]];
        } else {
            [constraints addObject:
                [btn.centerXAnchor constraintEqualToAnchor:titlebarContainer.leftAnchor
                                                 constant:c]];
        }
    }];

    [NSLayoutConstraint activateConstraints:constraints];
    objc_setAssociatedObject(window, &kTaoConstraintsKey, constraints,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeApplyButtonLayout(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jfloat titleBarHeight) {
    NTLOG("nativeApplyButtonLayout h=%.1f", titleBarHeight);
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        applyButtonConstraints(win, titleBarHeight);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/**
 * Flips the AppKit traffic-light buttons (close / miniaturise / zoom) to the
 * right edge of the title bar when [rtl] is YES, or back to the default left
 * edge when NO. Re-applies [applyButtonConstraints] with the previously stored
 * [kTaoTitleBarHeightKey] so the new anchor side takes effect immediately.
 *
 * Mirrors `decorated-window-jni`'s `JniMacTitleBarBridge.nativeSetRTL`.
 */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeSetButtonLayoutRtl(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean isRtl) {
    NTLOG("nativeSetButtonLayoutRtl rtl=%d", (int)isRtl);
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        objc_setAssociatedObject(win, &kTaoButtonsRtlKey,
                                 @((BOOL)(isRtl == JNI_TRUE)),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        NSNumber *h = objc_getAssociatedObject(win, &kTaoTitleBarHeightKey);
        if (h != nil) {
            applyButtonConstraints(win, h.floatValue);
        }
        // If we're currently in fullscreen, re-install the replacement
        // traffic-light container so the new RTL flag takes effect on the
        // overlay buttons too (otherwise they'd stay anchored on the side
        // matched at the moment fullscreen was entered).
        if (([win styleMask] & NSWindowStyleMaskFullScreen) != 0 && h != nil) {
            removeFullScreenButtons(win);
            installFullScreenButtons(win, h.floatValue);
        }
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/**
 * Returns YES on macOS 26 (Tahoe) or later, where attaching an invisible
 * NSToolbar yields the new ~26pt window corner radius and adopts other
 * modern chrome behaviours. Cheap query — caches the result in a static.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeIsMacOSTahoeOrLater(
        JNIEnv *env, jclass clazz) {
    static jboolean cached = (jboolean) -1;
    if (cached != (jboolean) -1) return cached;
    NSOperatingSystemVersion v = (NSOperatingSystemVersion){26, 0, 0};
    cached = [[NSProcessInfo processInfo] isOperatingSystemAtLeastVersion:v] ? JNI_TRUE : JNI_FALSE;
    return cached;
}

/**
 * Applies (or removes) the macOS 26+ "large corner radius" treatment by
 * attaching an invisible NSToolbar on the parent NSWindow. The toolbar acts
 * as a marker that opts the window into the new modern corner radius and
 * Liquid-Glass-friendly chrome path. No-op on macOS < 26.
 */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeApplyLargeCornerRadius(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        // Pre-Tahoe systems do not draw the new corners even with a toolbar
        // attached; bail out so we don't pay the toolbar overhead for nothing.
        NSOperatingSystemVersion v = (NSOperatingSystemVersion){26, 0, 0};
        if (![[NSProcessInfo processInfo] isOperatingSystemAtLeastVersion:v]) return;

        if (enabled == JNI_TRUE) {
            if (win.toolbar == nil) {
                NSToolbar *t = [[NSToolbar alloc] initWithIdentifier:@"NucleusTaoToolbar"];
                t.showsBaselineSeparator = NO;
                // Default visibility = YES; combined with titlebarAppearsTransparent
                // the toolbar is invisible but still triggers the 26pt corners.
                win.toolbar = t;
            }
        } else if (win.toolbar != nil) {
            win.toolbar = nil;
        }
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeDetach(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    NSWindow *win = att->view.window;
    att->layer  = nil;
    att->device = nil;
    att->queue  = nil;
    att->view   = nil;
    if (win != nil) {
        removeMenuBarMonitor(win);
        objc_setAssociatedObject(win, &kTaoFSObserverKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(win, &kTaoAttachmentKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(win, &kTaoNewFullscreenControlsKey, nil,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(win, &kTaoNsViewPtrKey, nil,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    free(att);
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeDevicePtr(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return 0;
    return (jlong)(uintptr_t) (__bridge void *) HANDLE_OF(handle)->device;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeQueuePtr(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return 0;
    return (jlong)(uintptr_t) (__bridge void *) HANDLE_OF(handle)->queue;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeResize(
        JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale) {
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    dispatch_block_t resize = ^{
        // Suppress implicit animations on `frame` / `drawableSize` /
        // `contentsScale` — during a live-resize the layer would
        // otherwise visibly chase the actual size.
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        att->layer.contentsScale = scale;
        att->layer.drawableSize  = CGSizeMake(widthPx, heightPx);
        att->layer.frame         = att->view.bounds;
        [CATransaction commit];
    };
    if ([NSThread isMainThread]) resize();
    else                          dispatch_sync(dispatch_get_main_queue(), resize);
}

JNIEXPORT jobject JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeBeginFrame(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return NULL;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);

    id<CAMetalDrawable> drawable = [att->layer nextDrawable];
    if (drawable == nil) {
        return NULL;
    }

    // Retain so the JVM can hold the pointer until present(); released there.
    void *retained = (__bridge_retained void *) drawable;

    id<MTLTexture> texture = drawable.texture;
    CGSize size = att->layer.drawableSize;
    CGFloat scale = att->layer.contentsScale;

    ensureFrameClassLoaded(env);
    if (gFrameClass == NULL || gFrameConstructor == NULL) {
        // Drop the retain to avoid leaking the drawable when the JVM mapping fails.
        CFBridgingRelease(retained);
        return NULL;
    }

    return (*env)->NewObject(env, gFrameClass, gFrameConstructor,
        (jlong)(uintptr_t) retained,
        (jlong)(uintptr_t) (__bridge void *) texture,
        (jint) size.width,
        (jint) size.height,
        (jfloat) scale);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeIsInTransition(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    return atomic_load(&att->in_transition) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativePresent(
        JNIEnv *env, jclass clazz, jlong handle, jlong drawablePtr) {
    if (handle == 0 || drawablePtr == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);

    // Move ownership back so ARC releases after the present block finishes.
    id<CAMetalDrawable> drawable = (__bridge_transfer id<CAMetalDrawable>)
        (void *)(uintptr_t) drawablePtr;

    id<MTLCommandBuffer> commandBuffer = [att->queue commandBuffer];
    [commandBuffer presentDrawable:drawable];
    [commandBuffer commit];
}

/* Toggles CAMetalLayer.presentsWithTransaction. With the flag ON, the
 * layer defers its surface swap so it can be flushed atomically inside
 * the enclosing CATransaction together with AppKit mutations made by
 * `nativePresentWithInterop`'s callback. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeSetPresentsWithTransaction(
        JNIEnv *env, jclass clazz, jlong handle, jboolean enabled) {
    (void)env; (void)clazz;
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    BOOL flag = enabled == JNI_TRUE;
    dispatch_block_t apply = ^{
        att->layer.presentsWithTransaction = flag;
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/* Atomic present-with-transaction path. See the Kotlin doc on
 * NativeMetalBridge.nativePresentWithInterop for the full sequence.
 *
 * Requires `presentsWithTransaction = YES` on the layer (set by
 * nativeSetPresentsWithTransaction) so [drawable present] joins our
 * outer CATransaction instead of being scheduled out of band. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativePresentWithInterop(
        JNIEnv *env, jclass clazz, jlong handle, jlong drawablePtr, jobject interopActions) {
    if (handle == 0 || drawablePtr == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);

    id<CAMetalDrawable> drawable = (__bridge_transfer id<CAMetalDrawable>)
        (void *)(uintptr_t) drawablePtr;

    [CATransaction begin];

    id<MTLCommandBuffer> commandBuffer = [att->queue commandBuffer];
    [commandBuffer commit];
    // Block until the GPU has scheduled our work — required before an
    // explicit [drawable present] under presentsWithTransaction = YES.
    [commandBuffer waitUntilScheduled];
    [drawable present];

    if (interopActions != NULL) {
        // Cache Runnable.run() once. Module-local statics are fine: the
        // method ID for java.lang.Runnable.run is stable for the JVM lifetime.
        static jclass sRunnableClass = NULL;
        static jmethodID sRunMethod = NULL;
        if (sRunMethod == NULL) {
            jclass local = (*env)->FindClass(env, "java/lang/Runnable");
            if (local != NULL) {
                sRunnableClass = (*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
                if (sRunnableClass != NULL) {
                    sRunMethod = (*env)->GetMethodID(env, sRunnableClass, "run", "()V");
                }
            }
        }
        if (sRunMethod != NULL) {
            (*env)->CallVoidMethod(env, interopActions, sRunMethod);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
            }
        }
    }

    [CATransaction commit];
}

// ── newFullscreenControls JNI bridge ─────────────────────────────────────
//
// Mirrors `decorated-window-jni`'s JniMacTitleBarBridge entries so the Tao
// backend exposes the same `Modifier.newFullscreenControls()` behaviour
// (title bar slides down with the auto-shown system menu bar in fullscreen).
// All entry points hop to the AppKit main queue before touching AppKit.

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeSetNewFullscreenControls(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    if (nsViewPtr == 0) return;
    ensureMetalJVMCached(env);
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    BOOL flag = (enabled == JNI_TRUE);
    dispatch_block_t apply = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil) return;
        NSWindow *w = view.window;
        if (w == nil) return;
        objc_setAssociatedObject(w, &kTaoNewFullscreenControlsKey, @(flag),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        if (w.styleMask & NSWindowStyleMaskFullScreen) {
            if (flag) installMenuBarMonitor(view);
            else      removeMenuBarMonitor(w);
        }
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeInstallMenuBarMonitor(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return;
    ensureMetalJVMCached(env);
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    dispatch_block_t apply = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil || view.window == nil) return;
        installMenuBarMonitor(view);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeRemoveMenuBarMonitor(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    dispatch_block_t apply = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil) return;
        NSWindow *w = view.window;
        if (w == nil) return;
        removeMenuBarMonitor(w);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeSetMenuBarOffset(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jfloat offsetPt) {
    if (nsViewPtr == 0) return;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    dispatch_block_t apply = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil) return;
        NSWindow *w = view.window;
        if (w == nil) return;
        objc_setAssociatedObject(w, &kTaoMenuBarOffsetKey, @(offsetPt),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        updateFullScreenButtonsPosition(w);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeUpdateFullScreenButtons(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    dispatch_block_t apply = ^{
        if (atomic_load(&sMetalShutdownInProgress)) return;
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil) return;
        NSWindow *w = view.window;
        if (w == nil) return;
        updateFullScreenButtonsPosition(w);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeMetalBridge_nativeShutdown(
        JNIEnv *env, jclass clazz) {
    // Stop further dispatch_async work and silence JNI callbacks. Cleanup of
    // remaining monitors is best-effort: a JVM shutdown hook may run very
    // late and AppKit could already be gone, so we just async-fire the
    // removal and rely on the atomic flags to prevent any callback racing.
    atomic_store(&sMetalShutdownInProgress, true);
    atomic_store(&sMetalCallbacksEnabled, false);
    dispatch_async(dispatch_get_main_queue(), ^{
        for (NSWindow *w in NSApp.windows) {
            if (objc_getAssociatedObject(w, &kTaoMenuBarMonitorKey) != nil) {
                removeMenuBarMonitor(w);
            }
        }
    });
}

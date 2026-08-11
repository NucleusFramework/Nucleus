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
#import <CoreVideo/CoreVideo.h>
#import <Carbon/Carbon.h>
#import <mach/mach_time.h>
#import <objc/runtime.h>
#import <stdatomic.h>
#import <stdio.h>
#include <stdint.h>
#include <math.h>
#import <jni.h>

// Diagnostic logging for the title-bar / fullscreen / menu-bar paths. Off by
// default (no-op) so production apps stay silent; opt in by launching with
// NUCLEUS_TAO_LOG=1 (any non-empty, non-"0" value). Routed through NSLog so the
// output lands in the unified log — visible in Console.app or `log stream`
// even when the app runs from a .app bundle with no attached terminal (which
// is why plain stdout/println "logs never arrive" for testers on release
// builds). All call sites are infrequent (FS transitions, attach, layout), so
// NSLog cost is irrelevant.
static BOOL nucleusTaoLogEnabled(void) {
    static BOOL enabled = NO;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        const char *v = getenv("NUCLEUS_TAO_LOG");
        enabled = v != NULL && v[0] != '\0' && !(v[0] == '0' && v[1] == '\0');
    });
    return enabled;
}
#define NTLOG(fmt, ...) do { \
    if (nucleusTaoLogEnabled()) NSLog(@"[nucleus-tao] " fmt, ##__VA_ARGS__); \
} while (0)

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
// Holds the title-bar passthrough view that forwards every mouse event to
// the contentView. Without it, AppKit's NSTitlebarContainerView swallows
// mouseMoved / mouseEntered / mouseExited in the title-bar zone, leaving
// Compose's hover detection (and thus TooltipBox, hover styling, cursor
// changes) dead on anything drawn over the title bar. Mirrors
// `decorated-window-jni`'s NucleusDragView.
static const char kTaoPassthroughViewKey = 13;
// Last themed fallback background ARGB. Applied to NSWindow and CAMetalLayer so
// AppKit fullscreen/title-bar animations never reveal the default white window.
static const char kTaoBackgroundArgbKey = 14;
// NSNumber(int): single window transparency mode.
//   0 OFF     — opaque themed background
//   1 REGIONS — window stays opaque (System Settings-style materials get a
//               wallpaper-only backdrop); CAMetalLayer is non-opaque so
//               in-window material panes show through alpha-0 Compose pixels
//   2 FULL    — full-window per-pixel transparency (#416): window non-opaque,
//               desktop composites through alpha-0 pixels
// FULL is creation-time and is never demoted by glass REGIONS/OFF requests.
static const char kTaoTransparentModeKey = 16;
// Holds the NucleusTaoZoomButtonResponder that flips `isMovable` back on
// while the cursor hovers the zoom button, so AppKit builds the full window
// tiling hover menu ("Move & Resize" / "Fill & Arrange") despite the window
// being kept non-movable at rest (issue #497).
static const char kTaoZoomResponderKey = 19;

#define TAO_TRANSPARENCY_OFF     0
#define TAO_TRANSPARENCY_REGIONS 1
#define TAO_TRANSPARENCY_FULL    2

// Same metrics as decorated-window-jni's applyConstraints — keeps the
// traffic-lights at the same offsets Apple's own apps use.
static const float kMinHeightForFullSize = 28.0f;
static const float kDefaultButtonOffset  = 23.0f;
static const float kToolbarExtraInset    = 6.0f;
static const float kMaxButtonLeftMargin  = 40.0f / 2.0f;
// Pre-Tahoe native traffic-lights: 20 pt between button centers, and the
// standard buttons keep their natural 14x16 pt frame (12 pt visible circle).
static const float kLegacyButtonOffset   = 20.0f;

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

// Forward declarations — definitions live further down the file but the FS
// observer @implementation needs to call into them.
static void removeButtonConstraints(NSWindow *window);
static void applyButtonConstraints(NSWindow *window, float titleBarHeight);
static void installFullScreenButtons(NSWindow *window, float titleBarHeight);
static void removeFullScreenButtons(NSWindow *window);
static void updateFullScreenButtonsPosition(NSWindow *window);
static void installMenuBarMonitor(NSView *view);
static void removeMenuBarMonitor(NSWindow *window);
static void neutralizeToolbarFullScreenWindows(void);
static void ensureTaoMenuBarEventHandler(void);

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
static jmethodID sMetalOnFullscreenPrepare = NULL;
static atomic_bool sMetalCallbacksEnabled = ATOMIC_VAR_INIT(false);
static atomic_bool sMetalShutdownInProgress = ATOMIC_VAR_INIT(false);

static void ensureMetalJVMCached(JNIEnv *env) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        (*env)->GetJavaVM(env, &sMetalJVM);
        jclass local = (*env)->FindClass(env,
            "dev/nucleusframework/window/tao/ffi/NativeMetalBridge");
        if (local) {
            sMetalBridgeClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            sMetalOnOffsetChanged = (*env)->GetStaticMethodID(
                env, sMetalBridgeClass, "onMenuBarOffsetChanged", "(JF)V");
            sMetalOnFullscreenPrepare = (*env)->GetStaticMethodID(
                env, sMetalBridgeClass, "onFullscreenPrepare", "(JII)V");
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

// Calls NativeMetalBridge.onFullscreenPrepare(nsViewPtr, widthPx, heightPx)
// and BLOCKS until the JVM side has presented that frame. Invoked from
// `windowWillEnterFullScreen:` on the macOS main thread — the same thread the
// Tao loop and the Compose host run on, so the host's blocking render runs
// re-entrantly on this stack. That is the point: AppKit snapshots the window
// as soon as this notification finishes, so the frame has to exist by then
// (#327). No-op if the JVM side never registered a prepare for this view.
static void notifyFullscreenPrepare(jlong nsViewPtr, jint widthPx, jint heightPx) {
    if (!atomic_load(&sMetalCallbacksEnabled)) return;
    if (!sMetalJVM || !sMetalBridgeClass || !sMetalOnFullscreenPrepare) return;

    JNIEnv *env = NULL;
    jint status = (*sMetalJVM)->GetEnv(sMetalJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sMetalJVM)->AttachCurrentThreadAsDaemon(sMetalJVM, (void **)&env, NULL) != JNI_OK) {
            return;
        }
    } else if (status != JNI_OK) {
        return;
    }
    if (!env) return;

    (*env)->CallStaticVoidMethod(env, sMetalBridgeClass, sMetalOnFullscreenPrepare,
                                 nsViewPtr, widthPx, heightPx);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
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
    // VSync pacing — the AWT/skiko MetalVSyncer pattern. A CVDisplayLink runs
    // continuously and, on each refresh, signals `vsyncSem` IF a waiter armed
    // `vsyncWaiting`. `nativeVSyncWait` arms the flag then blocks on the
    // semaphore, so it returns on the *next* refresh after the call (not a
    // queued/stale one). The render loop (Kotlin FrameDispatcher) calls
    // waitForVSync after presenting to pace itself to the display.
    //
    // Threading discipline: `displayLink`/`vsyncSem` are created/destroyed ONLY
    // on the Tao main thread (nativeStartDisplayLink / nativeStopDisplayLink /
    // nativeDetach). The CoreVideo callback runs on its own thread and touches
    // ONLY the atomics + signals the semaphore — never `displayLink`
    // (CVDisplayLinkStop is synchronous, so no callback is in flight once it
    // returns). `nativeVSyncWait` may be called from any (background) thread.
    CVDisplayLinkRef displayLink;     // NULL until started — main-thread lifecycle only
    dispatch_semaphore_t vsyncSem;    // signaled by the CV callback when armed
    atomic_bool vsyncWaiting;         // a waiter is parked in nativeVSyncWait
    // Mach host-time of the display refresh this frame targets, captured by the
    // display-link callback and consumed by nativePresent to pace the present
    // (presentDrawable:atTime:). It's the predicted *next* vsync, so normally
    // accurate; if render + present overruns a refresh it ends up in the past
    // and Metal presents immediately (graceful — not a hard 1:1 vsync).
    _Atomic uint64_t next_present_host_time;

    // View size in points just before entering fullscreen. AppKit restores
    // this frame on the way out, so it is the *final* layout for the exit
    // transition — the size we must have rendered before AppKit snapshots
    // (see willExitFS / #327). Zero until the first fullscreen entry.
    double windowed_w_points;
    double windowed_h_points;
    // Previous NSWindow frame origin (bottom-left screen coords), used by
    // nativeResize to pick a live-resize contentsGravity that anchors the
    // stale drawable to the window's *fixed* corner instead of letting
    // Core Animation rubber-band it around the layer centre. NAN until the
    // first resize.
    double prev_origin_x;
    double prev_origin_y;

    // YES for attachments created by nativeAttachOverlay (popup NSPanels,
    // in-window NativeView overlay subviews). Overlays never install the
    // window-level associated objects (fullscreen observer, attachment key —
    // see the observer-skip note on nativeAttachOverlay); nativeDetach reads
    // this so an overlay dispose can't tear down state owned by the host
    // window's primary attachment.
    BOOL isOverlay;
} NucleusTaoMetalAttachment;

#define HANDLE_OF(ptr) ((NucleusTaoMetalAttachment *)(uintptr_t)(ptr))

// Converts a CVTimeStamp mach host-time to the CACurrentMediaTime() seconds base
// expected by -[MTLCommandBuffer presentDrawable:atTime:].
static double hostTimeToSeconds(uint64_t hostTime) {
    static mach_timebase_info_data_t tb;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ mach_timebase_info(&tb); });
    if (hostTime == 0 || tb.denom == 0) return 0.0;
    return (double) hostTime * (double) tb.numer / (double) tb.denom / 1.0e9;
}

static CVReturn taoDisplayLinkCallback(
        CVDisplayLinkRef displayLink,
        const CVTimeStamp *now,
        const CVTimeStamp *outputTime,
        CVOptionFlags flagsIn,
        CVOptionFlags *flagsOut,
        void *context) {
    (void) displayLink; (void) now; (void) flagsIn; (void) flagsOut;
    NucleusTaoMetalAttachment *att = (NucleusTaoMetalAttachment *) context;
    if (att == NULL) return kCVReturnSuccess;
    if (outputTime != NULL && (outputTime->flags & kCVTimeStampHostTimeValid)) {
        atomic_store(&att->next_present_host_time, outputTime->hostTime);
    }
    // Wake a parked waitForVSync, if any. Disarm first so we signal at most once
    // per arm (waitForVSync re-arms on its next call).
    if (atomic_exchange(&att->vsyncWaiting, false)) {
        if (att->vsyncSem != NULL) dispatch_semaphore_signal(att->vsyncSem);
    }
    return kCVReturnSuccess;
}

static NSColor *colorFromArgb(jint argb) {
    uint32_t v = (uint32_t)argb;
    CGFloat a = (CGFloat)((v >> 24) & 0xFF) / 255.0;
    CGFloat r = (CGFloat)((v >> 16) & 0xFF) / 255.0;
    CGFloat g = (CGFloat)((v >> 8)  & 0xFF) / 255.0;
    CGFloat b = (CGFloat)(v & 0xFF) / 255.0;
    return [NSColor colorWithSRGBRed:r green:g blue:b alpha:a];
}

static NucleusTaoMetalAttachment *attachmentForWindow(NSWindow *window) {
    NSValue *boxed = objc_getAssociatedObject(window, &kTaoAttachmentKey);
    return boxed != nil ? (NucleusTaoMetalAttachment *)boxed.pointerValue : NULL;
}

static void applyWindowBackgroundColor(NSWindow *window, NSView *view, jint argb) {
    if (window == nil) return;
    NSColor *color = colorFromArgb(argb);
    window.backgroundColor = color;

    CGColorRef cgColor = color.CGColor;
    NSView *contentView = window.contentView;
    if (contentView != nil && contentView.layer != nil) {
        contentView.layer.backgroundColor = cgColor;
    }
    if (view != nil && view.layer != nil) {
        view.layer.backgroundColor = cgColor;
    }

    NucleusTaoMetalAttachment *att = attachmentForWindow(window);
    if (att != NULL && att->layer != nil) {
        att->layer.backgroundColor = cgColor;
    }
}

static int taoTransparencyMode(NSWindow *window) {
    NSNumber *mode = objc_getAssociatedObject(window, &kTaoTransparentModeKey);
    return mode != nil ? mode.intValue : TAO_TRANSPARENCY_OFF;
}

// Clears the fallback layer backgrounds only, leaving the NSWindow color
// untouched — used in regions mode where the window itself stays opaque.
static void clearLayerBackgrounds(NSWindow *window, NSView *view) {
    CGColorRef clear = [NSColor clearColor].CGColor;
    NSView *contentView = window.contentView;
    if (contentView != nil && contentView.layer != nil) {
        contentView.layer.backgroundColor = clear;
    }
    if (view != nil && view.layer != nil) {
        view.layer.backgroundColor = clear;
    }
    NucleusTaoMetalAttachment *att = attachmentForWindow(window);
    if (att != NULL && att->layer != nil) {
        att->layer.backgroundColor = clear;
    }
}

static void applyStoredWindowBackground(NSWindow *window, NSView *view) {
    if (window == nil) return;
    // Glass regions active: never repaint the stored opaque color over the
    // fallback layers — this path re-runs during fullscreen transitions and
    // toolbar toggles, which would otherwise cover the native materials.
    int mode = taoTransparencyMode(window);
    NSNumber *stored = objc_getAssociatedObject(window, &kTaoBackgroundArgbKey);
    jint argb = stored != nil ? stored.intValue : (jint)0xFFFFFFFF;
    if (mode == TAO_TRANSPARENCY_FULL) {
        // #416: non-opaque window; ARGB (incl. alpha) on NSWindow; layers clear
        // so Compose clear alpha is what the compositor sees.
        window.opaque = NO;
        window.backgroundColor = colorFromArgb(argb);
        clearLayerBackgrounds(window, view);
        NucleusTaoMetalAttachment *att = attachmentForWindow(window);
        if (att != NULL && att->layer != nil) {
            att->layer.opaque = NO;
        }
        return;
    }
    if (mode == TAO_TRANSPARENCY_REGIONS) {
        // Window keeps the themed color (it IS opaque — that is what makes
        // WindowServer feed the materials the wallpaper-only backdrop), but
        // the layers above the material views must stay clear.
        window.backgroundColor = colorFromArgb(argb);
        clearLayerBackgrounds(window, view);
        return;
    }
    applyWindowBackgroundColor(window, view, argb);
}

// Single transparency-mode applier: OFF / REGIONS / FULL.
// FULL is sticky for the window lifetime of a DecoratedWindow(transparent=true):
// glass REGIONS/OFF must not demote it (would re-opaque the top-level).
static void taoApplyWindowTransparencyMode(NSWindow *win, NSView *view, int mode) {
    if (win == nil) return;
    int current = taoTransparencyMode(win);
    if (current == TAO_TRANSPARENCY_FULL && mode != TAO_TRANSPARENCY_FULL) {
        // Keep FULL. Glass still wants clear layers for material panes.
        if (mode == TAO_TRANSPARENCY_REGIONS) {
            NucleusTaoMetalAttachment *att = attachmentForWindow(win);
            if (att != NULL && att->layer != nil) {
                att->layer.opaque = NO;
            }
            clearLayerBackgrounds(win, view);
        }
        return;
    }

    objc_setAssociatedObject(win, &kTaoTransparentModeKey,
                             @(mode),
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    // REGIONS needs an opaque window for System Settings materials.
    // FULL needs a non-opaque window so the desktop composites through.
    win.opaque = (mode == TAO_TRANSPARENCY_FULL) ? NO : YES;

    // The CAMetalLayer is only ever made non-opaque, never opaque again: a
    // live layer keeps already-issued opaque drawables, and the first frames
    // after flipping back render alpha-0 pixels as solid black. Leaving it
    // non-opaque costs nothing once the window itself is opaque again.
    NucleusTaoMetalAttachment *att = attachmentForWindow(win);
    if (att != NULL && att->layer != nil && mode != TAO_TRANSPARENCY_OFF) {
        att->layer.opaque = NO;
    }
    applyStoredWindowBackground(win, view);
}

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

// Neutralizes the NSToolbarFullScreenWindow overlay AppKit creates lazily in
// fullscreen (despite its name it hosts the re-parented native title bar, so
// it exists even without an NSToolbar). When the menu bar is revealed on
// pre-Tahoe non-notch screens, this overlay slides in over our Compose title
// bar, drawing a drop shadow / titlebar separator hairline and stray titlebar
// chrome (issue #310 B2/B4; separator fix mirrors Firefox bug 1700211).
// Mirrors decorated-window-jni's function of the same name.
static void neutralizeToolbarFullScreenWindows(void) {
    Class cls = NSClassFromString(@"NSToolbarFullScreenWindow");
    if (cls == nil) return;
    for (NSWindow *win in NSApp.windows) {
        if (![win isKindOfClass:cls]) continue;
        NTLOG("neutralize NSToolbarFullScreenWindow=%p shadow=%d",
              win, (int)win.hasShadow);
        if (!win.ignoresMouseEvents) win.ignoresMouseEvents = YES;
        if (!win.contentView.hidden) win.contentView.hidden = YES;
        if (win.hasShadow) win.hasShadow = NO;
        if (@available(macOS 11.0, *)) {
            if (win.titlebarSeparatorStyle != NSTitlebarSeparatorStyleNone) {
                win.titlebarSeparatorStyle = NSTitlebarSeparatorStyleNone;
            }
        }
    }
}

@interface NucleusTaoButtonsView : NSView {
    BOOL _mouseInside;
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
                      NSTrackingActiveAlways |
                      NSTrackingInVisibleRect)
               owner:self
            userInfo:nil];
    [self addTrackingArea:ta];
}
// The buttons only repaint their glyphs when explicitly invalidated: they
// query _mouseInGroup: at draw time. Synthesizing mouseEntered:/mouseExited:
// on the buttons instead leaves _NSThemeWidget in an inconsistent hover
// state on pre-Tahoe macOS — only the zoom button repainted, and its glyph
// never cleared on exit (issue #310 B1).
- (void)setButtonsNeedDisplay {
    for (NSView *btn in self.subviews) [btn setNeedsDisplay:YES];
}
- (void)mouseEntered:(NSEvent *)event {
    (void)event;
    _mouseInside = YES;
    [self setButtonsNeedDisplay];
}
- (void)mouseExited:(NSEvent *)event {
    (void)event;
    _mouseInside = NO;
    [self setButtonsNeedDisplay];
}
// Private AppKit hook: standard window buttons ask their superview whether
// the traffic-light group is hovered before drawing the glyphs. Without it,
// pre-Tahoe systems never show the symbols on hover (mirrors JBR's
// AWTButtonsView).
- (BOOL)_mouseInGroup:(NSButton *)button {
    (void)button;
    return _mouseInside;
}
@end

static void computeButtonMetrics(float titleBarHeight,
                                 float *outBtnWidth, float *outBtnHeight,
                                 float *outOffset) {
    float shrinkFactor = fminf(titleBarHeight / kMinHeightForFullSize, 1.0f);
    // Traffic-lights size adapts to the title-bar height, capped at the
    // native 14 pt width. Mirrors decorated-window-jni's implementation.
    *outBtnWidth  = fminf(titleBarHeight * 0.5f, kMinHeightForFullSize * 0.5f);
    if (isTahoeOrLater()) {
        // JBR's correction: AppKit adds a constant 2 pt to the resulting frame
        // height, so width * 14/12 - 2 keeps the circle perfectly round.
        *outBtnHeight = (*outBtnWidth) * (14.0f / 12.0f) - 2.0f;
    } else {
        // Keep the pre-Tahoe native 14x16 pt aspect so the glyphs aren't
        // squashed on older macOS.
        *outBtnHeight = (*outBtnWidth) * (16.0f / 14.0f);
    }
    *outOffset    = shrinkFactor * defaultButtonOffset();
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

// ── Menu bar reveal tracking (Carbon) ───────────────────────────────────
//
// In macOS fullscreen on non-notch screens the system menu bar auto-hides;
// it slides back in when the cursor reaches the top of the screen, or on
// Control+F2. Tracking this with NSEvent monitors is too late: AppKit
// reveals/hides the bar after an internal delay even while the cursor is
// stationary, so an event-driven check only notices on the NEXT mouse move.
//
// Chromium's FullscreenMenubarTracker listens to Carbon application events
// on kEventClassMenu instead:
//   - the undocumented kind 2004 streams the live reveal fraction (param
//     'rvlf', CGFloat 0..1) on every tick of the system animation,
//   - kEventMenuBarShown / kEventMenuBarHidden give the terminal states
//     (2004's own 0.0/1.0 are unreliable with multiple fullscreen spaces).
// This drives the Compose title bar in lockstep with the menu bar — no
// polling, no added latency.

static const UInt32 kTaoMenuBarRevealEventKind = 2004;

static EventHandlerRef sTaoMenuBarEventHandler = NULL;

// Applies a menu bar reveal fraction to every monitored fullscreen window:
// stores the raw offset, moves the native traffic-light container, and
// notifies the Kotlin side. Runs on the main thread (Carbon dispatch).
static void applyMenuBarFraction(CGFloat fraction) {
    if (atomic_load(&sMetalShutdownInProgress)) return;

    NSMenu *mainMenu = NSApp.mainMenu;
    float menuBarHeight = mainMenu != nil ? (float)mainMenu.menuBarHeight : 0.0f;
    NTLOG("menuBar fraction=%.3f menuBarHeight=%.1f", (double)fraction, menuBarHeight);
    if (menuBarHeight <= 0.0f) return;

    BOOL anyChanged = NO;
    for (NSWindow *w in NSApp.windows) {
        if (objc_getAssociatedObject(w, &kTaoMenuBarMonitorKey) == nil) continue;
        if (!(w.styleMask & NSWindowStyleMaskFullScreen)) continue;
        if (!w.isOnActiveSpace) continue;

        NSScreen *screen = w.screen;
        BOOL hasNotch = NO;
        if (@available(macOS 12.0, *)) {
            hasNotch = screen != nil && screen.safeAreaInsets.top > 0;
        }
        // Notch screens keep the menu bar in the notch area permanently —
        // the title bar never needs to move.
        float offset = hasNotch ? 0.0f : (float)(fraction * menuBarHeight);

        NSNumber *lastRaw = objc_getAssociatedObject(w, &kTaoMenuBarLastRawOffsetKey);
        float lastOffset = lastRaw != nil ? lastRaw.floatValue : -1.0f;
        // Chromium's guard: never *reveal* on a screen the cursor isn't on
        // (another space's menu bar can leak its events here).
        if (lastRaw != nil && offset > lastOffset && screen != nil &&
            !NSMouseInRect(NSEvent.mouseLocation, screen.frame, NO)) {
            continue;
        }
        if (offset == lastOffset) continue;

        objc_setAssociatedObject(w, &kTaoMenuBarLastRawOffsetKey, @(offset),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        // Move the traffic-light replacements right away — no JVM round-trip
        // — so they track the menu bar pixel-for-pixel.
        objc_setAssociatedObject(w, &kTaoMenuBarOffsetKey, @(offset),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        updateFullScreenButtonsPosition(w);
        anyChanged = YES;

        NSNumber *boxed = objc_getAssociatedObject(w, &kTaoNsViewPtrKey);
        if (boxed != nil) {
            notifyMenuBarOffsetChanged(boxed.longLongValue, offset);
        }
    }
    // AppKit drives the menu bar animation from a nested run loop; flush so
    // the repositioned buttons repaint during the animation (mirrors
    // Chromium's FullscreenMenubarTracker).
    if (anyChanged) [CATransaction flush];
}

static OSStatus taoMenuBarRevealHandler(EventHandlerCallRef handler,
                                        EventRef event, void *userData) {
    (void)userData;
    UInt32 kind = GetEventKind(event);
    // Pre-Tahoe: every menu-bar transition may lazily (re)create AppKit's
    // fullscreen title-bar overlay window; keep it neutralized (#310 B2/B4).
    if (!isTahoeOrLater() && !atomic_load(&sMetalShutdownInProgress)) {
        neutralizeToolbarFullScreenWindows();
    }
    if (kind == kTaoMenuBarRevealEventKind) {
        CGFloat fraction = 0;
        GetEventParameter(event, FOUR_CHAR_CODE('rvlf'), typeCGFloat, NULL,
                          sizeof(CGFloat), NULL, &fraction);
        // With several fullscreen spaces the 2004 event can report another
        // space's settled menu bar; trust only intermediate fractions and
        // let Shown/Hidden set the terminal values (mirrors Chromium).
        if (fraction > 0.0 && fraction < 1.0) applyMenuBarFraction(fraction);
    } else if (kind == kEventMenuBarShown) {
        applyMenuBarFraction(1.0);
    } else if (kind == kEventMenuBarHidden) {
        applyMenuBarFraction(0.0);
    }
    return CallNextEventHandler(handler, event);
}

// Installs the app-wide Carbon handler once; per-window opt-in happens via
// the kTaoMenuBarMonitorKey marker consumed by applyMenuBarFraction.
static void ensureTaoMenuBarEventHandler(void) {
    if (sTaoMenuBarEventHandler != NULL) return;
    EventTypeSpec specs[3] = {
        { kEventClassMenu, kTaoMenuBarRevealEventKind },
        { kEventClassMenu, kEventMenuBarShown },
        { kEventClassMenu, kEventMenuBarHidden },
    };
    InstallApplicationEventHandler(NewEventHandlerUPP(&taoMenuBarRevealHandler),
                                   3, specs, NULL, &sTaoMenuBarEventHandler);
}

static void installMenuBarMonitor(NSView *view) {
    // Safari-style fullscreen title bar (slide down with the menu bar) is a
    // Tahoe-era behaviour; on older macOS it produces a phantom padding and
    // a seam line in the title-bar area (issue #310 B4/C).
    if (!isTahoeOrLater()) return;
    NSWindow *window = view.window;
    if (window == nil) return;

    // Cache the NSView pointer on the window so the JNI callback can route
    // by the same opaque key Kotlin used to subscribe to the StateFlow.
    jlong nsViewPtr = (jlong)(uintptr_t)(__bridge void *)view;
    objc_setAssociatedObject(window, &kTaoNsViewPtrKey, @(nsViewPtr),
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    // Opt this window into applyMenuBarFraction.
    objc_setAssociatedObject(window, &kTaoMenuBarMonitorKey, @YES,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    ensureTaoMenuBarEventHandler();

    // Seed the current state — Carbon only reports transitions.
    applyMenuBarFraction([NSMenu menuBarVisible] ? 1.0 : 0.0);
}

static void removeMenuBarMonitor(NSWindow *window) {
    // The app-wide Carbon handler stays installed (inert without monitored
    // windows); only the per-window state is dropped.
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
    applyStoredWindowBackground(_view.window, _view);
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
    // Tahoe-only: on older macOS this briefly reveals the opaque native
    // title bar sliding to the top during the transition (issue #310).
    if (isTahoeOrLater()) {
        w.titlebarAppearsTransparent = NO;
        w.titleVisibility = NSWindowTitleVisible;
    }
    w.movableByWindowBackground = NO;
    // Keep the window movable for the whole fullscreen session: the real zoom
    // button (carrying the hover responder) is hidden in fullscreen, and the
    // replacement button installed by installFullScreenButtons has none — a
    // non-movable window would build its hover menu without the tiling
    // sections again (issue #497). Restored to NO in didExitFS. Mirrors
    // decorated-window-jni's willEnterFullScreen/didExitFullScreen.
    [w setMovable:YES];
    // Drop the invisible toolbar to avoid AppKit's white-band glitch.
    if (w.toolbar != nil) {
        objc_setAssociatedObject(w, &kTaoHadToolbarKey, @YES,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        w.toolbar = nil;
    }
    // Render the final fullscreen layout NOW, then let AppKit scale it.
    //
    // AppKit resizes the window to its final size and snapshots it as soon as
    // this notification returns, then stretches that snapshot for the whole
    // ~550ms animation. Left alone, the snapshot holds the previous, smaller
    // buffer: pinned top-left it sits 1:1 in the corner of an already
    // fullscreen-sized window (frozen, then a snap at the end), and scaled it
    // blows up past its final size (measured: a 28 dp bar reaching 1.74x
    // before popping back). Neither is what AppKit's own apps do — they ramp
    // the *final* layout from compressed to 1:1 (#327).
    //
    // So: hand the JVM the size the window is about to take and block until it
    // has presented that frame, then ask for Resize gravity. The snapshot now
    // holds the fullscreen layout, AppKit scales it down into the current
    // frame and grows it to 1:1 — the native ramp. Exit needs no prepare: its
    // pre-transition buffer is already the large one (see willExitFS).
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL && att->layer != nil) {
        // Capture before the prepare below resizes us: this is the frame
        // AppKit will restore on exit, i.e. the exit transition's final layout.
        NSSize windowed = _view.bounds.size;
        att->windowed_w_points = windowed.width;
        att->windowed_h_points = windowed.height;
        NSScreen *screen = w.screen ?: [NSScreen mainScreen];
        CGFloat scale = w.backingScaleFactor > 0 ? w.backingScaleFactor : screen.backingScaleFactor;
        NSSize target = screen.frame.size;
        notifyFullscreenPrepare((jlong)(uintptr_t)(__bridge void *) _view,
                                (jint) lround(target.width * scale),
                                (jint) lround(target.height * scale));
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        att->layer.contentsGravity = kCAGravityResize;
        [CATransaction commit];
    }
    applyStoredWindowBackground(w, _view);
}
- (void)willExitFS:(NSNotification *)n {
    NTLOG("FS willExit");
    [self setTransition:1];
    NSWindow *w = _view.window;
    if (w == nil) return;
    // Tear down the menu bar monitor before the exit animation so AppKit
    // can transition its native chrome without our monitor racing it.
    removeMenuBarMonitor(w);
    // Mirror of willEnterFS: the transition snapshot has to hold the *final*
    // layout, and on the way out that is the windowed one AppKit is about to
    // restore — not the fullscreen buffer we currently hold. Measured against
    // a native AppKit baseline, exiting stretches the windowed layout up and
    // shrinks it to 1:1 (bar height 13 -> 16 -> 13); leaving the fullscreen
    // buffer in place instead compresses it and pops at the end (#327).
    NucleusTaoMetalAttachment *att = [self attachment];
    if (att != NULL && att->layer != nil) {
        if (att->windowed_w_points > 0 && att->windowed_h_points > 0) {
            CGFloat scale = w.backingScaleFactor > 0
                ? w.backingScaleFactor
                : [NSScreen mainScreen].backingScaleFactor;
            notifyFullscreenPrepare((jlong)(uintptr_t)(__bridge void *) _view,
                                    (jint) lround(att->windowed_w_points * scale),
                                    (jint) lround(att->windowed_h_points * scale));
        }
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        att->layer.contentsGravity = kCAGravityResize;
        [CATransaction commit];
    }
    applyStoredWindowBackground(w, _view);
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
    applyStoredWindowBackground(w, _view);
    // Install replacement traffic-light buttons inside the contentView so
    // they remain visible when AppKit auto-hides the native title bar (and
    // they don't disappear with our custom Compose title bar in fullscreen).
    NSNumber *h = objc_getAssociatedObject(w, &kTaoTitleBarHeightKey);
    float height = h ? [h floatValue] : kMinHeightForFullSize;
    installFullScreenButtons(w, height);
    // Intentionally NOT reinstalling the invisible NSToolbar in fullscreen.
    // The toolbar exists solely to opt the window into the macOS 26 large
    // corner radius — irrelevant on a screen-spanning window — but having
    // it attached makes AppKit allocate a tall opaque band at the top of
    // the contentView, visible as a white strip above the Compose title
    // bar. didExitFS reinstalls it via the kTaoHadToolbarKey flag set in
    // willEnterFS, so the windowed-mode chrome restores correctly.
    // Hide the AppKit titlebar container to prevent it from intercepting
    // clicks meant for our Compose content (the contentView spans the full
    // window in fullscreen due to FullSizeContentView).
    NSView *btn = [w standardWindowButton:NSWindowCloseButton];
    NSView *tbc = btn ? btn.superview.superview : nil;
    if (tbc != nil) tbc.hidden = YES;
    // Also hide the standard buttons themselves: when the menu bar is
    // revealed, AppKit can re-host the native title bar in its fullscreen
    // overlay window, bypassing the hidden container (issue #310 B2).
    // Restored in didExitFS.
    [[w standardWindowButton:NSWindowCloseButton] setHidden:YES];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:YES];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:YES];
    // newFullscreenControls — install the menu bar monitor so the title bar
    // and traffic-lights animate down as the system menu bar slides in.
    NSNumber *newCtrls = objc_getAssociatedObject(w, &kTaoNewFullscreenControlsKey);
    if (newCtrls != nil && newCtrls.boolValue) {
        installMenuBarMonitor(_view);
    }
    // Pre-Tahoe: the Safari-style title bar is disabled, but AppKit still
    // creates its fullscreen title-bar overlay lazily (often only on the
    // first menu-bar reveal). Neutralize it now, once more after AppKit's
    // lazy setup, and keep the Carbon menu-bar handler installed so every
    // later reveal re-neutralizes a re-created overlay (issue #310 B2/B4).
    if (!isTahoeOrLater()) {
        neutralizeToolbarFullScreenWindows();
        ensureTaoMenuBarEventHandler();
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            if (atomic_load(&sMetalShutdownInProgress)) return;
            neutralizeToolbarFullScreenWindows();
        });
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
    applyStoredWindowBackground(w, _view);
    // Re-show the standard buttons (hidden in willExitFS).
    [[w standardWindowButton:NSWindowCloseButton] setHidden:NO];
    [[w standardWindowButton:NSWindowMiniaturizeButton] setHidden:NO];
    [[w standardWindowButton:NSWindowZoomButton] setHidden:NO];
    // Back to the non-movable at-rest state (movable since willEnterFS). Also
    // clears the latched movable=YES when fullscreen was entered by clicking
    // the zoom button mid-hover — the button is hidden before mouseExited can
    // deliver, so the responder never restores it (issue #497).
    [w setMovable:NO];
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
    jclass local = (*env)->FindClass(env, "dev/nucleusframework/window/tao/render/MetalFrame");
    if (local == NULL) return;
    gFrameClass = (jclass) (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    // ctor (long drawablePtr, long texturePtr, int widthPx, int heightPx, float scale)
    gFrameConstructor = (*env)->GetMethodID(env, gFrameClass, "<init>", "(JJIIF)V");
}

// ── JNI entry points ─────────────────────────────────────────────────────
// Symbol naming follows dev.nucleusframework.window.tao.ffi.NativeMetalBridge

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeAttach(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    // Prime the native -> JVM callback plumbing for every window, not just the
    // ones that happen to install a menu-bar monitor: the fullscreen-transition
    // prepare (#327) fires from an AppKit notification with no JNIEnv of its
    // own, so the JavaVM has to be cached by then.
    ensureMetalJVMCached(env);


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
    att->prev_origin_x = NAN;
    att->prev_origin_y = NAN;

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
        applyStoredWindowBackground(win, view);
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeAttachOverlay(
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
    att->prev_origin_x = NAN;
    att->prev_origin_y = NAN;
    att->isOverlay = YES;
    return (jlong)(uintptr_t)att;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeConfigureChrome(
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
        // The Compose title bar draws its own edge; without this the window
        // paints a hairline under the (transparent) native title-bar zone,
        // including on the fullscreen menu-bar reveal (issue #310 B4).
        if (@available(macOS 11.0, *)) {
            win.titlebarSeparatorStyle = NSTitlebarSeparatorStyleNone;
        }
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
        // Fallback background shown through the CAMetalLayer until the first
        // frame is presented and during AppKit fullscreen/title-bar
        // animations. Keep it synced with the Compose clear color instead of
        // forcing white.
        applyStoredWindowBackground(win, view);
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

// ── Title-bar event passthrough view ────────────────────────────────────
//
// AppKit's NSTitlebarContainerView sits in front of the contentView and
// intercepts mouseMoved / mouseEntered / mouseExited in the title-bar
// zone. Even with `setMovable:NO` and `acceptsMouseMovedEvents:YES`, these
// events never reach the contentView when the cursor hovers the titlebar,
// so Compose's hover state machine (`TooltipBox`, hover styling, cursor
// changes) is dead on tabs / buttons drawn over the title bar.
//
// Mirrors `decorated-window-jni`'s `NucleusDragView`: a transparent view
// pinned over the entire titlebar that overrides every mouse handler and
// forwards to `[self.window contentView]`. Installed below the traffic-
// light buttons (NSWindowBelow positioning) so close/min/max controls
// still receive their own clicks normally. The view persists across
// constraint updates so the forwarding chain is never torn down.
@interface NucleusTaoPassthroughView : NSView
@end

@implementation NucleusTaoPassthroughView
- (BOOL)acceptsFirstMouse:(NSEvent *)event { (void)event; return YES; }
- (void)mouseDown:(NSEvent *)event         { [[self.window contentView] mouseDown:event]; }
- (void)mouseUp:(NSEvent *)event           { [[self.window contentView] mouseUp:event]; }
- (void)mouseDragged:(NSEvent *)event      { [[self.window contentView] mouseDragged:event]; }
- (void)mouseMoved:(NSEvent *)event        { [[self.window contentView] mouseMoved:event]; }
- (void)mouseEntered:(NSEvent *)event      { [[self.window contentView] mouseEntered:event]; }
- (void)mouseExited:(NSEvent *)event       { [[self.window contentView] mouseExited:event]; }
- (void)rightMouseDown:(NSEvent *)event    { [[self.window contentView] rightMouseDown:event]; }
- (void)rightMouseUp:(NSEvent *)event      { [[self.window contentView] rightMouseUp:event]; }
- (void)rightMouseDragged:(NSEvent *)event { [[self.window contentView] rightMouseDragged:event]; }
- (void)otherMouseDown:(NSEvent *)event    { [[self.window contentView] otherMouseDown:event]; }
- (void)otherMouseUp:(NSEvent *)event      { [[self.window contentView] otherMouseUp:event]; }
- (void)otherMouseDragged:(NSEvent *)event { [[self.window contentView] otherMouseDragged:event]; }
- (void)scrollWheel:(NSEvent *)event       { [[self.window contentView] scrollWheel:event]; }
@end

// Installs the passthrough view once in the title bar. Idempotent — repeat
// calls return the cached instance. Returns nil if the AppKit title-bar
// hierarchy isn't materialised yet (early-init race; constraints will be
// reapplied on the next `nativeApplyButtonLayout` once it is).
static NucleusTaoPassthroughView *ensureTaoPassthroughView(NSWindow *window) {
    NucleusTaoPassthroughView *existing =
        objc_getAssociatedObject(window, &kTaoPassthroughViewKey);
    if (existing != nil) return existing;

    NSView *closeBtn = [window standardWindowButton:NSWindowCloseButton];
    if (closeBtn == nil) return nil;
    NSView *titlebar = closeBtn.superview;
    if (titlebar == nil) return nil;

    NucleusTaoPassthroughView *view = [[NucleusTaoPassthroughView alloc] init];
    [titlebar addSubview:view positioned:NSWindowBelow relativeTo:closeBtn];
    objc_setAssociatedObject(window, &kTaoPassthroughViewKey, view,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return view;
}

// ── Zoom-button hover: window tiling menu ────────────────────────────────
//
// nativeConfigureChrome keeps the window `isMovable == NO` so AppKit's
// title-bar machinery doesn't intercept clicks meant for the Compose title
// bar. But AppKit also gates window tiling on `isMovable`: hovering the zoom
// button of a non-movable window builds a menu with only the "Full Screen"
// section — the "Move & Resize" / "Fill & Arrange" tiling items are omitted
// (issue #497). Flip movable back on while the cursor is over the zoom
// button so the hover menu is built against a movable window, and restore it
// when the cursor leaves. Ported from decorated-window-jni's
// NucleusZoomButtonResponder (which mirrors JBR's
// AWTWindowZoomButtonMouseResponder).
@interface NucleusTaoZoomButtonResponder : NSObject
@property (nonatomic, weak) NSWindow *window;
@property (nonatomic, strong) NSTrackingArea *trackingArea;
@end

@implementation NucleusTaoZoomButtonResponder

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
    (void)event;
    NSWindow *w = self.window;
    if (w && ![w isMovable]) {
        [w setMovable:YES];
    }
}

- (void)mouseExited:(NSEvent *)event {
    (void)event;
    NSWindow *w = self.window;
    if (w && objc_getAssociatedObject(w, &kTaoTitleBarHeightKey)) {
        [w setMovable:NO];
    }
}

@end

static void installTaoZoomButtonResponder(NSWindow *window) {
    if (objc_getAssociatedObject(window, &kTaoZoomResponderKey)) return;
    NucleusTaoZoomButtonResponder *responder =
        [[NucleusTaoZoomButtonResponder alloc] initWithWindow:window];
    // Don't cache a dead responder when the zoom button wasn't there yet —
    // a later call can then install a live one (mirrors
    // ensureTaoPassthroughView's not-materialised-yet handling).
    if (responder.trackingArea == nil) return;
    objc_setAssociatedObject(window, &kTaoZoomResponderKey, responder,
                             OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

// ── _adjustWindowToScreen swizzle ────────────────────────────────────────
//
// macOS routes window snapping/tiling moves through the private
// _adjustWindowToScreen, which no-ops on a non-movable window. Temporarily
// re-enable movable around the original implementation so a chosen tiling
// item (or edge snap) actually moves the window even though movable is
// normally NO. Ported from decorated-window-jni (mirrors JBR's
// AWTWindow_Normal._adjustWindowToScreen). The re-entrancy guard prevents
// crashes when the original IMP or updateFullScreenButtonsPosition triggers
// another _adjustWindowToScreen call on older macOS versions.
static IMP  sTaoOriginalAdjustWindowToScreen = NULL;
static BOOL sTaoInAdjustWindow = NO;

static void nucleus_tao_adjustWindowToScreen(id self, SEL _cmd) {
    if (sTaoInAdjustWindow) {
        // Re-entrant call — just forward to the original implementation.
        if (sTaoOriginalAdjustWindowToScreen) {
            ((void (*)(id, SEL))sTaoOriginalAdjustWindowToScreen)(self, _cmd);
        }
        return;
    }
    sTaoInAdjustWindow = YES;

    NSNumber *storedHeight = objc_getAssociatedObject(self, &kTaoTitleBarHeightKey);
    BOOL needsRestore = storedHeight != nil && ![(NSWindow *)self isMovable];
    if (needsRestore) {
        [(NSWindow *)self setMovable:YES];
    }

    if (sTaoOriginalAdjustWindowToScreen) {
        ((void (*)(id, SEL))sTaoOriginalAdjustWindowToScreen)(self, _cmd);
    }

    updateFullScreenButtonsPosition((NSWindow *)self);

    if (needsRestore) {
        [(NSWindow *)self setMovable:NO];
    }

    sTaoInAdjustWindow = NO;
}

// Called only from the main thread (applyButtonConstraints call sites), so
// no synchronization is needed beyond the idempotency check.
static void ensureTaoAdjustWindowSwizzle(NSWindow *window) {
    Class cls = object_getClass(window);
    SEL sel = NSSelectorFromString(@"_adjustWindowToScreen");
    Method method = class_getInstanceMethod(cls, sel);
    if (!method) {
        // Private AppKit method gone (future macOS): the hover menu will
        // still show the tiling items but performing one no-ops, so leave a
        // trace for diagnosis.
        NTLOG("_adjustWindowToScreen not found — tiling moves stay blocked");
        return;
    }
    // Already swizzled (this class or an ancestor we already patched).
    if (method_getImplementation(method) == (IMP)nucleus_tao_adjustWindowToScreen) return;
    // Capture exactly once: re-capturing after a third party wrapped our hook
    // would store *their* hook (which chains back to ours) as the "original"
    // and recurse on the next snap.
    if (sTaoOriginalAdjustWindowToScreen == NULL) {
        sTaoOriginalAdjustWindowToScreen = method_getImplementation(method);
    }
    method_setImplementation(method, (IMP)nucleus_tao_adjustWindowToScreen);
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

    // Window tiling despite `isMovable == NO`: the hover responder makes the
    // zoom-button menu offer the tiling sections, and the swizzle lets the
    // chosen tile actually move the window (issue #497).
    ensureTaoAdjustWindowSwizzle(window);
    installTaoZoomButtonResponder(window);

    // Re-assert the at-rest state on every layout push: a mouseExited the
    // responder never received (zoom button hidden mid-hover, key-window
    // change) would otherwise leave movable=YES latched, and AppKit would
    // start intercepting title-bar mouse-downs meant for Compose. Skipped in
    // fullscreen, where the window stays movable for the whole session (see
    // willEnterFS). Mirrors decorated-window-jni's nativeApplyTitleBar.
    if ((window.styleMask & NSWindowStyleMaskFullScreen) == 0) {
        [window setMovable:NO];
    }

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

    NucleusTaoPassthroughView *passthrough = ensureTaoPassthroughView(window);
    if (passthrough != nil) {
        passthrough.translatesAutoresizingMaskIntoConstraints = NO;
        [constraints addObjectsFromArray:@[
            [passthrough.leftAnchor   constraintEqualToAnchor:titlebarContainer.leftAnchor],
            [passthrough.rightAnchor  constraintEqualToAnchor:titlebarContainer.rightAnchor],
            [passthrough.topAnchor    constraintEqualToAnchor:titlebarContainer.topAnchor],
            [passthrough.bottomAnchor constraintEqualToAnchor:titlebarContainer.bottomAnchor],
        ]];
    }

    float shrinkFactor = fminf(titleBarHeight / kMinHeightForFullSize, 1.0f);
    float offset       = shrinkFactor * defaultButtonOffset();
    float extraInset   = window.toolbar ? kToolbarExtraInset : 0.0f;
    float margin       = fminf(titleBarHeight / 2.0f, kMaxButtonLeftMargin) + extraInset;

    NSNumber *rtlNum = objc_getAssociatedObject(window, &kTaoButtonsRtlKey);
    BOOL rtl = rtlNum != nil && rtlNum.boolValue;

    // Same aspect correction as computeButtonMetrics — see the comment there.
    CGFloat sizeRatio    = 14.0 / 12.0;
    CGFloat sizeConstant = -2.0;

    NSArray *buttons = @[closeBtn, miniBtn, zoomBtn];
    [buttons enumerateObjectsUsingBlock:^(NSView *btn, NSUInteger idx, BOOL *stop) {
        btn.translatesAutoresizingMaskIntoConstraints = NO;
        float c = margin + idx * offset;
        [constraints addObjectsFromArray:@[
            [btn.widthAnchor   constraintLessThanOrEqualToAnchor:titlebarContainer.heightAnchor
                                                      multiplier:0.5],
            [btn.heightAnchor  constraintEqualToAnchor:btn.widthAnchor
                                            multiplier:sizeRatio
                                              constant:sizeConstant],
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeApplyButtonLayout(
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetButtonLayoutRtl(
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
        if (h == nil) return;
        BOOL isFullScreen = ([win styleMask] & NSWindowStyleMaskFullScreen) != 0;
        if (isFullScreen) {
            // In fullscreen only re-install the replacement traffic-light
            // container so the new RTL flag takes effect on the overlay
            // buttons (otherwise they'd stay anchored on the side matched at
            // the moment fullscreen was entered). Never touch the manual
            // constraints here: willEnterFS removed them so AppKit's animated
            // toggleFullScreen: can run, and re-activating them mid-session
            // leaves them active during the exit animation — the exact
            // constraint conflict removeButtonConstraints exists to prevent
            // (issue #510). didExitFS re-applies them with the stored RTL
            // flag, so the windowed layout picks up the new side on exit.
            // Mirrors decorated-window-jni's nativeSetRTL.
            removeFullScreenButtons(win);
            installFullScreenButtons(win, h.floatValue);
        } else {
            applyButtonConstraints(win, h.floatValue);
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeIsMacOSTahoeOrLater(
        JNIEnv *env, jclass clazz) {
    static jboolean cached = (jboolean) -1;
    if (cached != (jboolean) -1) return cached;
    NSOperatingSystemVersion v = (NSOperatingSystemVersion){26, 0, 0};
    cached = [[NSProcessInfo processInfo] isOperatingSystemAtLeastVersion:v] ? JNI_TRUE : JNI_FALSE;
    return cached;
}

/**
 * Returns the visibleFrame size (screen minus menu bar and dock) of the
 * NSScreen currently hosting the given NSView, in physical pixels, packed
 * as `(width << 32) | (height & 0xFFFFFFFF)`. Returns 0 when the NSView is
 * not yet attached to a window or no screen is resolvable — callers must
 * fall back to their owner-window size in that case.
 *
 * Used by `TaoPopupSceneLayer` to size its inner Compose scene's layout
 * constraints. The owner window's own size would be a false upper bound
 * (a popup can legitimately extend beyond the owner up to the screen edge).
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeOwnerWorkAreaSize(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return 0;
    NSWindow *win = view.window;
    if (win == nil) return 0;
    NSScreen *screen = win.screen ?: [NSScreen mainScreen];
    if (screen == nil) return 0;
    CGFloat scale = screen.backingScaleFactor;
    NSRect frame = screen.visibleFrame;
    jlong widthPx = (jlong) llround(frame.size.width * scale);
    jlong heightPx = (jlong) llround(frame.size.height * scale);
    if (widthPx <= 0 || heightPx <= 0) return 0;
    return ((jlong)(widthPx & 0xFFFFFFFFL) << 32) | (jlong)(heightPx & 0xFFFFFFFFL);
}

/**
 * Applies (or removes) the macOS 26+ "large corner radius" treatment by
 * attaching an invisible NSToolbar on the parent NSWindow. The toolbar acts
 * as a marker that opts the window into the new modern corner radius and
 * Liquid-Glass-friendly chrome path. No-op on macOS < 26.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeApplyLargeCornerRadius(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        // Pre-Tahoe systems do not draw the new corners even with a toolbar
        // attached; bail out so we don't pay the toolbar overhead for nothing.
        if (!isTahoeOrLater()) return;

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
        applyStoredWindowBackground(win, view);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/*
 * Window-level transparency for the glass regions. The window itself stays
 * opaque — that is precisely what makes the system hand its behind-window
 * materials the System Settings-style desktop-tinted backdrop — while the
 * CAMetalLayer is cleared to alpha 0 so a material inserted below the content
 * shows through wherever Compose paints nothing. Ref-counting lives on the
 * Kotlin side (WindowTransparencyMode).
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetWindowTransparencyMode(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;
    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        // Glass regions: REGIONS on, OFF when the last region releases.
        // No-op demotion if FULL is sticky (#416).
        int current = taoTransparencyMode(win);
        if (enabled == JNI_TRUE) {
            taoApplyWindowTransparencyMode(win, view, TAO_TRANSPARENCY_REGIONS);
        } else if (current == TAO_TRANSPARENCY_REGIONS) {
            taoApplyWindowTransparencyMode(win, view, TAO_TRANSPARENCY_OFF);
        }
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetFullyTransparent(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;
    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        // #416: FULL mode on the single transparency-mode slot.
        if (enabled == JNI_TRUE) {
            taoApplyWindowTransparencyMode(win, view, TAO_TRANSPARENCY_FULL);
        } else if (taoTransparencyMode(win) == TAO_TRANSPARENCY_FULL) {
            taoApplyWindowTransparencyMode(win, view, TAO_TRANSPARENCY_OFF);
        }
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeIsWindowOpaque(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return JNI_TRUE;
    __block jboolean result = JNI_TRUE;
    dispatch_block_t read = ^{
        NSWindow *win = view.window;
        result = (win != nil && win.opaque) ? JNI_TRUE : JNI_FALSE;
    };
    if ([NSThread isMainThread]) read();
    else                          dispatch_sync(dispatch_get_main_queue(), read);
    return result;
}

// ── Glass regions: hosted NSSplitViewController (the Apple pattern) ──────
//
// Ground truth (validated empirically): a public NSVisualEffectView with
// behind-window blending composites the REAL content behind the window —
// other windows included. The System Settings/Finder sidebar look (desktop
// wallpaper only, intervening windows ignored) is applied by AppKit's own
// components: an NSSplitViewItem created with sidebar/contentList/inspector
// factories gets the WindowServer desktop-tinted backdrop, and that plumbing
// works even when the split view is hosted as a plain subview. So each glass
// region hosts a real NSSplitViewController — public API used exactly as
// intended, no private selectors — with the material pane pinned to the
// region size and the filler pane clipped away by the container.

static const char kTaoGlassRegionSplitVcKey = 17;
static const char kTaoGlassRegionKindKey = 18;

// Decorative-only container: the region renders BELOW the Compose surface
// and must never capture mouse events — NSSplitView installs divider
// tracking areas that would otherwise steal clicks/cursor updates from the
// Compose content above. Returning nil makes the whole subtree
// click-through (hitTest: covers cursorUpdate routing too).
@interface TaoGlassRegionContainerView : NSView
@end

@implementation TaoGlassRegionContainerView
- (NSView *)hitTest:(NSPoint)point {
    return nil;
}
@end

#define TAO_GLASS_REGION_KIND_SIDEBAR      0
#define TAO_GLASS_REGION_KIND_CONTENT_LIST 1
#define TAO_GLASS_REGION_KIND_INSPECTOR    2


/*
 * Inserts a region that renders AppKit's wallpaper-tinted material below the
 * content view, sized later via nativeSetGlassRegionFrame from Compose
 * layout coordinates. [kindOrdinal] is WindowGlassRegionKind.nativeValue
 * (TAO_GLASS_REGION_KIND_*: 0 sidebar / 1 content list / 2 inspector) —
 * not enum ordinal, so Kotlin reordering cannot swap materials.
 * Returns a CFBridgingRetain'ed pointer; release via nativeRemoveGlassRegion.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeAddGlassRegion(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jint kindOrdinal) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return 0;
    __block jlong result = 0;
    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        NSView *contentView = win.contentView;
        NSView *frameView = contentView.superview;
        if (win == nil || contentView == nil || frameView == nil) return;

        NSView *container = [[TaoGlassRegionContainerView alloc] initWithFrame:NSZeroRect];
        container.wantsLayer = YES;
        container.layer.masksToBounds = YES;
        // No implicit CoreAnimation on geometry: the region must track the
        // Compose panel frame-for-frame, and the default ~0.25s animation
        // reads as the material lagging behind during a live resize.
        container.layer.actions = @{
            @"position": [NSNull null],
            @"bounds": [NSNull null],
            @"frame": [NSNull null],
            @"cornerRadius": [NSNull null],
        };

        NSViewController *paneVC = [[NSViewController alloc] init];
        paneVC.view = [[NSView alloc] initWithFrame:NSZeroRect];
        NSViewController *fillVC = [[NSViewController alloc] init];
        fillVC.view = [[NSView alloc] initWithFrame:NSZeroRect];

        NSSplitViewItem *pane = nil;
        switch (kindOrdinal) {
            case TAO_GLASS_REGION_KIND_CONTENT_LIST:
                pane = [NSSplitViewItem contentListWithViewController:paneVC];
                break;
            case TAO_GLASS_REGION_KIND_INSPECTOR:
                if ([NSSplitViewItem respondsToSelector:
                        @selector(inspectorWithViewController:)]) {
                    pane = [NSSplitViewItem inspectorWithViewController:paneVC];
                } else {
                    // macOS < 14: closest system pane material.
                    pane = [NSSplitViewItem contentListWithViewController:paneVC];
                }
                break;
            default:
                pane = [NSSplitViewItem sidebarWithViewController:paneVC];
                break;
        }
        pane.canCollapse = NO;

        NSSplitViewController *split = [[NSSplitViewController alloc] init];
        // Inspector panes sit on the trailing edge of their split view; the
        // filler goes first so the material pane hugs the container.
        if (kindOrdinal == TAO_GLASS_REGION_KIND_INSPECTOR) {
            [split addSplitViewItem:
                [NSSplitViewItem splitViewItemWithViewController:fillVC]];
            [split addSplitViewItem:pane];
        } else {
            [split addSplitViewItem:pane];
            [split addSplitViewItem:
                [NSSplitViewItem splitViewItemWithViewController:fillVC]];
        }
        split.splitView.dividerStyle = NSSplitViewDividerStyleThin;
        // The split view spans the WHOLE window, exactly like a real AppKit
        // app — that is the layout AppKit expects, and it keeps the pane's
        // safe-area/titlebar handling intact. The container simply clips it
        // to the Compose panel's rect. Constraints are anchored to the theme
        // frame (not the container), so they never need updating: only the
        // pane thickness follows the region.
        NSView *splitView = split.view;
        splitView.translatesAutoresizingMaskIntoConstraints = NO;
        [container addSubview:splitView];
        // The container must already be in the window hierarchy: the
        // constraints below reference the theme frame, and Auto Layout
        // resolves them against the nearest common ancestor.
        [frameView addSubview:container
                   positioned:NSWindowBelow
                   relativeTo:contentView];
        [NSLayoutConstraint activateConstraints:@[
            [splitView.leadingAnchor constraintEqualToAnchor:frameView.leadingAnchor],
            [splitView.trailingAnchor constraintEqualToAnchor:frameView.trailingAnchor],
            [splitView.topAnchor constraintEqualToAnchor:frameView.topAnchor],
            [splitView.bottomAnchor constraintEqualToAnchor:frameView.bottomAnchor],
        ]];
        // The controller (and its view hierarchy) must outlive the JNI call.
        objc_setAssociatedObject(container, &kTaoGlassRegionSplitVcKey, split,
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(container, &kTaoGlassRegionKindKey,
                                 @(kindOrdinal),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);

        result = (jlong)(uintptr_t)CFBridgingRetain(container);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
    return result;
}

/* [x, y, w, h] in points, top-left origin in Compose scene coordinates
 * (== content view coordinates: the window is fullSizeContentView).
 * [cornerRadius] rounds the region's clip so the material can sit behind
 * rounded panels (Tahoe floating sidebars). */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetGlassRegionFrame(
        JNIEnv *env, jclass clazz, jlong regionPtr,
        jfloat x, jfloat y, jfloat w, jfloat h, jfloat cornerRadius) {
    NSView *region = (__bridge NSView *)(void *)(uintptr_t)regionPtr;
    if (region == nil) return;
    dispatch_block_t apply = ^{
        NSView *superview = region.superview;
        if (superview == nil) return;
        CGFloat winH = superview.bounds.size.height;

        // Geometry tracks the Compose panel exactly, with no implicit
        // CoreAnimation: the default ~0.25s animation on a layer-backed
        // view reads as the material lagging behind during a live resize.
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        region.frame = NSMakeRect((CGFloat)x, winH - (CGFloat)y - (CGFloat)h,
                                  (CGFloat)w, (CGFloat)h);
        if (region.layer != nil) {
            region.layer.cornerRadius = (CGFloat)cornerRadius;
            region.layer.cornerCurve = kCACornerCurveContinuous;
        }

        // Inside the same transaction: the pane thickness drives the split
        // view's own layout, and left outside it AppKit animates that layout
        // while the container frame snaps — the material edge would trail the
        // clip by several frames for the whole resize.
        NSSplitViewController *split =
            objc_getAssociatedObject(region, &kTaoGlassRegionSplitVcKey);
        if (split != nil) {
            NSNumber *kind = objc_getAssociatedObject(region, &kTaoGlassRegionKindKey);
            BOOL inspector = kind != nil &&
                kind.intValue == TAO_GLASS_REGION_KIND_INSPECTOR;

            // The split view spans the window; grow the material pane so its
            // inner edge lines up with the region's inner edge — the container
            // clips everything outside the region rect.
            NSSplitViewItem *paneItem =
                inspector ? split.splitViewItems.lastObject
                          : split.splitViewItems.firstObject;
            CGFloat thickness =
                inspector ? superview.bounds.size.width - (CGFloat)x
                          : (CGFloat)x + (CGFloat)w;
            if (thickness < 1.0) thickness = 1.0;
            paneItem.minimumThickness = thickness;
            paneItem.maximumThickness = thickness;
        }
        [CATransaction commit];
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeRemoveGlassRegion(
        JNIEnv *env, jclass clazz, jlong regionPtr) {
    if (regionPtr == 0) return;
    dispatch_block_t apply = ^{
        NSView *region =
            (NSView *)CFBridgingRelease((CFTypeRef)(uintptr_t)regionPtr);
        [region removeFromSuperview];
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_sync(dispatch_get_main_queue(), apply);
}

/*
 * Forces the window's NSAppearance so every native surface (glass regions,
 * materials, traffic lights, menus) follows the APP's theme instead of the
 * system one. Without it, an app running dark on a light system gets a light
 * sidebar material under dark Compose content — unreadable.
 * [mode] 0 = follow system, 1 = light (Aqua), 2 = dark (DarkAqua).
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetWindowAppearance(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jint mode) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;
    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        NSAppearance *appearance = nil;
        if (mode == 1) {
            appearance = [NSAppearance appearanceNamed:NSAppearanceNameAqua];
        } else if (mode == 2) {
            appearance = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
        }
        win.appearance = appearance;
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetWindowBackgroundColor(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jint argb) {
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewPtr;
    if (view == nil) return;

    dispatch_block_t apply = ^{
        NSWindow *win = view.window;
        if (win == nil) return;
        objc_setAssociatedObject(win, &kTaoBackgroundArgbKey, @(argb),
                                 OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        // Route through the stored-background path so the current
        // transparency mode is honoured: full glass keeps everything clear,
        // regions mode paints the window (which stays opaque) but leaves the
        // layers clear, opaque mode paints everything.
        applyStoredWindowBackground(win, view);
    };
    if ([NSThread isMainThread]) apply();
    else                          dispatch_async(dispatch_get_main_queue(), apply);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDetach(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    // Stop the display link before freeing `att`: CVDisplayLinkStop is
    // synchronous (blocks until any in-flight callback returns), so the
    // callback can never run against the freed attachment.
    if (att->displayLink != NULL) {
        CVDisplayLinkRef link = att->displayLink;
        att->displayLink = NULL;
        CVDisplayLinkStop(link);
        CVDisplayLinkRelease(link);
    }
    // Wake any parked vsync waiter and release the semaphore (ARC frees the
    // dispatch object when the strong field is cleared).
    if (att->vsyncSem != NULL) {
        atomic_store(&att->vsyncWaiting, false);
        dispatch_semaphore_signal(att->vsyncSem);
        att->vsyncSem = NULL;
    }
    NSWindow *win = att->view.window;
    BOOL isOverlay = att->isOverlay;
    att->layer  = nil;
    att->device = nil;
    att->queue  = nil;
    att->view   = nil;
    // Overlay attachments (nativeAttachOverlay) never installed the
    // window-level state below — it belongs to the primary attachment of the
    // window hosting the overlay's parent NSView. Tearing it down here on an
    // overlay dispose (e.g. a NativeView unmounting from the main window)
    // would deallocate the window's NucleusTaoFSObserver out from under its
    // still-alive primary attachment — permanently dropping the #327
    // fullscreen-transition handling — and leave attachmentForWindow()
    // returning NULL for the rest of the window's life.
    if (win != nil && !isOverlay) {
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDevicePtr(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return 0;
    return (jlong)(uintptr_t) (__bridge void *) HANDLE_OF(handle)->device;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeQueuePtr(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return 0;
    return (jlong)(uintptr_t) (__bridge void *) HANDLE_OF(handle)->queue;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeResize(
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
        // During an interactive live-resize the present always lags the
        // bounds by one frame (the Resized event is queued and processed on
        // a later runloop turn than the AppKit layout commit). With the
        // default `kCAGravityResize`, Core Animation stretches the stale
        // last drawable to the new — oscillating — bounds, which reads as
        // the whole window trembling when the pointer circles a corner.
        // Instead anchor the stale drawable to the window's *fixed* corner
        // for the duration of the drag so it stops rubber-banding around
        // the layer centre; the render thread still presents crisp frames
        // at the new size, and `kCAGravityResize` is restored on drag end.
        // The fixed corner is inferred from the NSWindow frame origin delta
        // (macOS reports the origin at the bottom-left corner):
        //   origin.x unchanged -> left edge fixed   (else right edge fixed)
        //   origin.y unchanged -> bottom edge fixed (else top edge fixed)
        NSString *gravity = kCAGravityResize;
        NSWindow *win = att->view.window;
        // Never anchor during an AppKit fullscreen transition: the #327
        // snapshot ramp depends on Resize gravity for the whole animation,
        // and AppKit may report inLiveResize while it animates the frame.
        BOOL liveResize = att->view.inLiveResize &&
                          atomic_load(&att->in_transition) == 0;
        if (liveResize && win != nil &&
            !isnan(att->prev_origin_x) && !isnan(att->prev_origin_y)) {
            NSRect fr = win.frame;
            BOOL leftFixed   = fabs(fr.origin.x - att->prev_origin_x) < 0.5;
            BOOL bottomFixed = fabs(fr.origin.y - att->prev_origin_y) < 0.5;
            if (leftFixed) {
                gravity = bottomFixed ? kCAGravityBottomLeft : kCAGravityTopLeft;
            } else {
                gravity = bottomFixed ? kCAGravityBottomRight : kCAGravityTopRight;
            }
        } else if (liveResize) {
            // First tick of the drag: no prior origin to diff against. Pin
            // top-left — the common bottom/right case — and let the next
            // tick self-correct to the proper fixed corner.
            gravity = kCAGravityTopLeft;
        }
        att->layer.contentsGravity = gravity;
        if (win != nil) {
            att->prev_origin_x = win.frame.origin.x;
            att->prev_origin_y = win.frame.origin.y;
        }
        [CATransaction commit];
    };
    if ([NSThread isMainThread]) resize();
    else                          dispatch_sync(dispatch_get_main_queue(), resize);
}

// ── Per-frame autorelease pool (#494) ────────────────────────────────────
//
// The JVM render thread ("TaoMetalRender") has no ObjC autorelease pool, so
// the CAMetalDrawable returned by nextDrawable and the MTLCommandBuffer
// autoreleased inside skiko's flushAndSubmit would "just leak" — one pair per
// rendered frame (~20 MB/min while anything animates). The Kotlin side pushes
// a pool before each render-thread task and pops it after, covering
// beginFrame, skiko's flush (which runs between our JNI calls, so pools
// inside the JNI functions alone would not reach it) and present in one
// drain. These are the exact calls ARC emits for @autoreleasepool; exported
// by libobjc with a stable ABI but only declared in objc-internal.h, hence
// the local declarations.
extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *pool);

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeAutoreleasePoolPush(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jlong)(uintptr_t) objc_autoreleasePoolPush();
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeAutoreleasePoolPop(
        JNIEnv *env, jclass clazz, jlong pool) {
    (void) env; (void) clazz;
    if (pool == 0) return;
    objc_autoreleasePoolPop((void *)(uintptr_t) pool);
}

JNIEXPORT jobject JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeBeginFrame(
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeIsInTransition(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    return atomic_load(&att->in_transition) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativePresent(
        JNIEnv *env, jclass clazz, jlong handle, jlong drawablePtr) {
    if (handle == 0 || drawablePtr == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);

    // Move ownership back so ARC releases after the present block finishes.
    id<CAMetalDrawable> drawable = (__bridge_transfer id<CAMetalDrawable>)
        (void *)(uintptr_t) drawablePtr;

    id<MTLCommandBuffer> commandBuffer = [att->queue commandBuffer];
    // Pace the present to the upcoming vsync recorded by the display-link
    // callback so exactly one present lands per refresh. Falls back to an
    // untimed present if the display link isn't running yet (first frame / resize).
    double presentTime = hostTimeToSeconds(atomic_load(&att->next_present_host_time));
    if (presentTime > 0.0) {
        [commandBuffer presentDrawable:drawable atTime:presentTime];
    } else {
        [commandBuffer presentDrawable:drawable];
    }
    [commandBuffer commit];
}

// ── VSync-paced rendering via CVDisplayLink ──────────────────────────────

// Blocks the calling (background) thread until the NEXT display refresh after
// this call. The AWT/skiko MetalVSyncer pattern: arm the flag, then park on the
// semaphore; the CVDisplayLink callback disarms + signals on the next refresh.
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeVSyncWait(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    if (att->displayLink == NULL || att->vsyncSem == NULL) return; // not running → don't block
    atomic_store(&att->vsyncWaiting, true);
    // Bounded wait (2 refreshes @ ~16.7ms) so a paused link (occluded/minimised
    // window stops firing) can't deadlock the render loop.
    dispatch_semaphore_wait(att->vsyncSem,
        dispatch_time(DISPATCH_TIME_NOW, (int64_t)(34 * NSEC_PER_MSEC)));
    atomic_store(&att->vsyncWaiting, false);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeStartDisplayLink(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    if (att->displayLink != NULL) return; // already running

    CVDisplayLinkRef link = NULL;
    if (CVDisplayLinkCreateWithActiveCGDisplays(&link) != kCVReturnSuccess || link == NULL) {
        return;
    }
    if (att->vsyncSem == NULL) att->vsyncSem = dispatch_semaphore_create(0);
    CVDisplayLinkSetOutputCallback(link, taoDisplayLinkCallback, att);
    att->displayLink = link;
    CVDisplayLinkStart(link);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeStopDisplayLink(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);
    CVDisplayLinkRef link = att->displayLink;
    if (link == NULL) return;
    att->displayLink = NULL;
    CVDisplayLinkStop(link);   // synchronous: no callback in flight after this
    CVDisplayLinkRelease(link);
    // Wake any parked waiter so it doesn't hang on the now-dead link.
    if (att->vsyncSem != NULL) {
        atomic_store(&att->vsyncWaiting, false);
        dispatch_semaphore_signal(att->vsyncSem);
        att->vsyncSem = NULL;
    }
}

/* Toggles CAMetalLayer.presentsWithTransaction. With the flag ON, the
 * layer defers its surface swap so it can be flushed atomically inside
 * the enclosing CATransaction together with AppKit mutations made by
 * `nativePresentWithInterop`'s callback. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetPresentsWithTransaction(
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

/* Private run-loop mode for the interop callout below. Blocks scheduled in
 * this mode (in addition to the common modes) can be executed by a main
 * thread that is otherwise BLOCKED waiting on the render thread — it spins
 * CFRunLoopRunInMode on this mode via nativeInteropPump. Tao's own run-loop
 * observers/sources live in the default/common modes and never fire here, so
 * pumping cannot re-enter event dispatch or Compose rendering. */
static NSString *const kNucleusTaoInteropMode = @"NucleusTaoInteropMode";

/* A mode that contains nothing but queued blocks is considered EMPTY by
 * CFRunLoopRunInMode, which then returns kCFRunLoopRunFinished immediately
 * WITHOUT executing them — the pump would spin uselessly and every blocked
 * present would ride the 2s backstop (visible as a 2-3s freeze on fullscreen
 * entry). This permanent no-op source keeps the mode non-empty; the
 * scheduler signals it so a pumping main thread wakes instantly. */
static CFRunLoopSourceRef sInteropModeSource = NULL;

static void interopModeSourceNoop(void *info) { (void) info; }

static void ensureInteropModeSource(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        CFRunLoopSourceContext ctx = {0};
        ctx.perform = interopModeSourceNoop;
        sInteropModeSource = CFRunLoopSourceCreate(kCFAllocatorDefault, 0, &ctx);
        CFRunLoopAddSource(CFRunLoopGetMain(), sInteropModeSource,
                           (__bridge CFStringRef) kNucleusTaoInteropMode);
    });
}

/* Atomic present-with-transaction path. See the Kotlin doc on
 * NativeMetalBridge.nativePresentWithInterop for the full sequence.
 *
 * Requires `presentsWithTransaction = YES` on the layer (set by
 * nativeSetPresentsWithTransaction) so [drawable present] joins our
 * outer CATransaction instead of being scheduled out of band. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativePresentWithInterop(
        JNIEnv *env, jclass clazz, jlong handle, jlong drawablePtr, jobject interopActions) {
    (void) clazz;
    if (handle == 0 || drawablePtr == 0) return;
    NucleusTaoMetalAttachment *att = HANDLE_OF(handle);

    // Stage 2: this is invoked from the host's background render thread (after
    // the scene's GPU encode), but CATransaction + the explicit [drawable
    // present] + the AppKit mutations in `interopActions` must run on the macOS
    // main thread. This used to be a plain dispatch_sync to the main queue on
    // the assumption that "the main thread is never itself blocked on the
    // render thread while a frame's replay is in flight" — which the
    // fullscreen prepare (#327: windowWillEnterFullScreen → blocking frame via
    // runOnRenderThread) and the NativeView overlay first-attach violate,
    // deadlocking the app the moment either coincides with an in-flight
    // interop present. The block is therefore scheduled on the main RUN LOOP
    // instead, registered in the common modes (normal drain) AND the private
    // kNucleusTaoInteropMode, which a blocked main thread pumps via
    // nativeInteropPump while it waits on the render thread.
    ensureMetalJVMCached(env);

    // Promote the Runnable to a global ref: the local ref `interopActions` is
    // valid only on this (render) thread's JNI frame, but the block runs on the
    // main thread with the main thread's JNIEnv.
    jobject interopGlobal = (interopActions != NULL)
        ? (*env)->NewGlobalRef(env, interopActions) : NULL;

    // Take ownership of the drawable (balances nativeBeginFrame's retain) and
    // capture it + the queue in the block so ARC keeps them alive until present.
    id<CAMetalDrawable> drawable = (__bridge_transfer id<CAMetalDrawable>)
        (void *)(uintptr_t) drawablePtr;
    id<MTLCommandQueue> queue = att->queue;

    void (^work)(void) = ^{
        [CATransaction begin];

        id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
        [commandBuffer commit];
        // Block until the GPU has scheduled our work — required before an
        // explicit [drawable present] under presentsWithTransaction = YES.
        [commandBuffer waitUntilScheduled];
        [drawable present];

        if (interopGlobal != NULL) {
            // Resolve the main thread's JNIEnv (it's the JVM main thread, so
            // already attached). Cache Runnable.run() once — stable for the
            // JVM lifetime.
            JNIEnv *menv = NULL;
            jint status = sMetalJVM
                ? (*sMetalJVM)->GetEnv(sMetalJVM, (void **)&menv, JNI_VERSION_1_8)
                : JNI_ERR;
            if (status == JNI_EDETACHED && sMetalJVM) {
                (*sMetalJVM)->AttachCurrentThreadAsDaemon(sMetalJVM, (void **)&menv, NULL);
            }
            if (menv != NULL) {
                static jclass sRunnableClass = NULL;
                static jmethodID sRunMethod = NULL;
                if (sRunMethod == NULL) {
                    jclass local = (*menv)->FindClass(menv, "java/lang/Runnable");
                    if (local != NULL) {
                        sRunnableClass = (*menv)->NewGlobalRef(menv, local);
                        (*menv)->DeleteLocalRef(menv, local);
                        if (sRunnableClass != NULL) {
                            sRunMethod = (*menv)->GetMethodID(menv, sRunnableClass, "run", "()V");
                        }
                    }
                }
                if (sRunMethod != NULL) {
                    (*menv)->CallVoidMethod(menv, interopGlobal, sRunMethod);
                    if ((*menv)->ExceptionCheck(menv)) {
                        (*menv)->ExceptionDescribe(menv);
                        (*menv)->ExceptionClear(menv);
                    }
                }
                (*menv)->DeleteGlobalRef(menv, interopGlobal);
            }
        }

        [CATransaction commit];
    };

    if ([NSThread isMainThread]) {
        work();
        return;
    }

    // Once-guard shared by both mode registrations (copies of the same block
    // share the __block slot; both callouts run on the main thread, so no
    // atomics needed).
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    __block BOOL ran = NO;
    void (^once)(void) = ^{
        if (ran) return;
        ran = YES;
        work();
        dispatch_semaphore_signal(sem);
    };
    ensureInteropModeSource();
    CFRunLoopRef mainLoop = CFRunLoopGetMain();
    CFRunLoopPerformBlock(mainLoop, kCFRunLoopCommonModes, once);
    CFRunLoopPerformBlock(mainLoop, (__bridge CFStringRef) kNucleusTaoInteropMode, once);
    // Wake a main thread parked inside nativeInteropPump's RunInMode as well
    // as the regular event loop.
    CFRunLoopSourceSignal(sInteropModeSource);
    CFRunLoopWakeUp(mainLoop);
    // Generous backstop: if the main thread is wedged somewhere that neither
    // runs its loop nor pumps, degrade to a late/skipped transaction (the
    // queued block still presents when the loop resumes) instead of parking
    // the render thread forever.
    if (dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC)) != 0) {
        NTLOG("nativePresentWithInterop: main-thread callout timed out after 2s");
    }
}

/* Executes any pending interop callouts while the caller (which must be the
 * macOS main thread) is blocked waiting on the render thread — see
 * kNucleusTaoInteropMode above and TaoComposeSceneHost.runOnRenderThread.
 * Bounded: returns after one callout or ~4ms, whichever comes first. */
/* True on the AppKit main thread. Kotlin cannot decide this itself: on macOS
 * nativeRunBlocking marshals the event loop onto thread 0, so the JVM thread
 * that entered taoApplication (TaoMainDispatcher.taoMainThread) is NOT the
 * thread AppKit callbacks run on. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeIsMainThread(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return [NSThread isMainThread] ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeInteropPump(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (![NSThread isMainThread]) return;
    // The permanent source keeps the mode non-empty — without it RunInMode
    // returns kCFRunLoopRunFinished at once and never executes queued blocks.
    ensureInteropModeSource();
    // Timeout 0: one non-blocking pass — run whatever callout is already
    // queued and return. The caller (runOnRenderThread's cooperative wait)
    // polls; a positive timeout here would PARK the main thread for its
    // full duration on every pump with nothing queued, a fixed tax paid
    // once per TextureView producer frame via the per-frame snapshot hop
    // (measured 4.5ms/hop with 4ms — enough to halve 90Hz video to 45fps).
    CFRunLoopRunInMode((__bridge CFStringRef) kNucleusTaoInteropMode, 0.0, true);
}

// ── newFullscreenControls JNI bridge ─────────────────────────────────────
//
// Mirrors `decorated-window-jni`'s JniMacTitleBarBridge entries so the Tao
// backend exposes the same `Modifier.newFullscreenControls()` behaviour
// (title bar slides down with the auto-shown system menu bar in fullscreen).
// All entry points hop to the AppKit main queue before touching AppKit.

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetNewFullscreenControls(
        JNIEnv *env, jclass clazz, jlong nsViewPtr, jboolean enabled) {
    if (nsViewPtr == 0) return;
    ensureMetalJVMCached(env);
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    // Force-disable on pre-Tahoe systems — see installMenuBarMonitor.
    BOOL flag = (enabled == JNI_TRUE) && isTahoeOrLater();
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeInstallMenuBarMonitor(
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeRemoveMenuBarMonitor(
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeSetMenuBarOffset(
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
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeUpdateFullScreenButtons(
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

// ── Window-state diagnostics (headful e2e probes) ────────────────────────
//
// Same role as nativeMacOsProbeSheetParent in the Tao bridge: tiny read-only
// entry points the stage-2 headful suite uses to assert native invariants
// that have no other JVM-visible signal. Not part of any production path.

/* Bitmask of the window-level state nativeAttach installs on view.window:
 * bit 0 = kTaoAttachmentKey (primary attachment reachable via
 * attachmentForWindow), bit 1 = kTaoFSObserverKey (fullscreen-transition
 * observer, #327). Returns -1 when the view or its window is gone. */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDiagWindowState(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return -1;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    __block jint state = -1;
    dispatch_block_t read = ^{
        NSView *view = (__bridge NSView *)rawPtr;
        NSWindow *w = view.window;
        if (w == nil) return;
        state = 0;
        if (objc_getAssociatedObject(w, &kTaoAttachmentKey) != nil) state |= 1;
        if (objc_getAssociatedObject(w, &kTaoFSObserverKey) != nil) state |= 2;
    };
    if ([NSThread isMainThread]) read();
    else                          dispatch_sync(dispatch_get_main_queue(), read);
    return state;
}

/* Frame SIZE of an arbitrary NSView in physical pixels (points scaled by
 * its window's backingScaleFactor), packed (w << 32) | h. Lets the headful
 * suite assert that an embedded NativeView subview actually tracked a layout
 * change (e.g. across a fullscreen round-trip). Returns 0 when the view is
 * gone. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDiagViewFrameSize(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return 0;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    __block jlong packed = 0;
    dispatch_block_t read = ^{
        NSView *view = (__bridge NSView *)rawPtr;
        if (view == nil) return;
        CGFloat scale = view.window.backingScaleFactor;
        if (scale <= 0) scale = 1.0;
        jlong w = (jlong) lround(view.frame.size.width * scale);
        jlong h = (jlong) lround(view.frame.size.height * scale);
        packed = (w << 32) | (h & 0xFFFFFFFFLL);
    };
    if ([NSThread isMainThread]) read();
    else                          dispatch_sync(dispatch_get_main_queue(), read);
    return packed;
}

/* TOP-LEFT origin of an NSView within its superview, in physical pixels
 * with a top-left coordinate system (Compose convention), packed as two
 * signed 32-bit values (x << 32) | (y & 0xFFFFFFFF). Complements
 * nativeDiagViewFrameSize: a subview can have the right SIZE but sit at the
 * wrong offset after a fullscreen transition (bottom-left AppKit anchoring
 * vs a stale parent height). Returns LLONG_MIN when the view is gone. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDiagViewTopLeftPx(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return LLONG_MIN;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    __block jlong packed = LLONG_MIN;
    dispatch_block_t read = ^{
        NSView *view = (__bridge NSView *)rawPtr;
        NSView *parent = view.superview;
        if (view == nil || parent == nil) return;
        CGFloat scale = view.window.backingScaleFactor;
        if (scale <= 0) scale = 1.0;
        NSRect f = view.frame;
        CGFloat topPt = parent.isFlipped
            ? f.origin.y
            : parent.bounds.size.height - (f.origin.y + f.size.height);
        int64_t x = (int64_t) lround(f.origin.x * scale);
        int64_t y = (int64_t) lround(topPt * scale);
        packed = (jlong)((((uint64_t)(uint32_t) x) << 32) | ((uint64_t)(uint32_t) y));
    };
    if ([NSThread isMainThread]) read();
    else                          dispatch_sync(dispatch_get_main_queue(), read);
    return packed;
}

/* CFGetRetainCount of view.window. Only deltas are meaningful (AppKit holds
 * its own references); the set_focusable leak regression compares the count
 * before/after a burst of calls. Returns -1 when view/window is gone. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeDiagWindowRetainCount(
        JNIEnv *env, jclass clazz, jlong nsViewPtr) {
    if (nsViewPtr == 0) return -1;
    void *rawPtr = (void *)(uintptr_t)nsViewPtr;
    __block jlong count = -1;
    dispatch_block_t read = ^{
        NSView *view = (__bridge NSView *)rawPtr;
        NSWindow *w = view.window;
        if (w == nil) return;
        count = (jlong) CFGetRetainCount((__bridge CFTypeRef) w);
    };
    if ([NSThread isMainThread]) read();
    else                          dispatch_sync(dispatch_get_main_queue(), read);
    return count;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeMetalBridge_nativeShutdown(
        JNIEnv *env, jclass clazz) {
    // Stop further dispatch_async work and silence JNI callbacks. Cleanup of
    // remaining monitors is best-effort: a JVM shutdown hook may run very
    // late and AppKit could already be gone, so we just async-fire the
    // removal and rely on the atomic flags to prevent any callback racing.
    atomic_store(&sMetalShutdownInProgress, true);
    atomic_store(&sMetalCallbacksEnabled, false);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (sTaoMenuBarEventHandler != NULL) {
            RemoveEventHandler(sTaoMenuBarEventHandler);
            sTaoMenuBarEventHandler = NULL;
        }
        for (NSWindow *w in NSApp.windows) {
            if (objc_getAssociatedObject(w, &kTaoMenuBarMonitorKey) != nil) {
                removeMenuBarMonitor(w);
            }
        }
    });
}

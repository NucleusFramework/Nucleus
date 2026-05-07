// popup_panel.m
//
// Phase 1+ of the Compose-Popup-via-NSPanel architecture (post-mortem +
// research plan): builds a borderless, non-activating, transparent
// `NSPanel` attached as a child window of the Tao-owned host `NSWindow`.
// The panel hosts a custom `NSView` content view (`NucleusTaoPopupContent`)
// whose `CAMetalLayer` is wired by `NativeMetalBridge.nativeAttachOverlay`
// for the Compose scene to render into.
//
// **Phase 4 additions** (this file):
//  - `NucleusTaoPopupContent` overrides every relevant AppKit event
//    handler (mouseDown/Up/Move/Drag/right-click/scroll/keyDown/Up/
//    flagsChanged) and forwards them through a JNI callback so the
//    inner `ComposeScene` running inside `TaoPopupSceneLayer` can
//    `sendPointerEvent` / `sendKeyEvent` and route them to popup
//    content (menu items, text fields, …).
//  - `nativeSetFocusable` lets the layer toggle the panel's
//    `canBecomeKeyWindow` so PopupProperties.focusable=true populates
//    correctly.
//  - `nativeInstallOutsideClickMonitor` installs an `NSEvent
//    addLocalMonitorForEventsMatchingMask:` that fires for every left/
//    right mouseDown anywhere in the app and calls back into Java
//    when the click is **not** inside our panel — that's what
//    `setOutsidePointerEventListener` plumbs through to Compose's
//    Popup `dismissOnClickOutside` logic.
//
// Compiled into `libnucleus_tao_macos_popup.dylib`. Loaded from
// `NativeLibraryLoader.load("nucleus_tao_macos_popup")`.
//
// Threading: every entry point runs on the macOS main thread (= Tao
// event-loop thread = Compose dispatcher thread).
//
// Coordinate system on entry: parent-window pixels with a top-left origin
// (matching Compose's `boundsInWindow`). The native side flips Y and
// translates to AppKit's bottom-left, points-based screen coordinates.

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <jni.h>
#include <stdatomic.h>

// ── JVM caching for the per-panel event callback ────────────────────────
//
// Mirrors the pattern used by NucleusTaoMetal.m / dnd.m: cache the
// JavaVM, the callback method IDs, and the global jclass for the
// callback interface once on first use, gated by an atomic so a JVM
// shutdown can silence callbacks before tear-down.

static JavaVM *sJVM = NULL;
static jclass sCallbackClass = NULL;          // global ref to the Java callback interface
static jmethodID sOnPointerMethod = NULL;     // (IFFII)V — type, x, y, button, modifiers
static jmethodID sOnScrollMethod = NULL;      // (FFFF)V  — x, y, dx, dy
static jmethodID sOnKeyMethod = NULL;         // (IIII)V  — type, vkCode, codePoint, modifiers
static jclass sOutsideListenerClass = NULL;
static jmethodID sOutsideOnClickMethod = NULL; // (II)V  — eventType, button
static atomic_bool sCacheInited = ATOMIC_VAR_INIT(false);

#define EVT_PTR_DOWN  1
#define EVT_PTR_UP    2
#define EVT_PTR_MOVE  3
#define EVT_KEY_DOWN  1
#define EVT_KEY_UP    2

static void ensureCallbackCache(JNIEnv *env, jobject cbSample, jobject outsideSample) {
    if (atomic_load(&sCacheInited)) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (cbSample != NULL && sCallbackClass == NULL) {
        jclass local = (*env)->GetObjectClass(env, cbSample);
        if (local != NULL) {
            sCallbackClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            sOnPointerMethod = (*env)->GetMethodID(env, sCallbackClass, "onPointerEvent", "(IFFII)V");
            sOnScrollMethod  = (*env)->GetMethodID(env, sCallbackClass, "onScroll",       "(FFFF)V");
            sOnKeyMethod     = (*env)->GetMethodID(env, sCallbackClass, "onKeyEvent",     "(IIII)V");
        }
    }
    if (outsideSample != NULL && sOutsideListenerClass == NULL) {
        jclass local = (*env)->GetObjectClass(env, outsideSample);
        if (local != NULL) {
            sOutsideListenerClass = (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
            sOutsideOnClickMethod = (*env)->GetMethodID(env, sOutsideListenerClass, "onOutsideClick", "(II)V");
        }
    }
    if (sCallbackClass != NULL && sOutsideListenerClass != NULL &&
        sOnPointerMethod && sOnScrollMethod && sOnKeyMethod && sOutsideOnClickMethod) {
        atomic_store(&sCacheInited, true);
    }
}

static JNIEnv *attachThread(void) {
    if (sJVM == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) != JNI_OK) {
            return NULL;
        }
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

// ── Content NSView with event forwarding ──────────────────────────────────

static const char kCallbackKey       = 1; // jobject global ref
static const char kCallbackEnableKey = 2; // BOOL — flipped to NO on release
static const char kRegionEnableKey   = 3; // BOOL — region-based hitTest mode
static const char kRegionDataKey     = 4; // NSData with float[count*4] (x, y, w, h) in pixels
static const char kRegionCountKey    = 5; // NSNumber<int>

// Forward declaration: the panel class is defined further down but the
// content view's `maybeBecomeKey:` needs to refer to it.
@class NucleusTaoPopupPanel;

@interface NucleusTaoPopupContent : NSView
- (BOOL)nucleusRegionHitForWindowPoint:(NSPoint)windowPoint;
- (BOOL)nucleusRegionHitTestEnabled;
@end

@implementation NucleusTaoPopupContent

- (BOOL)wantsUpdateLayer { return YES; }
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }

- (BOOL)nucleusRegionHitTestEnabled {
    NSNumber *enableNum = objc_getAssociatedObject(self, &kRegionEnableKey);
    return enableNum != nil && enableNum.boolValue;
}

- (BOOL)nucleusRegionHitForWindowPoint:(NSPoint)windowPoint {
    if (![self nucleusRegionHitTestEnabled]) return YES;
    NSNumber *countNum = objc_getAssociatedObject(self, &kRegionCountKey);
    NSData *dataObj    = objc_getAssociatedObject(self, &kRegionDataKey);
    if (countNum == nil || dataObj == nil) return NO;
    int count = countNum.intValue;
    if (count <= 0) return NO;

    NSPoint local = [self convertPoint:windowPoint fromView:nil];
    CGFloat scale = self.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    CGFloat px = local.x * scale;
    CGFloat py = (self.bounds.size.height - local.y) * scale;
    const float *rects = (const float *)dataObj.bytes;
    for (int i = 0; i < count; i++) {
        float rx = rects[i * 4 + 0];
        float ry = rects[i * 4 + 1];
        float rw = rects[i * 4 + 2];
        float rh = rects[i * 4 + 3];
        if (px >= rx && py >= ry && px < rx + rw && py < ry + rh) {
            return YES;
        }
    }
    return NO;
}

/* When region-mode is enabled, walk the registered rect list. Points
 * outside every rect cause `hitTest:` to return nil — AppKit then
 * falls through to the parent NSWindow, so events on transparent areas
 * of the overlay reach the host's subviews (typically a `WKWebView`).
 *
 * Mode disabled (default) → standard full-intercept behaviour, used by
 * Compose `Popup` / `DropdownMenu` / context menus. */
- (NSView *)hitTest:(NSPoint)point {
    if (![self nucleusRegionHitTestEnabled]) {
        return [super hitTest:point];
    }
    NSPoint windowPoint = [self convertPoint:point toView:nil];
    return [self nucleusRegionHitForWindowPoint:windowPoint] ? self : nil;
}

/* AppKit only delivers `mouseMoved:` to a view that has an active
 * `NSTrackingArea` covering the cursor location. Without one, hover
 * effects on Compose menu items / dropdown rows / hover-styled buttons
 * never fire. We refresh on every layout change so the area always
 * matches the popup's current bounds. */
- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    for (NSTrackingArea *ta in self.trackingAreas) {
        [self removeTrackingArea:ta];
    }
    NSTrackingAreaOptions opts = NSTrackingMouseMoved
                               | NSTrackingMouseEnteredAndExited
                               | NSTrackingActiveInKeyWindow
                               | NSTrackingActiveAlways
                               | NSTrackingInVisibleRect;
    NSTrackingArea *ta = [[NSTrackingArea alloc]
        initWithRect:NSZeroRect      // ignored when InVisibleRect is set
             options:opts
               owner:self
            userInfo:nil];
    [self addTrackingArea:ta];
}

- (jobject)takeCallbackOrNil {
    NSNumber *enabled = objc_getAssociatedObject(self, &kCallbackEnableKey);
    if (enabled == nil || !enabled.boolValue) return NULL;
    NSValue *val = objc_getAssociatedObject(self, &kCallbackKey);
    return val != nil ? (jobject)val.pointerValue : NULL;
}

/* Convert event location (panel-window coords, bottom-left, points) to
 * popup-local pixels with a top-left origin — the coordinate space
 * `ComposeScene.sendPointerEvent` expects (since the inner scene's
 * `boundsInWindow` setter places content at `(0,0)` of its own root
 * via `RootMeasurePolicy.place`). */
- (void)pixelsForEvent:(NSEvent *)event outX:(jfloat *)outX outY:(jfloat *)outY {
    NSPoint p = [self convertPoint:event.locationInWindow fromView:nil];
    CGFloat scale = self.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    *outX = (jfloat)(p.x * scale);
    // Compose / Skia top-left, AppKit bottom-left → flip Y.
    *outY = (jfloat)((self.bounds.size.height - p.y) * scale);
}

- (jint)modifierMaskFor:(NSEvent *)event {
    NSEventModifierFlags m = event.modifierFlags;
    jint out = 0;
    if (m & NSEventModifierFlagShift)   out |= 0x1;
    if (m & NSEventModifierFlagControl) out |= 0x2;
    if (m & NSEventModifierFlagOption)  out |= 0x4;
    if (m & NSEventModifierFlagCommand) out |= 0x8;
    return out;
}

- (void)dispatchPointer:(NSEvent *)event type:(jint)type button:(jint)button {
    jobject cb = [self takeCallbackOrNil];
    if (cb == NULL) return;
    JNIEnv *env = attachThread();
    if (env == NULL) return;
    jfloat x, y;
    [self pixelsForEvent:event outX:&x outY:&y];
    (*env)->CallVoidMethod(env, cb, sOnPointerMethod, type, x, y, button, [self modifierMaskFor:event]);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* On mouseDown inside a focusable panel, escalate the panel to key
 * window before dispatching the event. With `becomesKeyOnlyIfNeeded =
 * YES`, AppKit can't tell that a Compose `BasicTextField` (which is
 * just pixels in our CAMetalLayer, not an NSTextView) wants the
 * keyboard, so we have to opt-in explicitly. Without this, key events
 * never reach the popup's inner ComposeScene. */
- (void)maybeBecomeKey:(NSEvent *)event {
    NSWindow *win = self.window;
    // Polymorphic dispatch — `canBecomeKeyWindow` is overridden in
    // `NucleusTaoPopupPanel` to return the `canKey` flag, so this picks
    // up the focusable state set by `nativeSetFocusable`.
    if (win != nil && win.canBecomeKeyWindow && !win.isKeyWindow) {
        [win makeKeyWindow];
    }
}

- (void)mouseDown:(NSEvent *)event       { [self maybeBecomeKey:event]; [self dispatchPointer:event type:EVT_PTR_DOWN button:1]; }
- (void)mouseUp:(NSEvent *)event         { [self dispatchPointer:event type:EVT_PTR_UP   button:1]; }
- (void)mouseMoved:(NSEvent *)event      { [self dispatchPointer:event type:EVT_PTR_MOVE button:0]; }
- (void)mouseDragged:(NSEvent *)event    { [self dispatchPointer:event type:EVT_PTR_MOVE button:1]; }
- (void)rightMouseDown:(NSEvent *)event  { [self maybeBecomeKey:event]; [self dispatchPointer:event type:EVT_PTR_DOWN button:2]; }
- (void)rightMouseUp:(NSEvent *)event    { [self dispatchPointer:event type:EVT_PTR_UP   button:2]; }

- (void)scrollWheel:(NSEvent *)event {
    jobject cb = [self takeCallbackOrNil];
    if (cb == NULL) { [super scrollWheel:event]; return; }
    JNIEnv *env = attachThread();
    if (env == NULL) return;
    jfloat x, y;
    [self pixelsForEvent:event outX:&x outY:&y];
    (*env)->CallVoidMethod(env, cb, sOnScrollMethod,
        x, y, (jfloat)event.scrollingDeltaX, (jfloat)event.scrollingDeltaY);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

- (void)dispatchKey:(NSEvent *)event type:(jint)type {
    jobject cb = [self takeCallbackOrNil];
    if (cb == NULL) return;
    JNIEnv *env = attachThread();
    if (env == NULL) return;
    jint vk = (jint)event.keyCode;
    NSString *chars = event.characters;
    if (chars.length == 0) chars = event.charactersIgnoringModifiers;
    jint cp = (chars.length > 0) ? (jint)[chars characterAtIndex:0] : 0;
    (*env)->CallVoidMethod(env, cb, sOnKeyMethod, type, vk, cp, [self modifierMaskFor:event]);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

- (void)keyDown:(NSEvent *)event { [self dispatchKey:event type:EVT_KEY_DOWN]; }
- (void)keyUp:(NSEvent *)event   { [self dispatchKey:event type:EVT_KEY_UP];   }

@end

// ── NSPanel subclass — controls key-window participation dynamically ────

@interface NucleusTaoPopupPanel : NSPanel
@property (nonatomic, weak) NSWindow *parentHostWindow;
@property (nonatomic) BOOL canKey;
@property (nonatomic, strong) id outsideMonitor;        // NSEvent monitor token
@property (nonatomic, strong) NSValue *outsideListenerVal; // jobject global ref boxed
@end

@implementation NucleusTaoPopupPanel
- (BOOL)canBecomeKeyWindow  { return self.canKey; }
- (BOOL)canBecomeMainWindow { return NO; }

- (BOOL)nucleusIsMouseEvent:(NSEvent *)event {
    switch (event.type) {
        case NSEventTypeLeftMouseDown:
        case NSEventTypeLeftMouseUp:
        case NSEventTypeLeftMouseDragged:
        case NSEventTypeRightMouseDown:
        case NSEventTypeRightMouseUp:
        case NSEventTypeRightMouseDragged:
        case NSEventTypeOtherMouseDown:
        case NSEventTypeOtherMouseUp:
        case NSEventTypeOtherMouseDragged:
        case NSEventTypeMouseMoved:
            return YES;
        default:
            return NO;
    }
}

- (BOOL)nucleusShouldForwardToParent:(NSEvent *)event {
    if (![self nucleusIsMouseEvent:event]) return NO;
    if (self.parentHostWindow == nil) return NO;
    NucleusTaoPopupContent *content = (NucleusTaoPopupContent *)self.contentView;
    if (content == nil || ![content nucleusRegionHitTestEnabled]) return NO;
    return ![content nucleusRegionHitForWindowPoint:event.locationInWindow];
}

- (void)nucleusForwardMouseEventToParent:(NSEvent *)event {
    NSWindow *parent = self.parentHostWindow;
    if (parent == nil) return;
    NSPoint screenPoint = [self convertPointToScreen:event.locationInWindow];
    NSPoint parentPoint = [parent convertPointFromScreen:screenPoint];
    NSEvent *forwarded = [NSEvent mouseEventWithType:event.type
                                           location:parentPoint
                                      modifierFlags:event.modifierFlags
                                          timestamp:event.timestamp
                                       windowNumber:parent.windowNumber
                                            context:nil
                                        eventNumber:event.eventNumber
                                         clickCount:event.clickCount
                                           pressure:event.pressure];
    [parent sendEvent:forwarded];
}

- (void)sendEvent:(NSEvent *)event {
    if ([self nucleusShouldForwardToParent:event]) {
        [self nucleusForwardMouseEventToParent:event];
        return;
    }
    [super sendEvent:event];
}
@end

static NSWindow *window_from_view(jlong viewPtr) {
    if (viewPtr == 0) return nil;
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)viewPtr;
    return view.window;
}

static NSRect to_screen_frame(NSWindow *parent, jint xPx, jint yPx, jint wPx, jint hPx) {
    CGFloat scale = parent.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    NSRect parentFrame = parent.frame;
    CGFloat parentTop = parentFrame.origin.y + parentFrame.size.height;
    CGFloat xPt = (CGFloat)xPx / scale;
    CGFloat yPt = (CGFloat)yPx / scale;
    CGFloat wPt = (CGFloat)wPx / scale;
    CGFloat hPt = (CGFloat)hPx / scale;
    return NSMakeRect(parentFrame.origin.x + xPt,
                      parentTop - yPt - hPt,
                      wPt, hPt);
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: io.github.kdroidfilter.nucleus.window.tao                 */
/*  Class:   PopupNativeBridge                                         */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeCreatePanel(
    JNIEnv *env, jclass clazz,
    jlong parentNsViewPtr,
    jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    NSWindow *parent = window_from_view(parentNsViewPtr);
    if (parent == nil) return 0;

    NSRect frame = to_screen_frame(parent, xPx, yPx, widthPx, heightPx);
    NSWindowStyleMask mask = NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel;

    NucleusTaoPopupPanel *panel =
        [[NucleusTaoPopupPanel alloc] initWithContentRect:frame
                                                styleMask:mask
                                                  backing:NSBackingStoreBuffered
                                                    defer:NO];
    panel.parentHostWindow = parent;
    panel.canKey = NO; // toggled via nativeSetFocusable
    panel.opaque = NO;
    panel.backgroundColor = [NSColor clearColor];
    panel.hasShadow = YES;
    panel.level = NSPopUpMenuWindowLevel;
    panel.hidesOnDeactivate = NO;
    panel.animationBehavior = NSWindowAnimationBehaviorNone;
    panel.movableByWindowBackground = NO;
    panel.releasedWhenClosed = NO;
    // Non-activating panels become key only when they actually need
    // keyboard focus (e.g. there's a text field as first responder).
    // Combined with `canKey` this gives us the typical "popup steals
    // focus only on demand" semantics.
    [panel setBecomesKeyOnlyIfNeeded:YES];

    // Phase 4 content view: forwards mouse / scroll / key events through
    // a JNI callback. CAMetalLayer is attached separately via
    // NativeMetalBridge.nativeAttachOverlay on this same NSView pointer.
    NucleusTaoPopupContent *contentView =
        [[NucleusTaoPopupContent alloc] initWithFrame:NSMakeRect(0, 0, frame.size.width, frame.size.height)];
    contentView.wantsLayer = YES;
    panel.contentView = contentView;

    [parent addChildWindow:panel ordered:NSWindowAbove];

    void *retained = (__bridge_retained void *)panel;
    return (jlong)(uintptr_t)retained;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetFrameInWindow(
    JNIEnv *env, jclass clazz,
    jlong panelPtr,
    jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    NSWindow *parent = panel.parentHostWindow;
    if (parent == nil) return;
    NSRect frame = to_screen_frame(parent, xPx, yPx, widthPx, heightPx);
    [panel setFrame:frame display:YES];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeOrderFront(
    JNIEnv *env, jclass clazz, jlong panelPtr)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    [panel orderFrontRegardless];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeOrderOut(
    JNIEnv *env, jclass clazz, jlong panelPtr)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    [panel orderOut:nil];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panelPtr, jboolean focusable)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    panel.canKey = focusable ? YES : NO;
    // Deliberately NOT calling `[panel makeKeyWindow]` here. With
    // `becomesKeyOnlyIfNeeded = YES` (set in nativeCreatePanel), AppKit
    // grants the panel key status only when the user clicks on a
    // control that actually needs keyboard focus (e.g. an `NSTextField`,
    // or here a Compose `BasicTextField` rendered inside the popup).
    // Forcing key status preemptively makes the host NSWindow lose its
    // "active" appearance the moment a context menu / DropdownMenu
    // opens — visually disruptive and not how native AppKit menus
    // behave.
}

/* Toggle whether the panel intercepts pointer events at the AppKit
 * level. When set to true, mouse events pass straight through to
 * whatever sits beneath the panel — typically the Tao host window's
 * subviews (a WKWebView etc.). Used for "watermark"-style popups that
 * should be visible but not block interaction. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetIgnoresMouseEvents(
    JNIEnv *env, jclass clazz, jlong panelPtr, jboolean ignore)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    [panel setIgnoresMouseEvents:ignore ? YES : NO];
}

/* Toggles region-based hit-testing. Used by `NativeView`'s overlay
 * slot so transparent regions pass clicks through to the underlying
 * native subview (WKWebView etc.). When disabled (default) the panel
 * intercepts everything — what Compose `Popup` / `DropdownMenu` need. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetRegionHitTestEnabled(
    JNIEnv *env, jclass clazz, jlong panelPtr, jboolean enable)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    NucleusTaoPopupContent *content = (NucleusTaoPopupContent *)panel.contentView;
    if (content == nil) return;
    objc_setAssociatedObject(content, &kRegionEnableKey,
        enable ? @YES : @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

/* Replaces the overlay's interactive-region list. [rectsPx] is a flat
 * `(x, y, w, h)` × [count] array in physical pixels with a top-left
 * origin (panel-local). When region-hit-test is enabled, the panel's
 * `hitTest:` returns hit only inside one of these rects. Pass count=0
 * to clear (full passthrough). */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetInteractiveRegions(
    JNIEnv *env, jclass clazz, jlong panelPtr, jfloatArray rectsPx, jint count)
{
    (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    NucleusTaoPopupContent *content = (NucleusTaoPopupContent *)panel.contentView;
    if (content == nil) return;
    if (count <= 0 || rectsPx == NULL) {
        objc_setAssociatedObject(content, &kRegionCountKey, @0, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(content, &kRegionDataKey,  nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        return;
    }
    jsize len = (*env)->GetArrayLength(env, rectsPx);
    int safeCount = (int)(len / 4);
    if (safeCount > count) safeCount = count;
    NSMutableData *buf = [NSMutableData dataWithLength:(NSUInteger)(safeCount * 4 * sizeof(float))];
    (*env)->GetFloatArrayRegion(env, rectsPx, 0, safeCount * 4, (jfloat *)buf.mutableBytes);
    objc_setAssociatedObject(content, &kRegionCountKey,
        [NSNumber numberWithInt:safeCount], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(content, &kRegionDataKey,
        buf, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panelPtr, jobject callback)
{
    (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    NucleusTaoPopupContent *content = (NucleusTaoPopupContent *)panel.contentView;
    if (content == nil) return;

    // Drop any previous callback global ref.
    NSValue *prev = objc_getAssociatedObject(content, &kCallbackKey);
    if (prev != nil) {
        jobject prevRef = (jobject)prev.pointerValue;
        if (prevRef != NULL) (*env)->DeleteGlobalRef(env, prevRef);
        objc_setAssociatedObject(content, &kCallbackKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }

    if (callback == NULL) {
        objc_setAssociatedObject(content, &kCallbackEnableKey, @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        return;
    }

    ensureCallbackCache(env, callback, NULL);
    jobject globalRef = (*env)->NewGlobalRef(env, callback);
    objc_setAssociatedObject(content, &kCallbackKey,
        [NSValue valueWithPointer:globalRef], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(content, &kCallbackEnableKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

/* Installs a process-wide NSEvent monitor that fires for every left/right
 * mouseDown anywhere in the app. When the click does NOT target this
 * panel, calls back into Java's `OutsideClickListener.onOutsideClick`
 * which Compose's Popup framework wires to `dismissOnClickOutside`.
 *
 * The monitor returns the event unchanged so AppKit dispatches it
 * normally (we observe; we don't consume). */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panelPtr, jobject listener)
{
    (void)clazz;
    if (panelPtr == 0 || listener == NULL) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    if (panel.outsideMonitor != nil) {
        [NSEvent removeMonitor:panel.outsideMonitor];
        panel.outsideMonitor = nil;
    }
    if (panel.outsideListenerVal != nil) {
        jobject prev = (jobject)panel.outsideListenerVal.pointerValue;
        if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
        panel.outsideListenerVal = nil;
    }
    ensureCallbackCache(env, NULL, listener);
    jobject globalRef = (*env)->NewGlobalRef(env, listener);
    panel.outsideListenerVal = [NSValue valueWithPointer:globalRef];

    __weak NucleusTaoPopupPanel *weakPanel = panel;
    NSEventMask mask = NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown |
                       NSEventMaskOtherMouseDown;
    panel.outsideMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:mask
                                     handler:^NSEvent *(NSEvent *e) {
        NucleusTaoPopupPanel *p = weakPanel;
        if (p == nil) return e;
        // Same-window click → it's a click *inside* our popup; don't
        // fire the outside listener (Popup content handles it via the
        // event-callback path).
        if (e.window == p) return e;
        NSValue *box = p.outsideListenerVal;
        if (box == nil) return e;
        jobject cb = (jobject)box.pointerValue;
        if (cb == NULL) return e;
        JNIEnv *jenv = attachThread();
        if (jenv == NULL) return e;
        jint type = EVT_PTR_DOWN;
        jint btn = 1;
        if (e.type == NSEventTypeRightMouseDown) btn = 2;
        else if (e.type == NSEventTypeOtherMouseDown) btn = 3;
        (*jenv)->CallVoidMethod(jenv, cb, sOutsideOnClickMethod, type, btn);
        if ((*jenv)->ExceptionCheck(jenv)) (*jenv)->ExceptionClear(jenv);
        return e;
    }];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panelPtr)
{
    (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    if (panel.outsideMonitor != nil) {
        [NSEvent removeMonitor:panel.outsideMonitor];
        panel.outsideMonitor = nil;
    }
    if (panel.outsideListenerVal != nil) {
        jobject prev = (jobject)panel.outsideListenerVal.pointerValue;
        if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
        panel.outsideListenerVal = nil;
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panelPtr)
{
    (void)clazz;
    if (panelPtr == 0) return;
    NucleusTaoPopupPanel *panel = (__bridge_transfer NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;

    // Drop event callback global ref.
    NucleusTaoPopupContent *content = (NucleusTaoPopupContent *)panel.contentView;
    if (content != nil) {
        objc_setAssociatedObject(content, &kCallbackEnableKey, @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        NSValue *cbBox = objc_getAssociatedObject(content, &kCallbackKey);
        if (cbBox != nil) {
            jobject cb = (jobject)cbBox.pointerValue;
            if (cb != NULL) (*env)->DeleteGlobalRef(env, cb);
            objc_setAssociatedObject(content, &kCallbackKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        }
    }

    // Drop outside monitor + listener global ref.
    if (panel.outsideMonitor != nil) {
        [NSEvent removeMonitor:panel.outsideMonitor];
        panel.outsideMonitor = nil;
    }
    if (panel.outsideListenerVal != nil) {
        jobject prev = (jobject)panel.outsideListenerVal.pointerValue;
        if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
        panel.outsideListenerVal = nil;
    }

    NSWindow *parent = panel.parentHostWindow;
    if (parent != nil) [parent removeChildWindow:panel];
    [panel orderOut:nil];
    panel.contentView = nil;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridge_nativeContentNsView(
    JNIEnv *env, jclass clazz, jlong panelPtr)
{
    (void)env; (void)clazz;
    if (panelPtr == 0) return 0;
    NucleusTaoPopupPanel *panel = (__bridge NucleusTaoPopupPanel *)(void *)(uintptr_t)panelPtr;
    NSView *cv = panel.contentView;
    return (jlong)(uintptr_t)(__bridge void *)cv;
}

// native_view.m
//
// Two related JNI bridges, both for `NSView` ↔ Compose interop:
//
// 1. Generic native-subview interop (`nativeAddSubview` /
//    `nativeRemoveSubview` / `nativeSetSubviewFrame`) used by the
//    `NativeView` composable to mount user-provided NSViews
//    (`WKWebView`, `AVPlayerView`, …) as subviews of the Tao-owned host.
//
// 2. **Sibling overlay** NSView (`NucleusTaoNativeOverlayView`) used by
//    the `NativeView`'s `content` slot. Lives as a subview of the host
//    NSView, *positioned above* the user's native subview in z-order.
//    Hit-test is region-based: points outside any registered rect cause
//    `hitTest:` to return nil, and AppKit (correctly, for sibling
//    subviews of the same superview) walks down to the next subview at
//    the point — typically the user's WebView. So clicks on
//    "transparent" areas of the overlay reach the page underneath.
//
//    A separate NSPanel-based path (`popup_panel.m`) is used for
//    Compose `Popup` / `DropdownMenu` / context menus where full-window
//    semantics are desired. The NSPanel path can't deliver
//    per-subregion click-through (returning nil from `contentView.hitTest:`
//    on a top-level NSWindow's contentView does NOT fall through to the
//    next NSWindow), so we use a sibling NSView for in-window overlays
//    instead — different primitives for different jobs.
//
// Compiled into `libnucleus_tao_macos_native_view.dylib`. Loaded from
// `NativeLibraryLoader.load("nucleus_tao_macos_native_view")`.
//
// Threading: every entry point runs on the macOS main thread.

#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>
#include <jni.h>
#include <stdatomic.h>

static NSView *view_from_long(jlong ptr) {
    if (ptr == 0) return nil;
    return (__bridge NSView *)(void *)(uintptr_t)ptr;
}

// ── JVM caching for the overlay event callback ──────────────────────────

static JavaVM *sJVM = NULL;
static jclass sCallbackClass = NULL;
static jmethodID sOnPointerMethod = NULL;     // (IFFII)V
static jmethodID sOnScrollMethod = NULL;      // (FFFF)V
static jmethodID sOnKeyMethod = NULL;         // (IIII)V
static jmethodID sOnResignMethod = NULL;      // ()V
static atomic_bool sCacheInited = ATOMIC_VAR_INIT(false);

#define EVT_PTR_DOWN  1
#define EVT_PTR_UP    2
#define EVT_PTR_MOVE  3
#define EVT_KEY_DOWN  1
#define EVT_KEY_UP    2

static void ensureCallbackCache(JNIEnv *env, jobject sample) {
    if (atomic_load(&sCacheInited)) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (sample == NULL) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (local == NULL) return;
    sCallbackClass = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (sCallbackClass == NULL) return;
    sOnPointerMethod = (*env)->GetMethodID(env, sCallbackClass, "onPointerEvent",         "(IFFII)V");
    sOnScrollMethod  = (*env)->GetMethodID(env, sCallbackClass, "onScroll",               "(FFFF)V");
    sOnKeyMethod     = (*env)->GetMethodID(env, sCallbackClass, "onKeyEvent",             "(IIII)V");
    sOnResignMethod  = (*env)->GetMethodID(env, sCallbackClass, "onResignFirstResponder", "()V");
    if (sOnPointerMethod && sOnScrollMethod && sOnKeyMethod && sOnResignMethod) {
        atomic_store(&sCacheInited, true);
    }
}

static JNIEnv *attachThread(void) {
    if (sJVM == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) != JNI_OK) return NULL;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

// ── NucleusTaoNativeOverlayView ─────────────────────────────────────────

static const char kOverlayCallbackKey       = 1; // jobject global ref
static const char kOverlayCallbackEnableKey = 2; // BOOL
static const char kOverlayRegionDataKey     = 3; // NSData (float[count*4], top-left pixels)
static const char kOverlayRegionCountKey    = 4; // NSNumber<int>

@interface NucleusTaoNativeOverlayView : NSView
@end

@implementation NucleusTaoNativeOverlayView

- (BOOL)wantsUpdateLayer { return YES; }
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }

- (jobject)takeCallbackOrNil {
    NSNumber *enabled = objc_getAssociatedObject(self, &kOverlayCallbackEnableKey);
    if (enabled == nil || !enabled.boolValue) return NULL;
    NSValue *val = objc_getAssociatedObject(self, &kOverlayCallbackKey);
    return val != nil ? (jobject)val.pointerValue : NULL;
}

- (BOOL)pointInRegion:(NSPoint)windowPoint {
    NSNumber *countNum = objc_getAssociatedObject(self, &kOverlayRegionCountKey);
    NSData *dataObj    = objc_getAssociatedObject(self, &kOverlayRegionDataKey);
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
        if (px >= rx && py >= ry && px < rx + rw && py < ry + rh) return YES;
    }
    return NO;
}

/* AppKit hit-test for sibling subviews. Returning nil for points
 * outside any region lets AppKit fall through to siblings beneath us
 * (= the user's `WKWebView` / `AVPlayerView`). */
- (NSView *)hitTest:(NSPoint)point {
    NSNumber *enabled = objc_getAssociatedObject(self, &kOverlayCallbackEnableKey);
    if (enabled == nil || !enabled.boolValue) return nil; // not initialised → fully passthrough
    NSPoint windowPoint = [self convertPoint:point toView:nil];
    return [self pointInRegion:windowPoint] ? self : nil;
}

- (void)pixelsForEvent:(NSEvent *)event outX:(jfloat *)outX outY:(jfloat *)outY {
    NSPoint p = [self convertPoint:event.locationInWindow fromView:nil];
    CGFloat scale = self.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    *outX = (jfloat)(p.x * scale);
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

/* On click, become first responder of the host NSWindow so subsequent
 * `keyDown:` events route to us — the host stays the key window so the
 * window chrome doesn't go inactive. */
- (void)mouseDown:(NSEvent *)event {
    [self.window makeFirstResponder:self];
    [self dispatchPointer:event type:EVT_PTR_DOWN button:1];
}

- (BOOL)becomeFirstResponder {
    return [super becomeFirstResponder];
}

- (BOOL)resignFirstResponder {
    BOOL r = [super resignFirstResponder];
    if (r) {
        jobject cb = [self takeCallbackOrNil];
        if (cb != NULL && sOnResignMethod != NULL) {
            JNIEnv *env = attachThread();
            if (env != NULL) {
                (*env)->CallVoidMethod(env, cb, sOnResignMethod);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            }
        }
    }
    return r;
}
- (void)mouseUp:(NSEvent *)event         { [self dispatchPointer:event type:EVT_PTR_UP   button:1]; }
- (void)mouseMoved:(NSEvent *)event      { [self dispatchPointer:event type:EVT_PTR_MOVE button:0]; }
- (void)mouseDragged:(NSEvent *)event    { [self dispatchPointer:event type:EVT_PTR_MOVE button:1]; }
- (void)rightMouseDown:(NSEvent *)event  { [self.window makeFirstResponder:self]; [self dispatchPointer:event type:EVT_PTR_DOWN button:2]; }
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

/* Deliberately NOT overriding `keyDown:` / `keyUp:`. AppKit's
 * `event.keyCode` is the raw macOS virtual keycode (e.g. 123 for ←);
 * Compose JVM's `Key` constants map to AWT VK codes (e.g. VK_LEFT=37).
 * The translation lives in Tao's `keymap.rs`, which only runs in the
 * main host's key forwarding pipeline. Routing keys via that pipeline
 * (through `TaoComposeSceneHost.popupKeyHandlers` while the overlay is
 * first responder) gives us correctly-translated keys; doing it here
 * would fork the translation table and silently break arrow keys,
 * Enter, Tab, Backspace, modifier shortcuts, etc. */

- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    for (NSTrackingArea *ta in self.trackingAreas) [self removeTrackingArea:ta];
    NSTrackingAreaOptions opts = NSTrackingMouseMoved
                               | NSTrackingActiveAlways
                               | NSTrackingInVisibleRect;
    NSTrackingArea *ta = [[NSTrackingArea alloc] initWithRect:NSZeroRect
                                                      options:opts
                                                        owner:self
                                                     userInfo:nil];
    [self addTrackingArea:ta];
}

@end

/* ================================================================== */
/*  JNI exports — generic NSView interop                               */
/*  Class: NativeTaoMacOsNativeViewBridge                              */
/* ================================================================== */

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeAddSubview(
    JNIEnv *env, jclass clazz, jlong parentPtr, jlong childPtr)
{
    (void)env; (void)clazz;
    NSView *parent = view_from_long(parentPtr);
    NSView *child  = view_from_long(childPtr);
    if (parent == nil || child == nil) return;
    if (child.superview != nil) [child removeFromSuperview];
    [parent addSubview:child positioned:NSWindowAbove relativeTo:nil];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeRemoveSubview(
    JNIEnv *env, jclass clazz, jlong childPtr)
{
    (void)env; (void)clazz;
    NSView *child = view_from_long(childPtr);
    if (child == nil) return;
    [child removeFromSuperview];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeSetSubviewFrame(
    JNIEnv *env, jclass clazz,
    jlong parentPtr, jlong childPtr,
    jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    NSView *parent = view_from_long(parentPtr);
    NSView *child  = view_from_long(childPtr);
    if (parent == nil || child == nil) return;

    CGFloat scale = parent.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    CGFloat xPt = (CGFloat)xPx     / scale;
    CGFloat yPt = (CGFloat)yPx     / scale;
    CGFloat wPt = (CGFloat)widthPx / scale;
    CGFloat hPt = (CGFloat)heightPx / scale;

    CGFloat parentH = parent.frame.size.height;
    NSRect newFrame = parent.isFlipped
        ? NSMakeRect(xPt, yPt, wPt, hPt)
        : NSMakeRect(xPt, parentH - yPt - hPt, wPt, hPt);

    // Wrap in a CATransaction with disableActions so the layer-backed
    // subview (typically a WKWebView) doesn't kick off the default 0.25s
    // implicit position/bounds animation on every layout pass — that's
    // the dominant source of jank during a window live-resize, the layer
    // visibly chases the actual frame instead of snapping.
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [child setFrame:newFrame];
    [CATransaction commit];
}

/* Sets the CALayer cornerRadius / masksToBounds on the embedded subview
 * so a Compose host can clip a `WKWebView`/`AVPlayerView`/etc. with
 * rounded or fully-circular corners. `Modifier.clip()` on the Compose
 * side has no effect on AppKit subviews — that's the standard interop
 * limitation also seen in `AndroidView` / `UIKitView`. radiusPx is in
 * physical pixels and is capped here at min(w, h) / 2 so callers can
 * pass a huge value (or `Dp.Infinity` translated to `Float.MAX_VALUE`)
 * to mean "make it a circle".
 */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeSetSubviewCornerRadius(
    JNIEnv *env, jclass clazz, jlong parentPtr, jlong childPtr, jfloat radiusPx)
{
    (void)env; (void)clazz;
    NSView *parent = view_from_long(parentPtr);
    NSView *child  = view_from_long(childPtr);
    if (child == nil) return;

    CGFloat scale = parent.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    CGFloat radiusPt = (CGFloat)radiusPx / scale;
    if (radiusPt < 0) radiusPt = 0;

    CGFloat halfMin = MIN(child.bounds.size.width, child.bounds.size.height) / 2.0;
    if (radiusPt > halfMin) radiusPt = halfMin;

    child.wantsLayer = YES;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (radiusPt > 0) {
        child.layer.cornerRadius = radiusPt;
        child.layer.masksToBounds = YES;
    } else {
        child.layer.cornerRadius = 0;
        child.layer.masksToBounds = NO;
    }
    [CATransaction commit];
}

/* ================================================================== */
/*  JNI exports — sibling overlay NSView                              */
/*  Class: NativeTaoMacOsNativeViewBridge                              */
/* ================================================================== */

/* Allocates a `NucleusTaoNativeOverlayView` and attaches it as the
 * topmost subview of [parentNsView]. The caller hands the returned
 * pointer to `NativeMetalBridge.nativeAttachOverlay` to wire up a
 * transparent `CAMetalLayer`. The overlay's `hitTest:` is region-based:
 * points outside any rect registered via [nativeSetOverlayRegions] fall
 * through to the next sibling subview underneath (typically a
 * `WKWebView`). Without an event callback installed via
 * [nativeSetOverlayCallback], the overlay is fully passthrough. */
JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeCreateOverlay(
    JNIEnv *env, jclass clazz, jlong parentNsViewPtr)
{
    (void)env; (void)clazz;
    NSView *parent = view_from_long(parentNsViewPtr);
    if (parent == nil) return 0;
    NucleusTaoNativeOverlayView *overlay =
        [[NucleusTaoNativeOverlayView alloc] initWithFrame:parent.bounds];
    // Intentionally NO autoresizingMask. We previously set
    // `NSViewWidthSizable | NSViewHeightSizable` so the overlay would
    // visually track parent resizes "for free", but that creates two
    // independent frame writers competing during a window live-resize:
    //
    //   1. AppKit autoresize fires synchronously per-resize-tick and
    //      stretches the overlay's frame to match the parent's new
    //      bounds.
    //   2. Compose's `NativeView.onGloballyPositioned` → `setFrame`
    //      lands one frame later, committed inside the host's interop
    //      CATransaction.
    //
    // Each tick the overlay flips between AppKit's auto-stretched
    // frame and Compose's explicit frame — the visible result is the
    // overlay's drawn region jittering during every drag. Compose
    // owns the overlay's frame end-to-end; AppKit must stay out.
    overlay.wantsLayer = YES;
    [parent addSubview:overlay positioned:NSWindowAbove relativeTo:nil];
    void *retained = (__bridge_retained void *)overlay;
    return (jlong)(uintptr_t)retained;
}

/* Repositions the overlay inside its parent. Bounds in physical pixels,
 * top-left origin (matches Compose's `boundsInWindow`). */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeSetOverlayFrame(
    JNIEnv *env, jclass clazz, jlong overlayPtr,
    jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    if (overlayPtr == 0) return;
    NucleusTaoNativeOverlayView *overlay =
        (__bridge NucleusTaoNativeOverlayView *)(void *)(uintptr_t)overlayPtr;
    NSView *parent = overlay.superview;
    if (parent == nil) return;
    CGFloat scale = parent.window.backingScaleFactor;
    if (scale <= 0) scale = 1.0;
    CGFloat xPt = (CGFloat)xPx     / scale;
    CGFloat yPt = (CGFloat)yPx     / scale;
    CGFloat wPt = (CGFloat)widthPx / scale;
    CGFloat hPt = (CGFloat)heightPx / scale;
    CGFloat parentH = parent.frame.size.height;
    NSRect newFrame = parent.isFlipped
        ? NSMakeRect(xPt, yPt, wPt, hPt)
        : NSMakeRect(xPt, parentH - yPt - hPt, wPt, hPt);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    [overlay setFrame:newFrame];
    [CATransaction commit];
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeSetOverlayCallback(
    JNIEnv *env, jclass clazz, jlong overlayPtr, jobject callback)
{
    (void)clazz;
    if (overlayPtr == 0) return;
    NucleusTaoNativeOverlayView *overlay =
        (__bridge NucleusTaoNativeOverlayView *)(void *)(uintptr_t)overlayPtr;
    NSValue *prev = objc_getAssociatedObject(overlay, &kOverlayCallbackKey);
    if (prev != nil) {
        jobject prevRef = (jobject)prev.pointerValue;
        if (prevRef != NULL) (*env)->DeleteGlobalRef(env, prevRef);
        objc_setAssociatedObject(overlay, &kOverlayCallbackKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    if (callback == NULL) {
        objc_setAssociatedObject(overlay, &kOverlayCallbackEnableKey, @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        return;
    }
    ensureCallbackCache(env, callback);
    jobject globalRef = (*env)->NewGlobalRef(env, callback);
    objc_setAssociatedObject(overlay, &kOverlayCallbackKey,
        [NSValue valueWithPointer:globalRef], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(overlay, &kOverlayCallbackEnableKey, @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeSetOverlayRegions(
    JNIEnv *env, jclass clazz, jlong overlayPtr, jfloatArray rectsPx, jint count)
{
    (void)clazz;
    if (overlayPtr == 0) return;
    NucleusTaoNativeOverlayView *overlay =
        (__bridge NucleusTaoNativeOverlayView *)(void *)(uintptr_t)overlayPtr;
    if (count <= 0 || rectsPx == NULL) {
        objc_setAssociatedObject(overlay, &kOverlayRegionCountKey, @0, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        objc_setAssociatedObject(overlay, &kOverlayRegionDataKey,  nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        return;
    }
    jsize len = (*env)->GetArrayLength(env, rectsPx);
    int safeCount = (int)(len / 4);
    if (safeCount > count) safeCount = count;
    NSMutableData *buf = [NSMutableData dataWithLength:(NSUInteger)(safeCount * 4 * sizeof(float))];
    (*env)->GetFloatArrayRegion(env, rectsPx, 0, safeCount * 4, (jfloat *)buf.mutableBytes);
    objc_setAssociatedObject(overlay, &kOverlayRegionCountKey,
        [NSNumber numberWithInt:safeCount], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    objc_setAssociatedObject(overlay, &kOverlayRegionDataKey,
        buf, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

/* Returns YES if the overlay NSView is the current first responder of
 * its host NSWindow. Used by `NativeViewOverlayController` to decide
 * whether to consume key events from the host's pre-existing Tao
 * key-forwarding pipeline. */
JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeIsFirstResponder(
    JNIEnv *env, jclass clazz, jlong overlayPtr)
{
    (void)env; (void)clazz;
    if (overlayPtr == 0) return JNI_FALSE;
    NSView *overlay = (__bridge NSView *)(void *)(uintptr_t)overlayPtr;
    NSWindow *win = overlay.window;
    if (win == nil) return JNI_FALSE;
    return win.firstResponder == overlay ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoMacOsNativeViewBridge_nativeReleaseOverlay(
    JNIEnv *env, jclass clazz, jlong overlayPtr)
{
    (void)clazz;
    if (overlayPtr == 0) return;
    NucleusTaoNativeOverlayView *overlay =
        (__bridge_transfer NucleusTaoNativeOverlayView *)(void *)(uintptr_t)overlayPtr;
    objc_setAssociatedObject(overlay, &kOverlayCallbackEnableKey, @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    NSValue *cbBox = objc_getAssociatedObject(overlay, &kOverlayCallbackKey);
    if (cbBox != nil) {
        jobject cb = (jobject)cbBox.pointerValue;
        if (cb != NULL) (*env)->DeleteGlobalRef(env, cb);
        objc_setAssociatedObject(overlay, &kOverlayCallbackKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    [overlay removeFromSuperview];
}

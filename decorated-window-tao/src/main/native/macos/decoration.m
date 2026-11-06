// decoration.m
//
// JNI bridge for Tao-window dialog/owner support on macOS. Mirrors the
// `windows/nucleus_tao_windows_deco.c` API surface used by `DecoratedDialog`,
// minus the WndProc-subclass machinery (macOS handles non-client decoration via
// the NSWindow style mask, not a message-handler subclass).
//
// Provides:
//   - nativeSetOwner: wires a child NSWindow to a parent via
//     `[parent addChildWindow:child ordered:NSWindowAbove]`. macOS gives child
//     windows the same semantics we want from a non-modal JDialog: stays above
//     the parent in z-order, follows it across spaces / minimisation, closes
//     with it; the parent stays usable. Pass `0` for the owner to clear.
//   - nativeGetWindowRect: returns the NSWindow's outer frame in physical
//     pixels using a top-left origin (matching Win32 `GetWindowRect`), so the
//     Kotlin centring math is the same on every platform.
//   - nativeGetContentRect: same convention, but for the view's own rect on
//     screen — the origin window-rooted Compose coordinates are relative to,
//     which the #569 popup screen clamp converts through.
//   - nativeGetPrimaryMonitorWorkArea: returns NSScreen.visibleFrame for the
//     primary screen, in physical pixels with top-left origin (matches the
//     Windows `SystemParametersInfo(SPI_GETWORKAREA)` shape).
//   - nativeGetPrimaryMonitorScaleMilli: backingScaleFactor of the primary
//     screen as `(scale * 1000)`.
//   - nativeGetMonitors: one tab-separated descriptor per NSScreen (id, name,
//     frame, visibleFrame, scale, primary flag) for the multi-monitor API.
//   - nativeSetHiddenFromDock: hides/shows the app's Dock icon by switching the
//     shared NSApplication's activation policy (app-wide, macOS only).
//
// Shipped as a separate `libnucleus_tao_macos_deco.dylib` so its JNI exports
// survive the Rust crate's release-mode `strip = "symbols"`. Loaded from
// `NativeLibraryLoader.load("nucleus_tao_macos_deco")`.
//
// Threading: every entry point runs on the macOS main thread (= Tao event-loop
// thread = Compose dispatcher thread). The functions touch AppKit objects, so
// callers must respect that.

#import <Cocoa/Cocoa.h>
#include <jni.h>
#include <math.h>

// Resolves the primary screen — the one that owns the (0,0) origin of AppKit's
// global screen coordinate space. `[NSScreen mainScreen]` follows the key
// window, which is the wrong reference for converting bottom-up Y to top-down
// Y; `[NSScreen screens][0]` is the canonical primary.
static NSScreen *primary_screen(void) {
    NSArray<NSScreen *> *screens = [NSScreen screens];
    if (screens.count > 0) return screens[0];
    return [NSScreen mainScreen];
}

// Converts a rect expressed in AppKit's bottom-up screen coords into a top-left
// origin rect (Tao / winit convention used by `setOuterPosition`). Y is
// referenced to the primary screen's height, not the screen the window happens
// to live on, so rects on secondary monitors retain their relative offset.
static NSRect to_top_left_rect(NSRect r) {
    NSScreen *primary = primary_screen();
    CGFloat primaryH = primary ? primary.frame.size.height : r.size.height;
    NSRect out = r;
    out.origin.y = primaryH - (r.origin.y + r.size.height);
    return out;
}

static jlongArray make_rect_array(JNIEnv *env, NSRect r, CGFloat scale) {
    if (scale <= 0) scale = 1.0;
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4] = {
        (jlong)llround(r.origin.x * scale),
        (jlong)llround(r.origin.y * scale),
        (jlong)llround(r.size.width * scale),
        (jlong)llround(r.size.height * scale),
    };
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: dev.nucleusframework.window.tao                 */
/*  Class:   NativeTaoMacOsDecoBridge                                  */
/* ================================================================== */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeSetOwner(
    JNIEnv *env, jclass clazz, jlong childViewLong, jlong ownerViewLong, jboolean centerOnOwner)
{
    (void)env; (void)clazz;
    if (!childViewLong) return;

    NSView *childView = (__bridge NSView *)(void *)(uintptr_t)childViewLong;
    NSWindow *child = childView.window;
    if (!child) return;

    // Always tear down the previous owner first — `addChildWindow:` is not
    // idempotent and chaining without removing leaves AppKit confused about the
    // child's parent (visible as the dialog refusing to come forward when the
    // owner is re-focused).
    NSWindow *previous = child.parentWindow;
    if (previous) {
        [previous removeChildWindow:child];
    }

    if (ownerViewLong == 0) return;

    NSView *ownerView = (__bridge NSView *)(void *)(uintptr_t)ownerViewLong;
    NSWindow *owner = ownerView.window;
    if (!owner) return;

    // A dialog must never be a *primary* fullscreen window. `nativeConfigureChrome`
    // sets `FullScreenPrimary` on every Tao window (so the green button can take
    // the main window fullscreen), but when the owner is already fullscreen,
    // `addChildWindow:` pulls the child into the owner's Space — and a
    // FullScreenPrimary child gets promoted to its own fullscreen there, which is
    // the "settings dialog also goes fullscreen / parent turns black on close"
    // bug. The two flags are mutually exclusive; swapping to
    // FullScreenAuxiliary makes the dialog float *over* the owner's fullscreen
    // content in the same Space, matching AppKit's auxiliary-window behaviour
    // (and `decorated-window-jni`, whose AWT JDialog is never FullScreenPrimary).
    NSWindowCollectionBehavior cb = child.collectionBehavior;
    cb &= ~NSWindowCollectionBehaviorFullScreenPrimary;
    cb |= NSWindowCollectionBehaviorFullScreenAuxiliary;
    child.collectionBehavior = cb;

    // Critical: `addChildWindow:` orders an invisible child front, so it
    // becomes visible *at its current frame*. Tao creates windows at a
    // default origin, so without pre-positioning we'd see the dialog flash
    // at e.g. (0,0) before the JVM-side position effect runs. AppKit's
    // `setFrameOrigin:` is synchronous and uses the same NSWindow as
    // addChildWindow:, so they form an atomic visible-at-centered sequence.
    if (centerOnOwner) {
        NSRect of = owner.frame;
        NSRect cf = child.frame;
        NSPoint origin = NSMakePoint(
            of.origin.x + (of.size.width - cf.size.width) / 2.0,
            of.origin.y + (of.size.height - cf.size.height) / 2.0);
        [child setFrameOrigin:origin];
    }

    [owner addChildWindow:child ordered:NSWindowAbove];
    // Re-stack explicitly: a zoom / fullscreen transition re-orders the owner
    // and can leave an already-attached child behind it; `addChildWindow:`
    // on its own does not always move a visible child back above.
    [child orderWindow:NSWindowAbove relativeTo:owner.windowNumber];
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeGetWindowRect(
    JNIEnv *env, jclass clazz, jlong nsViewLong)
{
    (void)clazz;
    if (!nsViewLong) return NULL;
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewLong;
    NSWindow *window = view.window;
    if (!window) return NULL;

    NSRect topLeft = to_top_left_rect(window.frame);
    return make_rect_array(env, topLeft, window.backingScaleFactor);
}

/* Returns the *content* rect of the view's window — the rect window-rooted
 * Compose coordinates are relative to — as `[x, y, width, height]` in physical
 * pixels with a top-left origin, i.e. the same space nativeGetWindowRect and
 * nativeGetMonitors report in.
 *
 * Distinct from nativeGetWindowRect: a window with a native title bar has its
 * content origin below the frame origin, and the #569 popup screen clamp is
 * only as accurate as this offset. `convertRectToScreen:` is asked for the
 * view's own bounds rather than the window's contentLayoutRect so a nested
 * overlay view answers for itself. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeGetContentRect(
    JNIEnv *env, jclass clazz, jlong nsViewLong)
{
    (void)clazz;
    if (!nsViewLong) return NULL;
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewLong;
    NSWindow *window = view.window;
    if (!window) return NULL;

    NSRect inWindow = [view convertRect:view.bounds toView:nil];
    NSRect onScreen = [window convertRectToScreen:inWindow];
    NSRect topLeft = to_top_left_rect(onScreen);
    return make_rect_array(env, topLeft, window.backingScaleFactor);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeGetPrimaryMonitorWorkArea(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    NSScreen *screen = primary_screen();
    if (!screen) return NULL;

    NSRect topLeft = to_top_left_rect(screen.visibleFrame);
    return make_rect_array(env, topLeft, screen.backingScaleFactor);
}

/* Returns one tab-separated descriptor per screen, in `[NSScreen screens]`
 * order (index 0 is the primary):
 *   id \t name \t x \t y \t w \t h \t workX \t workY \t workW \t workH
 *      \t scaleMilli \t primary
 * Geometry is physical pixels with a top-left origin — same convention as
 * nativeGetPrimaryMonitorWorkArea, so the JVM side needs no per-platform math.
 * `id` is derived from the CGDirectDisplayID, which is stable for as long as
 * the display stays attached. */
JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeGetMonitors(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    NSArray<NSScreen *> *screens = [NSScreen screens];
    if (screens.count == 0) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray arr =
        (*env)->NewObjectArray(env, (jsize)screens.count, stringClass, NULL);
    if (!arr) return NULL;

    for (NSUInteger i = 0; i < screens.count; i++) {
        NSScreen *screen = screens[i];
        CGFloat scale = screen.backingScaleFactor;
        if (scale <= 0) scale = 1.0;

        NSRect bounds = to_top_left_rect(screen.frame);
        NSRect work = to_top_left_rect(screen.visibleFrame);

        NSNumber *displayId = screen.deviceDescription[@"NSScreenNumber"];
        NSString *identifier = displayId
            ? [NSString stringWithFormat:@"display-%u", displayId.unsignedIntValue]
            : [NSString stringWithFormat:@"screen-%lu", (unsigned long)i];
        NSString *name = screen.localizedName.length > 0
            ? screen.localizedName
            : identifier;

        NSString *row = [NSString stringWithFormat:
            @"%@\t%@\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\t%ld\t%d",
            [identifier stringByReplacingOccurrencesOfString:@"\t" withString:@" "],
            [name stringByReplacingOccurrencesOfString:@"\t" withString:@" "],
            (long)llround(bounds.origin.x * scale),
            (long)llround(bounds.origin.y * scale),
            (long)llround(bounds.size.width * scale),
            (long)llround(bounds.size.height * scale),
            (long)llround(work.origin.x * scale),
            (long)llround(work.origin.y * scale),
            (long)llround(work.size.width * scale),
            (long)llround(work.size.height * scale),
            (long)llround(scale * 1000.0),
            (i == 0) ? 1 : 0];

        jstring jrow = (*env)->NewStringUTF(env, row.UTF8String);
        if (!jrow) return NULL;
        (*env)->SetObjectArrayElement(env, arr, (jsize)i, jrow);
        (*env)->DeleteLocalRef(env, jrow);
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeGetPrimaryMonitorScaleMilli(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    NSScreen *screen = primary_screen();
    if (!screen) return 1000;
    CGFloat scale = screen.backingScaleFactor;
    if (scale <= 0) return 1000;
    return (jint)llround(scale * 1000.0);
}

/* Sets the NSWindow's content-area minimum size and immediately enlarges the
 * window (preserving the top-left corner) when its current content is smaller.
 *
 * Mirrors Tao's `set_min_inner_size`, but bypasses Tao's UserEvent queue so
 * `DecoratedWindow` can apply the constraint *synchronously inside*
 * `onWindowReady` — by the time the LE position effect runs (one pump cycle
 * later), `[NSWindow frame]` already reflects the post-min-size size, so the
 * `WindowPosition.Aligned` centring math doesn't centre against the stale
 * `state.size` (which would put the window off-screen-right when
 * `rememberWindowState()`'s default 800×600 is smaller than the requested
 * `minimumSize`).
 *
 * Sizes are in **points** (logical pixels). Pass non-positive values to
 * leave the corresponding dimension unchanged. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeApplyContentMinSize(
    JNIEnv *env, jclass clazz, jlong nsViewLong, jdouble widthPts, jdouble heightPts)
{
    (void)env; (void)clazz;
    if (!nsViewLong) return;
    NSView *view = (__bridge NSView *)(void *)(uintptr_t)nsViewLong;
    NSWindow *window = view.window;
    if (!window) return;

    CGFloat minW = widthPts > 0 ? (CGFloat)widthPts : window.contentMinSize.width;
    CGFloat minH = heightPts > 0 ? (CGFloat)heightPts : window.contentMinSize.height;
    [window setContentMinSize:NSMakeSize(minW, minH)];

    // Convert content-min to frame-min via `frameRectForContentRect:` so the
    // resize math accounts for any chrome (title bar, borders) the window
    // might have. With NSWindowStyleMaskFullSizeContentView (set by
    // `nativeConfigureChrome`), content rect = frame rect — chrome
    // contribution is 0 — but the conversion stays correct for both cases.
    NSRect contentRect = NSMakeRect(0, 0, minW, minH);
    NSRect minFrameRect = [window frameRectForContentRect:contentRect];
    CGFloat minFrameW = minFrameRect.size.width;
    CGFloat minFrameH = minFrameRect.size.height;

    NSRect currentFrame = window.frame;
    BOOL needsResize = NO;
    if (currentFrame.size.width < minFrameW) {
        currentFrame.size.width = minFrameW;
        needsResize = YES;
    }
    if (currentFrame.size.height < minFrameH) {
        // macOS frames have a bottom-left origin; growing the height upward
        // (which is what we want — keep the visible top edge fixed) means
        // shifting `origin.y` down by the delta.
        currentFrame.origin.y -= (minFrameH - currentFrame.size.height);
        currentFrame.size.height = minFrameH;
        needsResize = YES;
    }
    if (needsResize) {
        // `display:NO` skips the immediate redraw — the window is hidden at
        // this point (DecoratedWindow opens with `visible = false`), so we
        // don't need it. The frame is updated synchronously regardless.
        [window setFrame:currentFrame display:NO];
    }
}

/* Hides or restores the application's Dock icon by switching the shared
 * NSApplication's activation policy.
 *
 *   hidden == YES → NSApplicationActivationPolicyAccessory: no Dock icon and no
 *                   menu bar, but the app can still show windows and take focus
 *                   (unlike Prohibited, which forbids activation entirely).
 *   hidden == NO  → NSApplicationActivationPolicyRegular: standard Dock icon +
 *                   menu bar.
 *
 * App-wide: activation policy is a property of NSApplication, not of an
 * individual window, so the last window to apply this wins. Switching to
 * Accessory can drop the app out of the active state, so re-activate to keep
 * the visible window key. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoMacOsDecoBridge_nativeSetHiddenFromDock(
    JNIEnv *env, jclass clazz, jboolean hidden)
{
    (void)env; (void)clazz;
    NSApplication *app = [NSApplication sharedApplication];
    NSApplicationActivationPolicy policy =
        hidden ? NSApplicationActivationPolicyAccessory
               : NSApplicationActivationPolicyRegular;
    if (app.activationPolicy == policy) return;
    [app setActivationPolicy:policy];
    if (hidden) {
        [app activateIgnoringOtherApps:YES];
    }
}

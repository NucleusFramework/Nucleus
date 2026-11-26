// window_drag.m
//
// Mirrors `decorated-window-jni`'s `JniMacTitleBar.m` window-drag helper:
// captures the last NSLeftMouseDown event globally, then defers
// `performWindowDragWithEvent:` to the next main-runloop tick via
// `dispatch_async`.
//
// Two reasons for the indirection (Tao's built-in `Window::drag_window()`
// does neither):
//  1. **Saved mouseDown** — `performWindowDragWithEvent:` requires the event
//     that initiated the drag (per Apple docs). When Compose calls into us
//     during a Move event, `NSApp.currentEvent` is a NSLeftMouseDragged, and
//     AppKit may misbehave (Tao 0.35 currently synthesizes a fake LeftMouseDown
//     in that case, which loses pressure/click-count fidelity).
//  2. **dispatch_async deferral** — `performWindowDragWithEvent:` is
//     synchronous and enters AppKit's modal event-tracking loop immediately.
//     If we call it inside the Compose pointer-input handler, child gesture
//     detectors (e.g. reorderable's tab drag) never see the next pointer
//     event because the modal loop has already taken over. Posting the call
//     to the next runloop tick lets Compose's gesture machinery run first;
//     if a child has already consumed the gesture the subsequent move events
//     will simply not reach the title-bar handler that asked for the drag.

#import <Cocoa/Cocoa.h>
#include <stdatomic.h>

static id sMouseDownMonitor = nil;
static NSEvent *sLastMouseDownEvent = nil;
static NSLock *sLock = nil;

// A child window (a dialog attached with `addChildWindow:`) detached from its
// parent for the length of a drag. AppKit moves a child window itself, at
// ~14 Hz, where WindowServer moves a top-level one with every mouse event: the
// dialog lagged far behind the pointer, with the native title-bar drag too.
// Main thread only.
static __weak NSWindow *sDetachedChild = nil;
static __weak NSWindow *sDetachedParent = nil;

static void reattach_dragged_child(void) {
    NSWindow *child = sDetachedChild;
    NSWindow *parent = sDetachedParent;
    sDetachedChild = nil;
    sDetachedParent = nil;
    // Closed meanwhile, or given another owner: leave it as it is.
    if (child == nil || parent == nil || child.parentWindow != nil) return;
    if (!child.isVisible || !parent.isVisible) return;
    [parent addChildWindow:child ordered:NSWindowAbove];
}

static void ensure_lock(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{ sLock = [[NSLock alloc] init]; });
}

// Installs a global NSEvent local monitor that latches every NSLeftMouseDown
// and ends a child window's detached drag on the NSLeftMouseUp (AppKit still
// delivers it after the WindowServer-driven move).
// Idempotent — safe to call from any thread.
void nucleus_tao_install_drag_monitor(void) {
    ensure_lock();
    if (sMouseDownMonitor != nil) return;
    if (![NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            nucleus_tao_install_drag_monitor();
        });
        return;
    }
    sMouseDownMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:NSEventMaskLeftMouseDown | NSEventMaskLeftMouseUp
        handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            // A press also ends it, should the release have been lost.
            reattach_dragged_child();
            if (event.type == NSEventTypeLeftMouseDown) {
                [sLock lock];
                sLastMouseDownEvent = event;
                [sLock unlock];
            }
            return event;
        }];
}

// Posts `performWindowDragWithEvent:` to the next main-runloop tick using the
// most recently captured NSLeftMouseDown event. Falls back to the current
// event if no mouseDown has been captured yet.
int nucleus_tao_begin_touch_window_drag(long ns_window_ptr); // direct_touch.m

void nucleus_tao_start_window_drag(long ns_window_ptr) {
    if (ns_window_ptr == 0) return;
    // A finger on the title bar (#892): the touch forwarder moves the window.
    if ([NSThread isMainThread] && nucleus_tao_begin_touch_window_drag(ns_window_ptr)) return;
    ensure_lock();

    [sLock lock];
    NSEvent *savedEvent = sLastMouseDownEvent;
    sLastMouseDownEvent = nil;
    [sLock unlock];

    if (savedEvent == nil) {
        savedEvent = [NSApp currentEvent];
        if (savedEvent == nil) return;
    }

    void *rawPtr = (void *)ns_window_ptr;
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            NSWindow *w = nil;
            for (NSWindow *win in [NSApp windows]) {
                if ((__bridge void *)win == rawPtr) { w = win; break; }
            }
            if (!w) return;
            // `performWindowDragWithEvent:` requires `isMovable == YES` on
            // macOS < 26, but we keep the window non-movable at rest so
            // AppKit's title-bar machinery doesn't intercept clicks meant
            // for our Compose content. Toggle around the call. Mirrors
            // JNI's `JniMacTitleBar.m::nativeStartWindowDrag` path.
            BOOL needsRestore = ![w isMovable];
            if (needsRestore) [w setMovable:YES];
            // Drag a child window as a top-level one; the mouse-up monitor
            // puts it back under its parent (see `sDetachedChild`).
            NSWindow *parent = w.parentWindow;
            if (parent != nil && sMouseDownMonitor != nil) {
                reattach_dragged_child();
                [parent removeChildWindow:w];
                sDetachedChild = w;
                sDetachedParent = parent;
            }
            [w performWindowDragWithEvent:savedEvent];
            // Already released (a quick flick, or a drag AppKit ran to its end
            // in here, swallowing the mouse-up the monitor waits for).
            if (([NSEvent pressedMouseButtons] & 1) == 0) reattach_dragged_child();
            if (needsRestore) [w setMovable:NO];
        }
    });
}

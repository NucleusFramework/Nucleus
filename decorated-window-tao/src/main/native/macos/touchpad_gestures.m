// touchpad_gestures.m
//
// Captures NSEventTypeMagnify / NSEventTypeRotate / NSEventTypeSmartMagnify
// via a global NSEvent local monitor and forwards them to a C callback
// installed by Rust. Tao 0.35 does not surface these events at all
// (`WindowEvent` only exposes `TouchpadPressure`), so we intercept them
// before AppKit dispatches them down the responder chain.
//
// The Rust side then synthesizes two `ComposeScenePointer` Touch points on the
// JVM side so that `detectTransformGestures` reacts to pinch-zoom and rotate
// uniformly across platforms — see TOUCH_PLAN.md, Phase 3.
//
// Threading: the monitor block runs on the AppKit main thread (where Tao's
// event loop already lives), so the callback fires on the same thread that
// owns the JVM-side Compose scene.

#import <Cocoa/Cocoa.h>

// Wire enums mirrored on the Rust / JVM side.
//   kind:  0 = magnify, 1 = rotate, 2 = smart_magnify
//   phase: 0 = began,   1 = changed, 2 = ended, 3 = cancelled
typedef void (*nucleus_tao_trackpad_gesture_cb)(
    long ns_window_ptr,
    int kind,
    int phase,
    double x_px,
    double y_px,
    double value
);

static id sGestureMonitor = nil;
static nucleus_tao_trackpad_gesture_cb sCallback = NULL;

void nucleus_tao_register_trackpad_gesture_callback(nucleus_tao_trackpad_gesture_cb cb) {
    sCallback = cb;
}

static int phase_from_event(NSEvent *event) {
    // Magnify / rotate carry NSEventPhase. Smart-magnify uses NSEventPhaseNone
    // (one-shot) — caller upgrades it to a synthetic Changed.
    NSEventPhase p = event.phase;
    if (p & NSEventPhaseBegan) return 0;
    if (p & NSEventPhaseChanged) return 1;
    if (p & NSEventPhaseEnded) return 2;
    if (p & NSEventPhaseCancelled) return 3;
    return 1;
}

void nucleus_tao_install_trackpad_gesture_monitor(void) {
    if (sGestureMonitor != nil) return;
    if (![NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            nucleus_tao_install_trackpad_gesture_monitor();
        });
        return;
    }
    NSEventMask mask = NSEventMaskMagnify
                     | NSEventMaskRotate
                     | NSEventMaskSmartMagnify;
    sGestureMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:mask
        handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            if (sCallback == NULL) return event;

            NSWindow *win = event.window;
            if (win == nil) return event;
            NSView *view = win.contentView;
            if (view == nil) return event;

            // Convert event location → view-local pixels with a top-left
            // origin so the position matches the rest of the Tao pipeline
            // (CursorMoved is also reported top-left in physical pixels).
            NSPoint windowPoint = event.locationInWindow;
            NSPoint local = [view convertPoint:windowPoint fromView:nil];
            CGFloat scale = win.backingScaleFactor;
            CGFloat localY = view.bounds.size.height - local.y;
            double xPx = local.x * scale;
            double yPx = localY * scale;

            int kind;
            int phase;
            double value = 0.0;
            switch (event.type) {
                case NSEventTypeMagnify:
                    kind = 0;
                    phase = phase_from_event(event);
                    value = event.magnification;
                    break;
                case NSEventTypeRotate:
                    kind = 1;
                    phase = phase_from_event(event);
                    value = event.rotation;
                    break;
                case NSEventTypeSmartMagnify:
                    // Single one-shot event with phase == NSEventPhaseNone.
                    // Kotlin wrapper will expand it to Began/Changed/Ended.
                    kind = 2;
                    phase = 1;
                    value = 0.0;
                    break;
                default:
                    return event;
            }
            sCallback((long)(__bridge void *)win, kind, phase, xPx, yPx, value);
            return event;
        }];
}

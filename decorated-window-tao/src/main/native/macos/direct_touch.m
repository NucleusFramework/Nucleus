// direct_touch.m
//
// Direct touch (touchscreen, Sidecar) on macOS 27+ (#892). AppKit hands
// direct touches only to gesture recognizers: they never reach the responder
// chain, `sendEvent:` or NSEvent monitors. Without a recognizer of its own a
// view gets AppKit's mouse emulation instead (tap → click, pan → scroll, long
// press → right click, pinch → magnify + rotate), which loses multi-touch and
// turns every finger drag into a scroll.
//
// So every tao content view whose host consumes touch gets one
// `NucleusTaoTouchForwarder` (`NativeTaoMacOsTouchBridge.nativeAttachDirectTouch`),
// a contact observer in the spirit of Blender's `BlenderContactObserver`: it
// follows every direct contact and forwards each one through a C callback,
// which the Rust side turns into `EventCallback.onTouchInput` (the Windows
// wire), so the Compose host emits real PointerType.Touch pointers.
//
// Its presence is also what turns the emulation off for the view: AppKit only
// injects the emulation recognizers into a hierarchy that has no direct-touch
// recognizer of its own, so no `NSIsTouchNative` is needed (and an unbundled
// `gradlew run` has no Info.plist to carry it anyway). Views it does not cover
// keep the emulation: a NativeView child (the forwarder declines touches that
// hit-test into one) and the NSPanel popups.
//
// Mouse and trackpad events are never attempted, so they stay in the responder
// stream exactly as before. On systems without direct touch the recognizer is
// inert: AppKit never sends it a touch.
//
// Threading: AppKit drives recognizers on the main thread, where tao's event
// loop lives, like the trackpad gesture monitor.

#import <Cocoa/Cocoa.h>
#import <objc/message.h>

// Wire mirrored on the Rust / JVM side (`TaoTouchEvent`).
//   phase: 0 = press, 1 = move, 2 = release, 3 = cancel
// (x_px, y_px) are view-local physical pixels, top-left origin.
typedef void (*nucleus_tao_direct_touch_cb)(
    long ns_window_ptr,
    int phase,
    long long id,
    double x_px,
    double y_px
);

static nucleus_tao_direct_touch_cb sTouchCallback = NULL;

// Posted when a finger lands on a tao window: popups dismiss on an outside
// press, and their monitors only see mouse events (popup_panel.m).
NSString *const NucleusTaoDirectTouchBeganNotification = @"NucleusTaoDirectTouchBegan";

void nucleus_tao_register_direct_touch_callback(nucleus_tao_direct_touch_cb cb) {
    sTouchCallback = cb;
}

@interface NucleusTaoTouchForwarder : NSGestureRecognizer <NSGestureRecognizerDelegate>
@end

// Every live forwarder, so a window drag request can find its window's.
static NSHashTable<NucleusTaoTouchForwarder *> *sForwarders = nil;

@implementation NucleusTaoTouchForwarder {
    // NSTouch.identity → the pointer id given to the JVM. An identity is only
    // valid while its finger is down, so ids are never reused within a
    // session of contacts and a lifted finger frees its entry.
    NSMutableDictionary<id, NSNumber *> *_ids;
    long long _nextId;
    NSPoint _lastLocation;
    // The contact that landed last, and the screen point it is at.
    id<NSObject, NSCopying> _lastIdentity;
    NSPoint _lastScreenPoint;
    // Title-bar drag by touch: the contact the window follows, where it was
    // on screen and where the window was when the drag began.
    id<NSObject, NSCopying> _dragIdentity;
    NSPoint _dragStartScreen;
    NSPoint _dragStartOrigin;
}

- (instancetype)init {
    self = [super initWithTarget:nil action:nil];
    if (self) {
        _ids = [NSMutableDictionary dictionary];
        // Clear of the mouse (0) and of the trackpad rotation contacts (0xA001/2).
        _nextId = 0x10000;
        self.allowedTouchTypes = NSTouchTypeMaskDirect;
        self.delaysPrimaryMouseButtonEvents = NO;
        self.delaysSecondaryMouseButtonEvents = NO;
        self.delaysOtherMouseButtonEvents = NO;
        self.delegate = self;
        if (sForwarders == nil) sForwarders = [NSHashTable weakObjectsHashTable];
        [sForwarders addObject:self];
    }
    return self;
}

// `performWindowDragWithEvent:` ignores a touch event (it returns at once,
// window unmoved, on macOS 27), so a title bar dragged by a finger moves the
// window here: it follows the contact that landed last, in screen
// coordinates, since its window-local position moves with the window.
- (BOOL)beginWindowDrag {
    if (_lastIdentity == nil || _ids[_lastIdentity] == nil) return NO;
    NSWindow *window = self.view.window;
    if (window == nil) return NO;
    _dragIdentity = _lastIdentity;
    _dragStartScreen = _lastScreenPoint;
    _dragStartOrigin = window.frame.origin;
    return YES;
}

- (void)followDrag:(NSTouch *)touch screenPoint:(NSPoint)screenPoint {
    if (_dragIdentity == nil || ![touch.identity isEqual:_dragIdentity]) return;
    NSWindow *window = self.view.window;
    [window setFrameOrigin:NSMakePoint(_dragStartOrigin.x + screenPoint.x - _dragStartScreen.x,
                                       _dragStartOrigin.y + screenPoint.y - _dragStartScreen.y)];
}

- (void)forward:(NSEvent *)event phase:(NSTouchPhase)touchPhase wire:(int)wire {
    NSView *view = self.view;
    NSWindow *window = view.window;
    if (view == nil || window == nil) return;
    CGFloat scale = window.backingScaleFactor;
    CGFloat height = view.bounds.size.height;
    for (NSTouch *touch in [event touchesMatchingPhase:touchPhase inView:view]) {
        if (touch.type != NSTouchTypeDirect) continue;
        id<NSObject, NSCopying> identity = touch.identity;
        NSNumber *pointerId = _ids[identity];
        if (pointerId == nil) {
            if (wire != 0) continue; // declined at begin (a NativeView), or already gone
            pointerId = @(_nextId++);
            _ids[identity] = pointerId;
        }
        NSPoint local = [touch locationInView:view];
        NSPoint screenPoint = [window convertPointToScreen:[view convertPoint:local toView:nil]];
        _lastLocation = local;
        if (wire == 0) _lastIdentity = identity;
        if ([identity isEqual:_lastIdentity]) _lastScreenPoint = screenPoint;
        if (wire == 1) {
            [self followDrag:touch screenPoint:screenPoint];
            // The window moved under the finger: report where it now is.
            local = [touch locationInView:view];
        }
        if (wire == 2 || wire == 3) {
            [_ids removeObjectForKey:identity];
            if ([identity isEqual:_dragIdentity]) _dragIdentity = nil;
        }
        if (sTouchCallback == NULL) continue;
        double yTop = view.isFlipped ? local.y : height - local.y;
        sTouchCallback((long)(__bridge void *)window, wire, pointerId.longLongValue,
                       local.x * scale, yTop * scale);
    }
}

- (NSUInteger)touchingCount:(NSEvent *)event {
    return [event touchesMatchingPhase:NSTouchPhaseTouching inView:self.view].count;
}

- (void)touchesBeganWithEvent:(NSEvent *)event {
    [super touchesBeganWithEvent:event];
    if (_ids.count == 0) {
        [[NSNotificationCenter defaultCenter]
            postNotificationName:NucleusTaoDirectTouchBeganNotification object:self.view.window];
    }
    [self forward:event phase:NSTouchPhaseBegan wire:0];
    // Recognizing claims the contacts, so AppKit routes the rest of them here.
    self.state = self.state == NSGestureRecognizerStatePossible
        ? NSGestureRecognizerStateBegan : NSGestureRecognizerStateChanged;
}

- (void)touchesMovedWithEvent:(NSEvent *)event {
    [super touchesMovedWithEvent:event];
    [self forward:event phase:NSTouchPhaseMoved wire:1];
    if (self.state == NSGestureRecognizerStateBegan || self.state == NSGestureRecognizerStateChanged) {
        self.state = NSGestureRecognizerStateChanged;
    }
}

- (void)touchesEndedWithEvent:(NSEvent *)event {
    [super touchesEndedWithEvent:event];
    [self forward:event phase:NSTouchPhaseEnded wire:2];
    [self settle:event];
}

- (void)touchesCancelledWithEvent:(NSEvent *)event {
    [super touchesCancelledWithEvent:event];
    [self forward:event phase:NSTouchPhaseCancelled wire:3];
    [self settle:event];
}

// Every recognizer must reach a final state, or AppKit keeps the next
// contacts away from the whole hierarchy (TN3212).
- (void)settle:(NSEvent *)event {
    if ([self touchingCount:event] > 0 && _ids.count > 0) {
        self.state = NSGestureRecognizerStateChanged;
        return;
    }
    switch (self.state) {
        case NSGestureRecognizerStateBegan:
        case NSGestureRecognizerStateChanged:
            self.state = NSGestureRecognizerStateEnded;
            break;
        case NSGestureRecognizerStatePossible:
            self.state = NSGestureRecognizerStateFailed;
            break;
        default:
            break;
    }
}

// AppKit cancels the recognizer itself (another window took the touches, the
// view left its window): the contacts still down are gone for Compose too.
- (void)reset {
    if (_ids.count > 0 && sTouchCallback != NULL) {
        NSView *view = self.view;
        NSWindow *window = view.window;
        if (window != nil) {
            CGFloat scale = window.backingScaleFactor;
            CGFloat yTop = view.isFlipped ? _lastLocation.y : view.bounds.size.height - _lastLocation.y;
            for (NSNumber *pointerId in _ids.allValues) {
                sTouchCallback((long)(__bridge void *)window, 3, pointerId.longLongValue,
                               _lastLocation.x * scale, yTop * scale);
            }
        }
    }
    [_ids removeAllObjects];
    _dragIdentity = nil;
    _lastIdentity = nil;
    [super reset];
}

// The macOS 27 SDK requires recognizers to report a location (NSZeroPoint
// plus a logged error otherwise): the last contact.
- (NSPoint)locationInView:(NSView *)view {
    return [self.view convertPoint:_lastLocation toView:view];
}

// ── Delegate ────────────────────────────────────────────────────────────

// Mouse and trackpad events stay in the responder chain, as before.
- (BOOL)gestureRecognizer:(NSGestureRecognizer *)recognizer
    shouldAttemptToRecognizeWithEvent:(NSEvent *)event {
    return NO;
}

// A finger on a NativeView child belongs to that view (WKWebView, MapKit…),
// which handles touch itself or through AppKit's emulation.
- (BOOL)gestureRecognizer:(NSGestureRecognizer *)recognizer shouldReceiveTouch:(NSTouch *)touch {
    NSView *view = self.view;
    if (view == nil || touch.type != NSTouchTypeDirect) return NO;
    // hitTest: takes a point in the superview's coordinates.
    NSView *hit = [view hitTest:[touch locationInView:view.superview]];
    return hit == nil || hit == view;
}

// Never block AppKit's own recognizers (window resize by touch, the context
// menu press of a NativeView parent…) and never be blocked by them.
- (BOOL)gestureRecognizer:(NSGestureRecognizer *)recognizer
    shouldRecognizeSimultaneouslyWithGestureRecognizer:(NSGestureRecognizer *)other {
    return YES;
}

@end

/* Attaches the forwarder to a tao content view, once. Main thread only. */
void nucleus_tao_attach_direct_touch(long ns_view_ptr) {
    if (ns_view_ptr == 0) return;
    NSView *view = (__bridge NSView *)(void *)ns_view_ptr;
    for (NSGestureRecognizer *recognizer in view.gestureRecognizers) {
        if ([recognizer isKindOfClass:[NucleusTaoTouchForwarder class]]) return;
    }
    [view addGestureRecognizer:[[NucleusTaoTouchForwarder alloc] init]];
    // macOS 27: gestures are exclusive to one view hierarchy at a time by
    // default; two windows (or two hands on one) must track fingers at once.
    // Called by selector so the file still builds against older SDKs.
    SEL setExclusive = NSSelectorFromString(@"setExclusiveGestureBehavior:");
    if ([view respondsToSelector:setExclusive]) {
        const NSInteger notExclusive = 2; // NSViewExclusiveGestureBehaviorNotExclusive
        ((void (*)(id, SEL, NSInteger))objc_msgSend)(view, setExclusive, notExclusive);
    }
}

/* Starts moving [ns_window_ptr] with the finger that landed last on it, when
 * one is down (a title bar dragged by touch). Returns 0 when no contact is down
 * there: the drag came from the mouse. Main thread only. */
int nucleus_tao_begin_touch_window_drag(long ns_window_ptr) {
    if (ns_window_ptr == 0) return 0;
    NSWindow *window = (__bridge NSWindow *)(void *)ns_window_ptr;
    for (NucleusTaoTouchForwarder *forwarder in sForwarders) {
        if (forwarder.view.window == window && [forwarder beginWindowDrag]) return 1;
    }
    return 0;
}

/* Whether [ns_view_ptr] carries the forwarder (e2e). */
int nucleus_tao_has_direct_touch(long ns_view_ptr) {
    if (ns_view_ptr == 0) return 0;
    NSView *view = (__bridge NSView *)(void *)ns_view_ptr;
    for (NSGestureRecognizer *recognizer in view.gestureRecognizers) {
        if ([recognizer isKindOfClass:[NucleusTaoTouchForwarder class]]) return 1;
    }
    return 0;
}

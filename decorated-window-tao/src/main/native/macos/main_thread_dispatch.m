// main_thread_dispatch.m
//
// Bridge that routes our Tao event loop onto the macOS main thread regardless
// of which thread the JNI/native-image entry was invoked from. Modelled on
// JWM's `App.mm` (HumbleUI/JWM, MIT) — the trick is to use
// `performSelectorOnMainThread:withObject:waitUntilDone:YES`, which uses a
// run-loop source (NSPort-based) rather than GCD. Unlike `dispatch_sync` on
// the main queue, it works even when the main thread has not yet entered an
// `[NSApp run]` loop, because the message wakes any CFRunLoop the main
// thread eventually enters.

#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>
#include <stdatomic.h>

@interface NucleusTaoMainLauncher : NSObject
{
@public
    void (*entry)(void *);
    void *context;
}
- (void)runEntry;
@end

@implementation NucleusTaoMainLauncher
- (void)runEntry {
    if (self->entry != NULL) {
        self->entry(self->context);
    }
}
@end

void nucleus_tao_run_on_main_blocking(void (*entry)(void *), void *context) {
    NucleusTaoMainLauncher *launcher = [[NucleusTaoMainLauncher alloc] init];
    launcher->entry   = entry;
    launcher->context = context;
    [launcher performSelectorOnMainThread:@selector(runEntry)
                               withObject:nil
                            waitUntilDone:YES];
}

int nucleus_tao_is_main_thread(void) {
    return [NSThread isMainThread] ? 1 : 0;
}

extern void nucleus_tao_post_exit(void);

static id sCmdQMonitor = nil;

void nucleus_tao_install_cmd_q_handler(void) {
    if (sCmdQMonitor != nil) return;
    sCmdQMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
        handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            NSEventModifierFlags mods = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
            if ((mods & NSEventModifierFlagCommand) &&
                [event.charactersIgnoringModifiers isEqualToString:@"q"]) {
                nucleus_tao_post_exit();
                return nil;
            }
            return event;
        }];
}

// macOS press-and-hold (long-press a key → accent picker) is gated by the
// `ApplePressAndHoldEnabled` user default. We set it everywhere we can — App
// domain, Argument volatile domain, CFPreferences, registration domain — both
// from `+load` (= dyld load time, before any Compose/AWT static init) and at
// runtime. Note: empirically this is **insufficient** for Tao's bare NSView;
// AppKit's `_NSKeyBindingManager` has additional internal checks tied to
// NSTextView/NSTextField subclasses that we cannot satisfy from outside.
// The flag plumbing is kept anyway since it's harmless and required for any
// future native-image/sub-process scenario.
static void nucleus_tao_force_press_and_hold(void) {
    @autoreleasepool {
        [[NSUserDefaults standardUserDefaults]
            setVolatileDomain:@{@"ApplePressAndHoldEnabled": @YES}
                      forName:NSArgumentDomain];
        [[NSUserDefaults standardUserDefaults]
            setBool:YES forKey:@"ApplePressAndHoldEnabled"];
        [[NSUserDefaults standardUserDefaults] synchronize];
        CFPreferencesSetAppValue(CFSTR("ApplePressAndHoldEnabled"),
                                 kCFBooleanTrue,
                                 kCFPreferencesCurrentApplication);
        CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);
        [[NSUserDefaults standardUserDefaults] registerDefaults:@{
            @"ApplePressAndHoldEnabled": @YES,
        }];
    }
}

@interface NucleusTaoPressAndHoldEnabler : NSObject
@end

@implementation NucleusTaoPressAndHoldEnabler
+ (void)load {
    nucleus_tao_force_press_and_hold();
}
@end

void nucleus_tao_enable_press_and_hold(void) {
    nucleus_tao_force_press_and_hold();
}

// ── IME caret rect plumbing (used by `firstRectForCharacterRange:` swizzle) ──
//
// Stored in screen coords (Cocoa bottom-up Y) so the swizzled getter can hand
// it back unchanged. Updated from the JVM side via `nativeSetImeRect`.

static _Atomic CGFloat g_ime_screen_x = 0;
static _Atomic CGFloat g_ime_screen_y = 0;
static _Atomic CGFloat g_ime_w = 1;
static _Atomic CGFloat g_ime_h = 18;

static NSRect tao_view_first_rect_for_character_range(
    id self, SEL _cmd, NSRange range, NSRangePointer actual_range
) {
    (void)self; (void)_cmd; (void)range;
    if (actual_range) {
        *actual_range = range;
    }
    return NSMakeRect(g_ime_screen_x, g_ime_screen_y, g_ime_w, g_ime_h);
}

// Tao's `selectedRange` returns `{NSNotFound, 0}` ("no text storage"). Some
// AppKit code paths interpret that as "this view doesn't host text" and skip
// IME-related machinery. Returning `{0, 0}` matches AWT-managed text views.
static NSRange tao_view_selected_range(id self, SEL _cmd) {
    (void)self; (void)_cmd;
    return NSMakeRange(0, 0);
}

// Tao's `validAttributesForMarkedText` returns `@[]`, which AppKit treats as
// "this client cannot host marked text" and skips PressAndHold. Returning the
// standard set used by Chromium's `BridgedContentView` and Firefox's `ChildView`
// classifies TaoView as a full IM client.
static NSArray<NSAttributedStringKey> *tao_view_valid_attributes_for_marked_text(
    id self, SEL _cmd
) {
    (void)self; (void)_cmd;
    return @[
        NSUnderlineStyleAttributeName,
        NSUnderlineColorAttributeName,
        NSMarkedClauseSegmentAttributeName,
        NSGlyphInfoAttributeName,
    ];
}

static void nucleus_tao_swizzle_view_methods_once(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Class taoViewClass = objc_getClass("TaoView");
        if (!taoViewClass) return;
        class_replaceMethod(taoViewClass,
                            @selector(selectedRange),
                            (IMP)tao_view_selected_range,
                            "{_NSRange=QQ}@:");
        class_replaceMethod(taoViewClass,
                            @selector(firstRectForCharacterRange:actualRange:),
                            (IMP)tao_view_first_rect_for_character_range,
                            "{CGRect={CGPoint=dd}{CGSize=dd}}@:{_NSRange=QQ}^{_NSRange=QQ}");
        class_replaceMethod(taoViewClass,
                            @selector(validAttributesForMarkedText),
                            (IMP)tao_view_valid_attributes_for_marked_text,
                            "@@:");
    });
}

void nucleus_tao_activate_input_context(long ns_view_handle) {
    nucleus_tao_swizzle_view_methods_once();
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    NSTextInputContext *ctx = view.inputContext;
    if (ctx) {
        [ctx activate];
    }
}

// ── NSTextView overlay (NucleusTextOverlay) ────────────────────────────────
//
// AppKit's `_NSKeyBindingManager` checks the firstResponder's class hierarchy
// before engaging press-and-hold (long-press 'e' → accent picker). Plain NSView
// subclasses — even with full NSTextInputClient conformance — short-circuit to
// `insertText:` immediately. NSTextView (and subclasses) trigger the marked-
// text phase that drives the picker.
//
// We add a transparent NSTextView subclass as a zero-frame subview of TaoView.
// When a Compose BasicTextField gains focus we make this overlay the
// firstResponder; AppKit then dispatches key events through it and sees the
// NSTextView lineage. The overlay forwards every NSTextInputClient call to
// TaoView's existing handlers (which emit `WindowEvent::ReceivedImeText` and
// `setMarkedText` events Tao already manages), so our existing JVM-side IME
// pipeline keeps working without changes.

@interface NucleusTextOverlay : NSTextView
@property(nonatomic, weak) NSView *taoView;
@end

@implementation NucleusTextOverlay
// Mouse events fall through to TaoView (the overlay is zero-frame anyway).
- (NSView *)hitTest:(NSPoint)point { (void)point; return nil; }

// Always accept first responder so the press-and-hold path can engage.
- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)becomeFirstResponder { return [super becomeFirstResponder]; }

// Forward NSTextInputClient calls to TaoView, which already implements them
// and routes through Tao's Rust-side WindowEvent pipeline.
- (BOOL)hasMarkedText {
    return self.taoView ? [(id<NSTextInputClient>)self.taoView hasMarkedText] : NO;
}
- (NSRange)markedRange {
    return self.taoView
        ? [(id<NSTextInputClient>)self.taoView markedRange]
        : NSMakeRange(NSNotFound, 0);
}
- (NSRange)selectedRange {
    return self.taoView
        ? [(id<NSTextInputClient>)self.taoView selectedRange]
        : NSMakeRange(0, 0);
}
- (void)setMarkedText:(id)string
        selectedRange:(NSRange)selectedRange
     replacementRange:(NSRange)replacementRange {
    if (self.taoView) {
        [(id<NSTextInputClient>)self.taoView setMarkedText:string
                                            selectedRange:selectedRange
                                         replacementRange:replacementRange];
    }
}
- (void)unmarkText {
    if (self.taoView) [(id<NSTextInputClient>)self.taoView unmarkText];
}
- (NSArray<NSAttributedStringKey> *)validAttributesForMarkedText {
    return @[];
}
- (NSAttributedString *)attributedSubstringForProposedRange:(NSRange)range
                                                actualRange:(NSRangePointer)actualRange {
    if (actualRange) *actualRange = NSMakeRange(NSNotFound, 0);
    return nil;
}
- (void)insertText:(id)string replacementRange:(NSRange)replacementRange {
    if (self.taoView) {
        [(id<NSTextInputClient>)self.taoView insertText:string
                                       replacementRange:replacementRange];
    }
}
- (NSUInteger)characterIndexForPoint:(NSPoint)point {
    (void)point;
    return 0;
}
- (NSRect)firstRectForCharacterRange:(NSRange)range
                         actualRange:(NSRangePointer)actualRange {
    if (actualRange) *actualRange = range;
    return NSMakeRect(g_ime_screen_x, g_ime_screen_y, g_ime_w, g_ime_h);
}
- (void)doCommandBySelector:(SEL)selector {
    // Forward editing commands (arrow keys, delete, enter, etc.) to TaoView so
    // its existing keyDown handler still emits the WindowEvent::KeyboardInput.
    // We have to re-dispatch the *current* NSEvent because doCommandBySelector
    // doesn't carry it.
    NSEvent *current = NSApp.currentEvent;
    if (self.taoView && current && current.type == NSEventTypeKeyDown) {
        [self.taoView keyDown:current];
    }
}
@end

static NucleusTextOverlay *g_text_overlay = nil;
static NSView *g_tao_view_ref = nil;

/// Adds a transparent NSTextView overlay to TaoView. Called once per window;
/// idempotent.
void nucleus_tao_attach_text_overlay(long ns_view_handle) {
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    g_tao_view_ref = view;
    if (g_text_overlay && g_text_overlay.superview == view) return;

    NucleusTextOverlay *overlay = [[NucleusTextOverlay alloc]
        initWithFrame:NSMakeRect(0, 0, 0, 0)];
    overlay.taoView = view;
    overlay.editable = YES;
    overlay.selectable = YES;
    overlay.drawsBackground = NO;
    overlay.fieldEditor = NO;
    overlay.richText = NO;
    overlay.allowsUndo = NO;
    [view addSubview:overlay];
    g_text_overlay = overlay;
}

/// When `focused` is true, makes the overlay firstResponder so AppKit routes
/// key events through it (engaging press-and-hold). When false, restores
/// TaoView as firstResponder.
void nucleus_tao_focus_text_overlay(int focused) {
    if (!g_text_overlay || !g_tao_view_ref) return;
    NSWindow *window = g_text_overlay.window ?: g_tao_view_ref.window;
    if (!window) return;
    NSResponder *target = focused ? (NSResponder *)g_text_overlay
                                   : (NSResponder *)g_tao_view_ref;
    [window makeFirstResponder:target];
}

/// Converts a caret rectangle expressed in NSView-local logical points
/// (top-left origin) to Cocoa screen coordinates (bottom-up origin) and
/// stores it for the swizzled `firstRectForCharacterRange:`.
void nucleus_tao_set_ime_local_rect(long ns_view_handle,
                                    double x, double y, double w, double h) {
    NSView *view = (__bridge NSView *)(void *)ns_view_handle;
    NSWindow *window = view.window;
    if (!window) return;

    NSRect viewBounds = view.bounds;
    NSRect rectInView = NSMakeRect(x, viewBounds.size.height - y - h, w, h);
    NSRect rectInWindow = [view convertRect:rectInView toView:nil];
    NSRect rectOnScreen = [window convertRectToScreen:rectInWindow];

    atomic_store(&g_ime_screen_x, rectOnScreen.origin.x);
    atomic_store(&g_ime_screen_y, rectOnScreen.origin.y);
    atomic_store(&g_ime_w, rectOnScreen.size.width > 0 ? rectOnScreen.size.width : 1);
    atomic_store(&g_ime_h, rectOnScreen.size.height > 0 ? rectOnScreen.size.height : 18);
}

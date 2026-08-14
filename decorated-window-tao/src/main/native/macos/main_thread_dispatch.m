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
// runtime. The picker itself is an input method: it only engages when
// `interpretKeyEvents:` sees the *repeat* keyDown after the initial press.
// Tao used to skip those repeats (see vendored `view.rs`); that was the
// actual blocker, not the NSView class hierarchy. These defaults stay
// required so a user-level `defaults write -g ApplePressAndHoldEnabled -bool
// false` cannot silently disable the picker for Nucleus apps.
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

// PressAndHold inserts the base character first (`insertText:@"e"`), then
// on the first repeat marks it (`setMarkedText:@"e"`) and shows the picker.
// Picking an accent calls `insertText:@"é"`. Compose has no composition
// range, so without a delete the field would read "eé". We remember the
// last committed scalar count and emit that many backspaces before the
// replacement insert.
static NSUInteger g_last_inserted_scalars = 0;
static BOOL g_pending_ime_replace = NO;
static IMP g_orig_set_marked_text = NULL;
static IMP g_orig_insert_text = NULL;
static IMP g_orig_unmark_text = NULL;
static void (*g_ime_delete_previous)(long ns_view, int count) = NULL;

void nucleus_tao_register_ime_delete_callback(void (*cb)(long, int)) {
    g_ime_delete_previous = cb;
}

static NSString *nucleus_string_from_ime_arg(id string) {
    if ([string isKindOfClass:[NSAttributedString class]]) {
        return [(NSAttributedString *)string string];
    }
    return (NSString *)string;
}

static NSUInteger nucleus_utf16_scalar_count(NSString *s) {
    if (!s) return 0;
    NSUInteger count = 0;
    NSUInteger len = s.length;
    for (NSUInteger i = 0; i < len; ) {
        unichar c = [s characterAtIndex:i];
        if (CFStringIsSurrogateHighCharacter(c) && i + 1 < len) {
            i += 2;
        } else {
            i += 1;
        }
        count++;
    }
    return count;
}

static void nucleus_set_marked_text(
    id self, SEL sel, id string, NSRange selected, NSRange replacement
) {
    if (g_last_inserted_scalars > 0) {
        g_pending_ime_replace = YES;
    }
    if (g_orig_set_marked_text) {
        ((void (*)(id, SEL, id, NSRange, NSRange))g_orig_set_marked_text)(
            self, sel, string, selected, replacement
        );
    }
}

static void nucleus_insert_text(id self, SEL sel, id string, NSRange replacement) {
    if (g_pending_ime_replace && g_last_inserted_scalars > 0 && g_ime_delete_previous) {
        g_ime_delete_previous((long)(__bridge void *)self, (int)g_last_inserted_scalars);
        g_last_inserted_scalars = 0;
    }
    g_pending_ime_replace = NO;
    if (g_orig_insert_text) {
        ((void (*)(id, SEL, id, NSRange))g_orig_insert_text)(self, sel, string, replacement);
    }
    g_last_inserted_scalars = nucleus_utf16_scalar_count(nucleus_string_from_ime_arg(string));
}

static void nucleus_unmark_text(id self, SEL sel) {
    g_pending_ime_replace = NO;
    if (g_orig_unmark_text) {
        ((void (*)(id, SEL))g_orig_unmark_text)(self, sel);
    }
}

static void nucleus_tao_swizzle_view_methods_once(void) {
    Class taoViewClass = objc_getClass("TaoView");
    if (!taoViewClass) return;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
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
        Method setMarked = class_getInstanceMethod(
            taoViewClass, @selector(setMarkedText:selectedRange:replacementRange:)
        );
        if (setMarked) {
            g_orig_set_marked_text = method_setImplementation(setMarked, (IMP)nucleus_set_marked_text);
        }
        Method insertText = class_getInstanceMethod(
            taoViewClass, @selector(insertText:replacementRange:)
        );
        if (insertText) {
            g_orig_insert_text = method_setImplementation(insertText, (IMP)nucleus_insert_text);
        }
        Method unmark = class_getInstanceMethod(taoViewClass, @selector(unmarkText));
        if (unmark) {
            g_orig_unmark_text = method_setImplementation(unmark, (IMP)nucleus_unmark_text);
        }
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

static NSCursor *nucleus_tao_cursor_from_selector(NSString *selectorName) {
    SEL selector = NSSelectorFromString(selectorName);
    if (![NSCursor respondsToSelector:selector]) return nil;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    return [NSCursor performSelector:selector];
#pragma clang diagnostic pop
}

static NSCursor *nucleus_tao_cursor_for_code(int code) {
    switch (code) {
        case 1:  return [NSCursor IBeamCursor];
        case 2:  return [NSCursor pointingHandCursor];
        case 3:  return [NSCursor crosshairCursor];
        case 4:
        case 8: {
            NSCursor *cursor = nucleus_tao_cursor_from_selector(@"busyButClickableCursor");
            return cursor ?: [NSCursor arrowCursor];
        }
        case 5: {
            NSCursor *cursor = nucleus_tao_cursor_from_selector(@"_moveCursor");
            return cursor ?: [NSCursor openHandCursor];
        }
        case 6:  return [NSCursor operationNotAllowedCursor];
        case 7: {
            NSCursor *cursor = nucleus_tao_cursor_from_selector(@"_helpCursor");
            return cursor ?: [NSCursor arrowCursor];
        }
        case 9:  return [NSCursor resizeLeftRightCursor];
        case 10: return [NSCursor resizeUpDownCursor];
        case 11: {
            NSCursor *cursor = nucleus_tao_cursor_from_selector(
                @"_windowResizeNorthEastSouthWestCursor");
            return cursor ?: [NSCursor arrowCursor];
        }
        case 12: {
            NSCursor *cursor = nucleus_tao_cursor_from_selector(
                @"_windowResizeNorthWestSouthEastCursor");
            return cursor ?: [NSCursor arrowCursor];
        }
        default: return [NSCursor arrowCursor];
    }
}

void nucleus_tao_set_cursor_icon(int code) {
    void (^apply)(void) = ^{
        NSCursor *cursor = nucleus_tao_cursor_for_code(code);
        if (cursor) [cursor set];
    };

    if ([NSThread isMainThread]) {
        apply();
    } else {
        dispatch_sync(dispatch_get_main_queue(), apply);
    }
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

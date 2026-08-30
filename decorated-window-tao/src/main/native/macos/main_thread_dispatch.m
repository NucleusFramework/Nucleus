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
#include <stdint.h>

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
// `ApplePressAndHoldEnabled` user default. Like Chromium (zero occurrences
// of the key in its tree), Nucleus never reads, sets or registers it — the
// OS/user decides whether a held letter repeats or opens the picker (#612).
// Forcing it on used to leave letter keys dead wherever the picker cannot
// engage (non-Apple keyboards, Karabiner virtual devices): the repeat was
// suppressed and nothing appeared in its place. The picker works because
// TaoView answers `selectedRange` / `attributedSubstringForProposedRange`
// over the committed text (document cache below) and honors
// `replacementRange` in `insertText:` (vendored view.rs) — and because
// repeat keyDowns are fed to `interpretKeyEvents:` (also view.rs).

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

// ── Document-backed NSTextInputClient answers (Chromium parity) ─────────────
//
// TaoView's own NSTextInputClient (vendored view.rs) only knows the marked
// text — it has no document. AppKit's press-and-hold picker needs more: it
// reads `selectedRange` when it engages and commits the accent as
// `insertText:"é" replacementRange:{caret-1, 1}` — a UTF-16 document-absolute
// range. Chromium solves this with a cached window of committed text around
// the selection, pushed asynchronously from the renderer
// (`setTextSelectionText:offset:range:` in RenderWidgetHostViewCocoa). Same
// model here: the JVM pushes the focused field's text window, window offset
// and selection on every field change; the swizzled getters serve those
// answers. All access is on the AppKit main thread (the Tao event loop *is*
// `Dispatchers.Main`).
//
// During a composition the marked text bookkeeping stays authoritative and
// synchronous in the view's ivar; only its *location* needs an absolute
// anchor, maintained optimistically in the `setMarkedText:` swizzle exactly
// like Chromium's `_markedRange` fallback chain (replacementRange →
// selection start at composition start → keep).
static int64_t g_doc_view = 0;
static NSString *g_doc_text = nil;
static int64_t g_doc_offset = 0;
static NSRange g_doc_selection = {NSNotFound, 0};
static NSUInteger g_marked_anchor = 0;

void nucleus_tao_set_ime_document(
    int64_t ns_view_handle,
    const uint16_t *utf16,
    int64_t utf16_len,
    int64_t offset,
    int64_t sel_start,
    int64_t sel_end
) {
    if (sel_start < 0 || utf16 == NULL) {
        // Scoped to the owning view: focus moving between windows tears down
        // the old input session *after* the new one starts, so a blanket
        // invalidate would wipe the cache the newly focused field just
        // installed and leave its picker committing against a stale caret.
        if (ns_view_handle != 0 && ns_view_handle != g_doc_view) {
            return;
        }
        g_doc_view = 0;
        g_doc_text = nil;
        g_doc_offset = 0;
        g_doc_selection = NSMakeRange(NSNotFound, 0);
        g_marked_anchor = 0;
        return;
    }
    g_doc_view = ns_view_handle;
    g_doc_text = [NSString stringWithCharacters:(const unichar *)utf16
                                         length:(NSUInteger)utf16_len];
    g_doc_offset = offset;
    g_doc_selection = NSMakeRange(
        (NSUInteger)sel_start,
        (NSUInteger)(sel_end >= sel_start ? sel_end - sel_start : 0)
    );
}

static BOOL nucleus_doc_valid_for(id view) {
    return g_doc_view != 0 &&
        g_doc_view == (int64_t)(intptr_t)(__bridge void *)view &&
        g_doc_text != nil &&
        g_doc_selection.location != NSNotFound;
}

static IMP g_orig_selected_range = NULL;
static IMP g_orig_marked_range = NULL;
static IMP g_orig_attributed_substring = NULL;
static IMP g_orig_set_marked_text = NULL;

/// Tao's own `markedRange` — `{0, len}` while composing, `{NSNotFound, 0}`
/// otherwise. The length is authoritative (updated synchronously by IMKit's
/// own `setMarkedText:`); only the location is view-relative.
static NSRange nucleus_orig_marked_range(id self) {
    if (g_orig_marked_range) {
        return ((NSRange (*)(id, SEL))g_orig_marked_range)(self, @selector(markedRange));
    }
    return NSMakeRange(NSNotFound, 0);
}

static BOOL nucleus_is_composing(id self) {
    NSRange marked = nucleus_orig_marked_range(self);
    return marked.location != NSNotFound && marked.length > 0;
}

/// `selectedRange` is document-absolute, like every document-backed client
/// (NSTextView, Chromium). While composing, tao reports the IME's selection
/// relative to the marked text — shift it by the composition anchor.
static NSRange tao_view_selected_range(id self, SEL _cmd) {
    if (nucleus_is_composing(self)) {
        NSRange rel = g_orig_selected_range
            ? ((NSRange (*)(id, SEL))g_orig_selected_range)(self, _cmd)
            : NSMakeRange(0, 0);
        return NSMakeRange(g_marked_anchor + rel.location, rel.length);
    }
    if (nucleus_doc_valid_for(self)) {
        return g_doc_selection;
    }
    return NSMakeRange(0, 0);
}

static NSRange tao_view_marked_range(id self, SEL _cmd) {
    (void)_cmd;
    NSRange marked = nucleus_orig_marked_range(self);
    if (marked.location == NSNotFound || marked.length == 0) {
        return NSMakeRange(NSNotFound, 0);
    }
    return NSMakeRange(g_marked_anchor, marked.length);
}

/// Maintains the absolute anchor of the marked text. A valid
/// replacementRange wins; otherwise a *starting* composition anchors at the
/// committed caret; a continuing one keeps its anchor (Chromium's
/// `_markedRange` fallback chain). The replacement's delete-committed-text
/// side is not applied — same self-declared limitation as Chromium's
/// `setMarkedText:` ("hard to support replacementRange without accessing
/// the full web content"); no mainstream IME depends on it.
static void tao_view_set_marked_text(
    id self, SEL sel, id string, NSRange selectedRange, NSRange replacementRange
) {
    // A stale `markedText` ivar (Compose cancelled the session without an
    // `unmarkText` reaching the view) would otherwise pin the anchor from a
    // previous field, so an invalid cache also forces a re-anchor.
    if (replacementRange.location != NSNotFound) {
        g_marked_anchor = replacementRange.location;
    } else if (!nucleus_is_composing(self) || !nucleus_doc_valid_for(self)) {
        g_marked_anchor = nucleus_doc_valid_for(self) ? g_doc_selection.location : 0;
    }
    if (g_orig_set_marked_text) {
        ((void (*)(id, SEL, id, NSRange, NSRange))g_orig_set_marked_text)(
            self, sel, string, selectedRange, replacementRange
        );
    }
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

/// While composing, the marked text in the view's ivar is authoritative —
/// serve requests inside the (absolute) marked range from it. Everything
/// else is served from the JVM-pushed committed-text window, clamped like
/// Chromium's `attributedSubstringForProposedRange:` (answer locally, write
/// the clamped `actualRange`, `nil` outside the window).
static id nucleus_attributed_substring(
    id self, SEL sel, NSRange range, NSRangePointer actual
) {
    if (nucleus_is_composing(self)) {
        NSRange marked = nucleus_orig_marked_range(self);
        NSRange abs = NSMakeRange(g_marked_anchor, marked.length);
        if (range.location >= abs.location && NSMaxRange(range) <= NSMaxRange(abs) &&
            g_orig_attributed_substring) {
            NSRange rel = NSMakeRange(range.location - g_marked_anchor, range.length);
            NSRange relActual = rel;
            id result = ((id (*)(id, SEL, NSRange, NSRangePointer))g_orig_attributed_substring)(
                self, sel, rel, &relActual
            );
            if (actual) {
                *actual = NSMakeRange(relActual.location + g_marked_anchor, relActual.length);
            }
            return result;
        }
    }
    if (!nucleus_doc_valid_for(self) || range.location == NSNotFound) {
        return nil;
    }
    NSUInteger window_start = (NSUInteger)g_doc_offset;
    NSUInteger window_end = window_start + g_doc_text.length;
    if (range.location >= window_end || NSMaxRange(range) <= window_start) {
        return nil;
    }
    NSUInteger loc = MAX(range.location, window_start);
    NSUInteger end = MIN(NSMaxRange(range), window_end);
    // `NSMaxRange` wraps on an overflowing proposed range, which slips past
    // the guards above and would underflow `end - loc` into a huge length.
    if (end <= loc) {
        return nil;
    }
    NSRange clamped = NSMakeRange(loc, end - loc);
    if (actual) {
        *actual = clamped;
    }
    NSRange local = NSMakeRange(clamped.location - window_start, clamped.length);
    NSString *sub = [g_doc_text substringWithRange:local];
    return [[NSAttributedString alloc] initWithString:sub];
}

/// IMKit uses this as the caret index (#595). No glyph map — report the
/// insertion point: end of the marked text while composing, the committed
/// caret otherwise.
static NSUInteger tao_view_character_index_for_point(id self, SEL _cmd, NSPoint point) {
    (void)_cmd; (void)point;
    if (nucleus_is_composing(self)) {
        return g_marked_anchor + nucleus_orig_marked_range(self).length;
    }
    if (nucleus_doc_valid_for(self)) {
        return g_doc_selection.location;
    }
    return 0;
}

static void nucleus_tao_swizzle_view_methods_once(void) {
    Class taoViewClass = objc_getClass("TaoView");
    if (!taoViewClass) return;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Method selectedRange = class_getInstanceMethod(
            taoViewClass, @selector(selectedRange)
        );
        if (selectedRange) {
            g_orig_selected_range =
                method_setImplementation(selectedRange, (IMP)tao_view_selected_range);
        }
        Method markedRange = class_getInstanceMethod(
            taoViewClass, @selector(markedRange)
        );
        if (markedRange) {
            g_orig_marked_range =
                method_setImplementation(markedRange, (IMP)tao_view_marked_range);
        }
        Method setMarkedText = class_getInstanceMethod(
            taoViewClass, @selector(setMarkedText:selectedRange:replacementRange:)
        );
        if (setMarkedText) {
            g_orig_set_marked_text =
                method_setImplementation(setMarkedText, (IMP)tao_view_set_marked_text);
        }
        class_replaceMethod(taoViewClass,
                            @selector(firstRectForCharacterRange:actualRange:),
                            (IMP)tao_view_first_rect_for_character_range,
                            "{CGRect={CGPoint=dd}{CGSize=dd}}@:{_NSRange=QQ}^{_NSRange=QQ}");
        class_replaceMethod(taoViewClass,
                            @selector(validAttributesForMarkedText),
                            (IMP)tao_view_valid_attributes_for_marked_text,
                            "@@:");
        Method attrSub = class_getInstanceMethod(
            taoViewClass, @selector(attributedSubstringForProposedRange:actualRange:)
        );
        if (attrSub) {
            g_orig_attributed_substring =
                method_setImplementation(attrSub, (IMP)nucleus_attributed_substring);
        }
        class_replaceMethod(taoViewClass,
                            @selector(characterIndexForPoint:),
                            (IMP)tao_view_character_index_for_point,
                            "Q@:{CGPoint=dd}");
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

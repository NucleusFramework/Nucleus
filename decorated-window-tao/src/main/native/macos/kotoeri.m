// kotoeri.m
//
// Headful e2e helper: select the real Japanese Kotoeri IME, inject NSEvents
// into TaoView's keyDown:/keyUp: (the same path a user keystroke takes), then
// restore the previous input source. Not used by the product path.

#import <Carbon/Carbon.h>
#import <Cocoa/Cocoa.h>
#import <CoreGraphics/CoreGraphics.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

static TISInputSourceRef g_saved = NULL;
static TISInputSourceRef g_enabled_parent = NULL;

static TISInputSourceRef copy_source_with_id(CFArrayRef list, CFStringRef want) {
    if (list == NULL || want == NULL) {
        return NULL;
    }
    CFIndex n = CFArrayGetCount(list);
    for (CFIndex i = 0; i < n; i++) {
        TISInputSourceRef src = (TISInputSourceRef)CFArrayGetValueAtIndex(list, i);
        CFStringRef sid = TISGetInputSourceProperty(src, kTISPropertyInputSourceID);
        if (sid != NULL && CFEqual(sid, want)) {
            CFRetain(src);
            return src;
        }
    }
    return NULL;
}

static BOOL source_id_in_list(CFArrayRef list, CFStringRef want) {
    if (list == NULL || want == NULL) {
        return NO;
    }
    CFIndex n = CFArrayGetCount(list);
    for (CFIndex i = 0; i < n; i++) {
        TISInputSourceRef src = (TISInputSourceRef)CFArrayGetValueAtIndex(list, i);
        CFStringRef sid = TISGetInputSourceProperty(src, kTISPropertyInputSourceID);
        if (sid != NULL && CFEqual(sid, want)) {
            return YES;
        }
    }
    return NO;
}

/// Hiragana-via-romaji: `com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese`.
/// Fall back to any selectable Kotoeri Japanese mode that is not katakana /
/// roman / half-width.
static TISInputSourceRef copy_japanese_hiragana(void) {
    CFArrayRef all = TISCreateInputSourceList(NULL, true);
    if (all == NULL) {
        return NULL;
    }
    TISInputSourceRef src = copy_source_with_id(
        all, CFSTR("com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese")
    );
    if (src == NULL) {
        CFIndex n = CFArrayGetCount(all);
        for (CFIndex i = 0; i < n; i++) {
            TISInputSourceRef cand = (TISInputSourceRef)CFArrayGetValueAtIndex(all, i);
            CFStringRef sid = TISGetInputSourceProperty(cand, kTISPropertyInputSourceID);
            CFBooleanRef selectable =
                TISGetInputSourceProperty(cand, kTISPropertyInputSourceIsSelectCapable);
            if (sid == NULL || selectable == NULL || !CFBooleanGetValue(selectable)) {
                continue;
            }
            char idb[256] = {0};
            if (!CFStringGetCString(sid, idb, sizeof(idb), kCFStringEncodingUTF8)) {
                continue;
            }
            if (strstr(idb, "Kotoeri") == NULL) {
                continue;
            }
            if (strstr(idb, "Katakana") != NULL ||
                strstr(idb, "HalfWidth") != NULL ||
                strstr(idb, "FullWidth") != NULL ||
                strstr(idb, "Roman") != NULL) {
                continue;
            }
            if (strstr(idb, "Japanese") == NULL) {
                continue;
            }
            CFRetain(cand);
            src = cand;
            break;
        }
    }
    CFRelease(all);
    return src;
}

static TISInputSourceRef copy_romaji_parent(void) {
    CFArrayRef all = TISCreateInputSourceList(NULL, true);
    if (all == NULL) {
        return NULL;
    }
    TISInputSourceRef src =
        copy_source_with_id(all, CFSTR("com.apple.inputmethod.Kotoeri.RomajiTyping"));
    CFRelease(all);
    return src;
}

static BOOL current_is_japanese(void) {
    TISInputSourceRef cur = TISCopyCurrentKeyboardInputSource();
    if (cur == NULL) {
        return NO;
    }
    CFStringRef sid = TISGetInputSourceProperty(cur, kTISPropertyInputSourceID);
    char idb[256] = {0};
    BOOL match = NO;
    if (sid != NULL && CFStringGetCString(sid, idb, sizeof(idb), kCFStringEncodingUTF8)) {
        match = strstr(idb, "Kotoeri") != NULL && strstr(idb, "Japanese") != NULL;
    }
    CFRelease(cur);
    return match;
}

int nucleus_tao_kotoeri_available(void) {
    TISInputSourceRef src = copy_japanese_hiragana();
    if (src == NULL) {
        return 0;
    }
    CFRelease(src);
    return 1;
}

int nucleus_tao_kotoeri_select(int64_t ns_view_ptr) {
    TISInputSourceRef jp = copy_japanese_hiragana();
    if (jp == NULL) {
        return 0;
    }

    if (g_saved == NULL) {
        g_saved = TISCopyCurrentKeyboardInputSource();
    }

    CFArrayRef enabled = TISCreateInputSourceList(NULL, false);
    BOOL parent_already_enabled = source_id_in_list(
        enabled, CFSTR("com.apple.inputmethod.Kotoeri.RomajiTyping")
    );
    if (enabled != NULL) {
        CFRelease(enabled);
    }

    if (!parent_already_enabled && g_enabled_parent == NULL) {
        TISInputSourceRef parent = copy_romaji_parent();
        if (parent != NULL) {
            OSStatus st = TISEnableInputSource(parent);
            if (st == noErr) {
                g_enabled_parent = parent;
            } else {
                CFRelease(parent);
            }
        }
    }

    OSStatus st = TISSelectInputSource(jp);
    CFRelease(jp);
    if (st != noErr && !current_is_japanese()) {
        return 0;
    }

    [NSApp activateIgnoringOtherApps:YES];
    if (ns_view_ptr != 0) {
        NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_ptr;
        NSWindow *win = view.window;
        if (win != nil) {
            [win makeKeyAndOrderFront:nil];
            [win makeFirstResponder:view];
        }
        NSTextInputContext *ctx = view.inputContext;
        [ctx activate];
        ctx.selectedKeyboardInputSource =
            @"com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese";
    }
    // TISSelectInputSource can return before the IME process is the current
    // source — wait briefly so the first posted keystroke sees Kotoeri.
    for (int i = 0; i < 50 && !current_is_japanese(); i++) {
        usleep(20000);
    }
    return current_is_japanese() ? 1 : 0;
}

void nucleus_tao_kotoeri_restore(void) {
    TISInputSourceRef saved = g_saved;
    TISInputSourceRef parent = g_enabled_parent;
    g_saved = NULL;
    g_enabled_parent = NULL;
    // Disable first — otherwise Kotoeri can steal the selection back.
    if (parent != NULL) {
        TISDisableInputSource(parent);
        CFRelease(parent);
    }
    if (saved != NULL) {
        TISSelectInputSource(saved);
        CFRelease(saved);
    }
}

int nucleus_tao_post_key_to_view(
    int64_t ns_view_ptr,
    int key_code,
    const char *chars,
    int down
) {
    if (ns_view_ptr == 0 || chars == NULL) {
        return 0;
    }
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_ptr;
    NSWindow *win = view.window;
    if (win == nil) {
        return 0;
    }
    [NSApp activateIgnoringOtherApps:YES];
    [win makeKeyAndOrderFront:nil];
    [win makeFirstResponder:view];
    (void)chars;

    // Keycode only — do not stamp a Latin unicode string onto the event or
    // Kotoeri never sees a "raw" key and insertText: just commits "n".
    // Session-tap posting makes the event currentEvent when AppKit dequeues
    // it (unlike a synchronous sendEvent from a runloop observer).
    CGEventRef cg = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)key_code, down != 0);
    if (cg == NULL) {
        return 0;
    }
    CGEventSetIntegerValueField(cg, kCGKeyboardEventAutorepeat, 0);
    CGEventPost(kCGSessionEventTap, cg);
    CFRelease(cg);
    return 1;
}

/// Writes the current TIS keyboard source id into [buf]. Returns 1 on success.
int nucleus_tao_current_input_source_id(char *buf, int len) {
    if (buf == NULL || len <= 1) {
        return 0;
    }
    buf[0] = '\0';
    TISInputSourceRef cur = TISCopyCurrentKeyboardInputSource();
    if (cur == NULL) {
        return 0;
    }
    CFStringRef sid = TISGetInputSourceProperty(cur, kTISPropertyInputSourceID);
    int ok = 0;
    if (sid != NULL &&
        CFStringGetCString(sid, buf, (CFIndex)len, kCFStringEncodingUTF8)) {
        ok = 1;
    }
    CFRelease(cur);
    return ok;
}

// text_input_client_probe.m
//
// Headful e2e helper: query and inject TaoView's NSTextInputClient the way
// IMKit does. Not used by the product path. Kept out of kotoeri.m — that
// file is TIS select/restore + CGEvent key delivery.

#import <Cocoa/Cocoa.h>
#include <stdint.h>
#include <string.h>

static int64_t range_loc_or_neg1(NSRange range) {
    return range.location == NSNotFound ? (int64_t)-1 : (int64_t)range.location;
}

/// [out_ranges] is 5×int64 (marked loc/len, selected loc/len, characterIndex).
/// NSNotFound is encoded as -1.
int nucleus_tao_query_text_input_client(
    int64_t ns_view_ptr,
    int64_t *out_ranges,
    char *substring_buf,
    int substring_buf_len
) {
    if (ns_view_ptr == 0 || out_ranges == NULL) {
        return 0;
    }
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_ptr;
    id<NSTextInputClient> client = (id<NSTextInputClient>)view;
    NSRange marked = [client markedRange];
    NSRange selected = [client selectedRange];
    NSUInteger idx = [client characterIndexForPoint:NSZeroPoint];
    out_ranges[0] = range_loc_or_neg1(marked);
    out_ranges[1] = (int64_t)marked.length;
    out_ranges[2] = range_loc_or_neg1(selected);
    out_ranges[3] = (int64_t)selected.length;
    out_ranges[4] = (idx == NSNotFound) ? (int64_t)-1 : (int64_t)idx;
    if (substring_buf != NULL && substring_buf_len > 0) {
        substring_buf[0] = '\0';
        if (marked.location != NSNotFound) {
            NSAttributedString *sub =
                [client attributedSubstringForProposedRange:marked actualRange:NULL];
            if (sub != nil) {
                const char *utf8 = sub.string.UTF8String;
                if (utf8 != NULL) {
                    strncpy(substring_buf, utf8, (size_t)substring_buf_len - 1);
                    substring_buf[substring_buf_len - 1] = '\0';
                }
            }
        }
    }
    return 1;
}

int nucleus_tao_inject_marked_text(
    int64_t ns_view_ptr,
    const char *utf8,
    int selected_loc,
    int selected_len
) {
    if (ns_view_ptr == 0 || utf8 == NULL) {
        return 0;
    }
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_ptr;
    NSString *str = [NSString stringWithUTF8String:utf8];
    if (str == nil) {
        return 0;
    }
    NSRange selected = NSMakeRange((NSUInteger)selected_loc, (NSUInteger)selected_len);
    NSRange replacement = NSMakeRange(NSNotFound, 0);
    [(id<NSTextInputClient>)view setMarkedText:str
                                 selectedRange:selected
                              replacementRange:replacement];
    return 1;
}

/// [rr_loc] < 0 means "no replacement range" ({NSNotFound, 0}) — the shape
/// of ordinary typing. A non-negative [rr_loc] replays the accent-picker
/// commit AppKit sends to document-backed clients: `insertText:'é'
/// replacementRange:{caret-1, 1}` (UTF-16, document-absolute).
int nucleus_tao_inject_insert_text(
    int64_t ns_view_ptr,
    const char *utf8,
    int64_t rr_loc,
    int64_t rr_len
) {
    if (ns_view_ptr == 0 || utf8 == NULL) {
        return 0;
    }
    NSView *view = (__bridge NSView *)(void *)(intptr_t)ns_view_ptr;
    NSString *str = [NSString stringWithUTF8String:utf8];
    if (str == nil) {
        return 0;
    }
    NSRange replacement = rr_loc < 0
        ? NSMakeRange(NSNotFound, 0)
        : NSMakeRange((NSUInteger)rr_loc, (NSUInteger)rr_len);
    [(id<NSTextInputClient>)view insertText:str replacementRange:replacement];
    return 1;
}

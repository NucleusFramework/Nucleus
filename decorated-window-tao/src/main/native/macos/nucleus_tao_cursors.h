// nucleus_tao_cursors.h
//
// The one TaoCursorIcon → NSCursor table (codes mirror `TaoCursorIcon` in
// TaoEventConstants.kt). Header-only so both dylibs that set cursors — the
// Rust crate's Objective-C helpers (window + Tao's cursor rects) and the
// popup panel helper — draw the same shapes.
//
// Private `NSCursor` factories are a liability: `+[NSCursor _moveCursor]` still
// exists on macOS 27 (`respondsToSelector:` says yes, the method has a real
// implementation) yet raises "unrecognized selector" when called, which aborted
// the JVM on hover (#746). No probe can tell such a method apart from a working
// one, so shapes come from public API first, then from the cursor bundles
// HIServices ships (what WebKit, Chromium and Tao read), and a private factory
// is only the last resort, behind `@try`.

#ifndef NUCLEUS_TAO_CURSORS_H
#define NUCLEUS_TAO_CURSORS_H

#import <Cocoa/Cocoa.h>
#include <stdint.h>

#define NUCLEUS_TAO_CURSOR_CODE_COUNT 15

static NSString *const kNucleusTaoHIServicesCursors =
    @"/System/Library/Frameworks/ApplicationServices.framework/Versions/A/Frameworks/"
    @"HIServices.framework/Versions/A/Resources/cursors";

/// A cursor from the HIServices bundle `<name>/cursor.pdf` + `info.plist`
/// (hotspot), or nil when any part of it is missing.
static NSCursor *nucleus_tao_hiservices_cursor(NSString *name) {
    NSString *dir = [kNucleusTaoHIServicesCursors stringByAppendingPathComponent:name];
    NSImage *image =
        [[NSImage alloc] initByReferencingFile:[dir stringByAppendingPathComponent:@"cursor.pdf"]];
    if (image == nil || !image.isValid) return nil;
    NSDictionary *info =
        [NSDictionary dictionaryWithContentsOfFile:[dir stringByAppendingPathComponent:@"info.plist"]];
    id x = info[@"hotx"];
    id y = info[@"hoty"];
    if (![x respondsToSelector:@selector(doubleValue)] || ![y respondsToSelector:@selector(doubleValue)]) {
        return nil;
    }
    return [[NSCursor alloc] initWithImage:image hotSpot:NSMakePoint([x doubleValue], [y doubleValue])];
}

/// A private `+[NSCursor <name>]` factory, nil if it is missing, throws or
/// returns something else.
static NSCursor *nucleus_tao_private_cursor(NSString *name) {
    SEL selector = NSSelectorFromString(name);
    if (![NSCursor respondsToSelector:selector]) return nil;
    @try {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        id cursor = [NSCursor performSelector:selector];
#pragma clang diagnostic pop
        return [cursor isKindOfClass:[NSCursor class]] ? cursor : nil;
    } @catch (id exception) {
        return nil;
    }
}

/// Diagonal frame resize: public on macOS 15+, the HIServices bundle before.
static NSCursor *nucleus_tao_diagonal_resize_cursor(BOOL northEast) {
    // A selector probe rather than `@available`: the Rust cdylib links
    // without compiler-rt, which provides `__isPlatformVersionAtLeast`.
    if ([NSCursor respondsToSelector:@selector(frameResizeCursorFromPosition:inDirections:)]) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability-new"
        NSCursor *cursor = [NSCursor
            frameResizeCursorFromPosition:northEast ? NSCursorFrameResizePositionTopRight
                                                    : NSCursorFrameResizePositionTopLeft
                             inDirections:NSCursorFrameResizeDirectionsAll];
#pragma clang diagnostic pop
        if (cursor != nil) return cursor;
    }
    return nucleus_tao_hiservices_cursor(northEast ? @"resizenortheastsouthwest"
                                                   : @"resizenorthwestsoutheast");
}

static NSCursor *nucleus_tao_resolve_cursor(int code) {
    switch (code) {
        case 1:  return [NSCursor IBeamCursor];
        case 2:  return [NSCursor pointingHandCursor];
        case 3:  return [NSCursor crosshairCursor];
        case 4:  // WAIT
        case 8:  // PROGRESS — Safari and Chrome use the same cursor for both
            return nucleus_tao_private_cursor(@"busyButClickableCursor");
        case 5: {
            NSCursor *cursor = nucleus_tao_hiservices_cursor(@"move");
            return cursor ?: [NSCursor openHandCursor];
        }
        case 6:  return [NSCursor operationNotAllowedCursor];
        case 7: {
            NSCursor *cursor = nucleus_tao_hiservices_cursor(@"help");
            return cursor ?: nucleus_tao_private_cursor(@"_helpCursor");
        }
        case 9:  return [NSCursor resizeLeftRightCursor];
        case 10: return [NSCursor resizeUpDownCursor];
        case 11: return nucleus_tao_diagonal_resize_cursor(YES);
        case 12: return nucleus_tao_diagonal_resize_cursor(NO);
        case 13: return [NSCursor openHandCursor];
        case 14: return [NSCursor closedHandCursor];
        default: return [NSCursor arrowCursor];
    }
}

/// The cursor for a `TaoCursorIcon` code, never nil and never throwing.
/// Resolved once per code and kept, so repeated calls return the same
/// instance (Tao's cursor rects and the immediate `[set]` agree on it, and the
/// HIServices PDFs are not reloaded on every cursor-rect rebuild).
static NSCursor *nucleus_tao_cursor_for_code(int code) {
    static NSCursor *cache[NUCLEUS_TAO_CURSOR_CODE_COUNT];
    if (code < 0 || code >= NUCLEUS_TAO_CURSOR_CODE_COUNT) return [NSCursor arrowCursor];
    @synchronized([NSCursor class]) {
        if (cache[code] == nil) {
            NSCursor *cursor = nil;
            @try {
                cursor = nucleus_tao_resolve_cursor(code);
            } @catch (id exception) {
                cursor = nil;
            }
            cache[code] = cursor ?: [NSCursor arrowCursor];
        }
        return cache[code];
    }
}

/// What a cursor looks like: hotspot, image size and a hash of its pixels at
/// 32x32. Two cursors with one signature draw the same shape, whichever dylib
/// built them — the headful suite compares `[NSCursor currentCursor]` against
/// the table with it.
static NSString *nucleus_tao_cursor_signature(NSCursor *cursor) {
    if (cursor == nil) return @"nil";
    enum { side = 32 };
    uint8_t pixels[side * side * 4] = {0};
    CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
    CGContextRef bitmap = CGBitmapContextCreate(pixels, side, side, 8, side * 4, space,
                                                (CGBitmapInfo)kCGImageAlphaPremultipliedLast);
    CGColorSpaceRelease(space);
    if (bitmap != NULL) {
        [NSGraphicsContext saveGraphicsState];
        NSGraphicsContext.currentContext = [NSGraphicsContext graphicsContextWithCGContext:bitmap flipped:NO];
        [cursor.image drawInRect:NSMakeRect(0, 0, side, side)];
        [NSGraphicsContext restoreGraphicsState];
        CGContextRelease(bitmap);
    }
    uint64_t hash = 1469598103934665603ULL;
    for (size_t i = 0; i < sizeof(pixels); i++) {
        hash = (hash ^ pixels[i]) * 1099511628211ULL;
    }
    NSSize size = cursor.image.size;
    return [NSString stringWithFormat:@"%gx%g@%g,%g#%016llx", size.width, size.height,
                                      cursor.hotSpot.x, cursor.hotSpot.y, hash];
}

#endif

// dialog_parent.m
//
// Headful e2e helper: prove a Tao-exported NSWindow* can own an NSOpenPanel
// sheet (FileKit-style dialog parenting via beginSheetModalForWindow:).
// Not used by the product path — only by the taoHeadfulTest suite.

#import <Cocoa/Cocoa.h>
#include <stdint.h>

/// Pointer equality walk — never messages [target]; safe for any bit pattern.
static BOOL view_tree_contains_ptr(NSView *root, void *target) {
    if (root == nil || target == NULL) {
        return NO;
    }
    if ((__bridge void *)root == target) {
        return YES;
    }
    for (NSView *child in root.subviews) {
        if (view_tree_contains_ptr(child, target)) {
            return YES;
        }
    }
    return NO;
}

static NSWindow *find_window_by_ptr(void *raw) {
    if (raw == NULL) {
        return nil;
    }
    for (NSWindow *win in NSApp.windows) {
        if ((__bridge void *)win == raw) {
            return win;
        }
    }
    return nil;
}

static void pump_main_runloop(NSTimeInterval seconds) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while ([deadline timeIntervalSinceNow] > 0) {
        BOOL more = [[NSRunLoop currentRunLoop]
            runMode:NSDefaultRunLoopMode
            beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
        if (!more) {
            // No sources ready — still wait out a slice so timers/async work.
            [NSThread sleepForTimeInterval:0.005];
        }
    }
}

/**
 * Present an NSOpenPanel as a sheet on [ns_window_ptr], verify attachment,
 * then cancel it. Optionally check that [ns_view_ptr] lives in that window's
 * view tree (Tao nativeHandle is the Compose NSView).
 *
 * Return codes (mirrored by the Kotlin headful probe):
 *   1  — sheet attached to the parent and dismissed cleanly
 *   0  — ns_window_ptr not found among NSApp.windows
 *  -1  — ns_view_ptr not in the parent's view hierarchy
 *  -2  — beginSheetModalForWindow did not attach a sheet
 *  -3  — sheet failed to dismiss / completion handler did not fire
 */
int nucleus_tao_probe_sheet_parent(int64_t ns_window_ptr, int64_t ns_view_ptr) {
    __block int code = 0;

    void (^work)(void) = ^{
        @autoreleasepool {
            NSWindow *parent = find_window_by_ptr((void *)(intptr_t)ns_window_ptr);
            if (parent == nil) {
                code = 0;
                return;
            }

            if (ns_view_ptr != 0) {
                void *viewPtr = (void *)(intptr_t)ns_view_ptr;
                NSView *content = parent.contentView;
                BOOL inTree = view_tree_contains_ptr(content, viewPtr);
                // Tao may also place chrome outside contentView; check titlebar
                // container when present (macOS 10.10+).
                if (!inTree) {
                    NSView *themeFrame = parent.contentView.superview;
                    inTree = view_tree_contains_ptr(themeFrame, viewPtr);
                }
                if (!inTree) {
                    code = -1;
                    return;
                }
            }

            NSOpenPanel *panel = [NSOpenPanel openPanel];
            panel.message = @"Nucleus nsWindow sheet e2e";
            panel.canChooseFiles = YES;
            panel.canChooseDirectories = NO;
            panel.allowsMultipleSelection = NO;
            panel.prompt = @"E2E";

            __block BOOL completed = NO;
            [panel beginSheetModalForWindow:parent
                          completionHandler:^(__unused NSModalResponse response) {
                              completed = YES;
                          }];

            // Sheet attachment is async relative to beginSheet…; pump until
            // AppKit wires attachedSheet / sheets, or time out.
            NSDate *attachDeadline = [NSDate dateWithTimeIntervalSinceNow:3.0];
            while (parent.attachedSheet == nil &&
                   ![parent.sheets containsObject:panel] &&
                   [attachDeadline timeIntervalSinceNow] > 0) {
                [[NSRunLoop currentRunLoop]
                    runMode:NSDefaultRunLoopMode
                    beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
            }

            BOOL attached = (parent.attachedSheet == panel) ||
                            [parent.sheets containsObject:panel] ||
                            (panel.sheetParent == parent);
            if (!attached) {
                [panel orderOut:nil];
                code = -2;
                return;
            }

            [parent endSheet:panel returnCode:NSModalResponseCancel];

            NSDate *endDeadline = [NSDate dateWithTimeIntervalSinceNow:3.0];
            while ((!completed || parent.attachedSheet != nil) &&
                   [endDeadline timeIntervalSinceNow] > 0) {
                [[NSRunLoop currentRunLoop]
                    runMode:NSDefaultRunLoopMode
                    beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
            }

            if (parent.attachedSheet != nil || [parent.sheets containsObject:panel]) {
                [panel orderOut:nil];
                code = -3;
                return;
            }
            if (!completed) {
                // Sheet gone but completion never ran — still treat as soft
                // failure so we notice AppKit lifecycle changes.
                code = -3;
                return;
            }

            // Drain a few more ticks so nothing from the sheet lingers into
            // the next headful case.
            pump_main_runloop(0.05);
            code = 1;
        }
    };

    if ([NSThread isMainThread]) {
        work();
    } else {
        dispatch_sync(dispatch_get_main_queue(), work);
    }
    return code;
}

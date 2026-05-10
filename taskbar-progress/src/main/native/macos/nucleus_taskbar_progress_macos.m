/**
 * JNI bridge for macOS dock progress indicator.
 *
 * Uses NSDockTile with a custom NSProgressIndicator subclass to render
 * a colored progress bar at the bottom of the app's dock icon.
 *
 * Attention requests use [NSApp requestUserAttention:].
 *
 * Frameworks: Cocoa
 */

#import <Cocoa/Cocoa.h>
#include <jni.h>

/* State constants — must match TaskbarProgress.State.flag values */
#define STATE_NO_PROGRESS   0x00
#define STATE_INDETERMINATE 0x01
#define STATE_NORMAL        0x02
#define STATE_ERROR         0x04
#define STATE_PAUSED        0x08

/* ---- Custom NSProgressIndicator subclass -------------------------- */

@interface NucleusProgressIndicator : NSProgressIndicator
@property (nonatomic, assign) uint8_t progressState;
@end

@implementation NucleusProgressIndicator

- (void)drawRect:(NSRect)dirtyRect {
    (void)dirtyRect;
    NSRect bounds = self.bounds;

    NSRect bar = NSMakeRect(0.0, 4.0, bounds.size.width, 8.0);
    NSRect barInner = NSInsetRect(bar, 0.5, 0.5);
    NSRect barProgress = NSInsetRect(bar, 1.0, 1.0);

    /* Scale progress width */
    double normalized = fmin(fmax(self.doubleValue / 100.0, 0.0), 1.0);
    barProgress.size.width *= normalized;

    CGFloat radius = bar.size.height / 2.0;

    /* Outer bar background */
    [[NSColor colorWithWhite:1.0 alpha:0.05] set];
    [[NSBezierPath bezierPathWithRoundedRect:bar xRadius:radius yRadius:radius] fill];

    /* Inner bar background */
    [[NSBezierPath bezierPathWithRoundedRect:barInner xRadius:radius yRadius:radius] fill];

    /* Progress fill */
    NSColor *color;
    switch (_progressState) {
        case STATE_PAUSED: color = [NSColor systemYellowColor]; break;
        case STATE_ERROR:  color = [NSColor systemRedColor];    break;
        default:           color = [NSColor systemBlueColor];   break;
    }
    [color set];
    [[NSBezierPath bezierPathWithRoundedRect:barProgress xRadius:radius yRadius:radius] fill];
}

@end

/* ---- Progress indicator lifecycle --------------------------------- */

static NucleusProgressIndicator *g_indicator = nil;

static NucleusProgressIndicator *getOrCreateIndicator(void) {
    /* Fast path: cached and still attached to a superview */
    if (g_indicator != nil && [g_indicator superview] != nil) {
        return g_indicator;
    }
    g_indicator = nil;

    NSApplication *app = [NSApplication sharedApplication];
    NSDockTile *dockTile = [app dockTile];
    if (dockTile == nil) return nil;

    /* Set up content view with app icon if needed */
    NSView *contentView = [dockTile contentView];
    if (contentView == nil) {
        NSImage *appIcon = [app applicationIconImage];
        NSImageView *imageView = [NSImageView imageViewWithImage:appIcon];
        [dockTile setContentView:imageView];
        contentView = imageView;
    }

    /* Search for existing indicator in subviews */
    for (NSView *subview in [contentView subviews]) {
        if ([subview isKindOfClass:[NucleusProgressIndicator class]]) {
            g_indicator = (NucleusProgressIndicator *)subview;
            return g_indicator;
        }
    }

    /* Create new indicator at the bottom of the dock tile */
    NSSize tileSize = [dockTile size];
    NSRect frame = NSMakeRect(0.0, 0.0, tileSize.width, 15.0);
    g_indicator = [[NucleusProgressIndicator alloc] initWithFrame:frame];
    [g_indicator setMinValue:0.0];
    [g_indicator setMaxValue:100.0];
    [g_indicator setHidden:YES];
    [contentView addSubview:g_indicator];

    return g_indicator;
}

/* Helper: run a block on the main thread (sync if off-main, direct if on-main) */
static void runOnMain(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

/* ---- JNI: nativeSetDockProgress ----------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_taskbarprogress_macos_NativeMacOsTaskbarBridge_nativeSetDockProgress(
    JNIEnv *env, jclass clazz, jlong completed, jlong total)
{
    (void)env; (void)clazz;

    __block jint result = 0;
    runOnMain(^{
        @autoreleasepool {
            NucleusProgressIndicator *indicator = getOrCreateIndicator();
            if (indicator == nil) { result = -1; return; }

            double progress = (total > 0) ? ((double)completed / (double)total * 100.0) : 0.0;
            [indicator setDoubleValue:progress];

            /* Implicitly switch to NORMAL if no state was set — matches Windows behavior
               where SetProgressValue auto-shows the progress bar. */
            if ([indicator progressState] == STATE_NO_PROGRESS) {
                [indicator setProgressState:STATE_NORMAL];
            }
            [indicator setHidden:NO];
            [indicator setNeedsDisplay:YES];

            NSDockTile *dockTile = [[NSApplication sharedApplication] dockTile];
            [dockTile display];
        }
    });
    return result;
}

/* ---- JNI: nativeSetDockState -------------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_taskbarprogress_macos_NativeMacOsTaskbarBridge_nativeSetDockState(
    JNIEnv *env, jclass clazz, jint flags)
{
    (void)env; (void)clazz;

    __block jint result = 0;
    runOnMain(^{
        @autoreleasepool {
            NSDockTile *dockTile = [[NSApplication sharedApplication] dockTile];
            if (dockTile == nil) { result = -1; return; }

            if (flags == STATE_NO_PROGRESS) {
                /* Restore default dock tile by removing custom content view */
                [dockTile setContentView:nil];
                g_indicator = nil;
                [dockTile display];
                return;
            }

            NucleusProgressIndicator *indicator = getOrCreateIndicator();
            if (indicator == nil) { result = -1; return; }

            [indicator setProgressState:(uint8_t)flags];
            [indicator setHidden:NO];
            [indicator setNeedsDisplay:YES];
            [dockTile display];
        }
    });
    return result;
}

/* ---- JNI: nativeRequestAttention ---------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_taskbarprogress_macos_NativeMacOsTaskbarBridge_nativeRequestAttention(
    JNIEnv *env, jclass clazz, jint type)
{
    (void)env; (void)clazz;

    __block jint requestId = -1;
    runOnMain(^{
        @autoreleasepool {
            NSApplication *app = [NSApplication sharedApplication];
            NSRequestUserAttentionType attentionType;
            switch (type) {
                case 2:  attentionType = NSCriticalRequest;      break;
                default: attentionType = NSInformationalRequest; break;
            }
            requestId = (jint)[app requestUserAttention:attentionType];
        }
    });
    return requestId;
}

/* ---- JNI: nativeCancelAttention ----------------------------------- */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_taskbarprogress_macos_NativeMacOsTaskbarBridge_nativeCancelAttention(
    JNIEnv *env, jclass clazz, jint requestId)
{
    (void)env; (void)clazz;

    runOnMain(^{
        @autoreleasepool {
            [[NSApplication sharedApplication] cancelUserAttentionRequest:(NSInteger)requestId];
        }
    });
}

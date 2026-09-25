// macOS backend of the screen-capture module.
//
// macOS 14+: ScreenCaptureKit (SCScreenshotManager). Earlier: Core Graphics display and window
// images, resolved with dlsym because the macOS 15 SDK marks them obsoleted.
//
// Pixels leave as jint 0xAARRGGBB: a CGBitmapContext with kCGImageAlphaPremultipliedFirst |
// kCGBitmapByteOrder32Little stores B, G, R, A bytes, which is that int on little-endian.

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreVideo/CoreVideo.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include <dlfcn.h>
#include <math.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "../../../../../native-common/nucleus_jni.h"

#define STATUS_OK 0
#define STATUS_UNSUPPORTED 1
#define STATUS_PERMISSION_DENIED 2
#define STATUS_DISPLAY_NOT_FOUND 3
#define STATUS_WINDOW_NOT_FOUND 4
#define STATUS_FAILED 6
#define STATUS_TIMEOUT 7
#define STATUS_INVALID_REGION 8

#define PERMISSION_GRANTED 0
#define PERMISSION_DENIED 1
#define PERMISSION_NOT_DETERMINED 2

#define BACKEND_NONE 0
#define BACKEND_SCREEN_CAPTURE_KIT 2
#define BACKEND_CORE_GRAPHICS 3

#define MAX_DISPLAYS 32
#define CAPTURE_TIMEOUT_SECONDS 10.0

// SCStreamErrorUserDeclined, spelled out so the file also builds where the enum is missing.
#define SC_ERROR_USER_DECLINED (-3801)

typedef CGImageRef (*CreateDisplayImageForRectFn)(CGDirectDisplayID, CGRect);
typedef CGImageRef (*CreateWindowListImageFn)(CGRect, CGWindowListOption, CGWindowID, CGWindowImageOption);

static CreateDisplayImageForRectFn cgCreateDisplayImageForRect(void) {
    static CreateDisplayImageForRectFn fn;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        fn = (CreateDisplayImageForRectFn)dlsym(RTLD_DEFAULT, "CGDisplayCreateImageForRect");
    });
    return fn;
}

static CreateWindowListImageFn cgCreateWindowListImage(void) {
    static CreateWindowListImageFn fn;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        fn = (CreateWindowListImageFn)dlsym(RTLD_DEFAULT, "CGWindowListCreateImage");
    });
    return fn;
}

static BOOL hasScreenCaptureKit(void) {
    if (@available(macOS 14.0, *)) {
        return [SCScreenshotManager class] != nil;
    }
    return NO;
}

// ---------------------------------------------------------------------------------------------
// JNI result helpers
// ---------------------------------------------------------------------------------------------

static void setResult(JNIEnv *env, jintArray result, jint status, jint width, jint height) {
    if (result == NULL || (*env)->GetArrayLength(env, result) < 3) return;
    jint values[3] = {status, width, height};
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
}

static void setMessage(JNIEnv *env, jobjectArray message, NSString *text) {
    if (message == NULL || text == nil || (*env)->GetArrayLength(env, message) < 1) return;
    jstring str = (*env)->NewStringUTF(env, text.UTF8String ?: "");
    if (str == NULL) {
        nucleus_jni_clear_exception(env);
        return;
    }
    (*env)->SetObjectArrayElement(env, message, 0, str);
    (*env)->DeleteLocalRef(env, str);
}

static jintArray fail(JNIEnv *env, jintArray result, jobjectArray message, jint status, NSString *text) {
    setResult(env, result, status, 0, 0);
    setMessage(env, message, text);
    return NULL;
}

// Draws [image] into an opaque BGRA buffer and hands it to Java as 0xAARRGGBB ints.
static jintArray imageToPixels(JNIEnv *env, CGImageRef image, jintArray result, jobjectArray message) {
    size_t width = CGImageGetWidth(image);
    size_t height = CGImageGetHeight(image);
    if (width == 0 || height == 0 || (uint64_t)width * height > (uint64_t)INT32_MAX - 8) {
        return fail(env, result, message, STATUS_FAILED, [NSString stringWithFormat:@"Unusable image size %zux%zu", width, height]);
    }
    size_t count = width * height;
    uint32_t *buffer = calloc(count, sizeof(uint32_t));
    if (buffer == NULL) {
        return fail(env, result, message, STATUS_FAILED, @"Out of memory");
    }
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    CGContextRef context = CGBitmapContextCreate(
        buffer, width, height, 8, width * 4, space,
        (CGBitmapInfo)kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
    CGColorSpaceRelease(space);
    if (context == NULL) {
        free(buffer);
        return fail(env, result, message, STATUS_FAILED, @"CGBitmapContextCreate failed");
    }
    CGContextSetBlendMode(context, kCGBlendModeCopy);
    CGContextDrawImage(context, CGRectMake(0, 0, (CGFloat)width, (CGFloat)height), image);
    CGContextRelease(context);
    for (size_t i = 0; i < count; i++) buffer[i] |= 0xFF000000u;

    jintArray pixels = (*env)->NewIntArray(env, (jsize)count);
    if (pixels == NULL) {
        free(buffer);
        nucleus_jni_clear_exception(env);
        return fail(env, result, message, STATUS_FAILED, @"Cannot allocate the pixel array");
    }
    (*env)->SetIntArrayRegion(env, pixels, 0, (jsize)count, (const jint *)buffer);
    free(buffer);
    setResult(env, result, STATUS_OK, (jint)width, (jint)height);
    return pixels;
}

// ---------------------------------------------------------------------------------------------
// ScreenCaptureKit plumbing
// ---------------------------------------------------------------------------------------------

// Shared between a waiting caller and a completion handler that may outlive it (timeout).
@interface NucleusCaptureBox : NSObject
@property(nonatomic) BOOL done;
@property(nonatomic) BOOL abandoned;
@property(nonatomic, strong) NSError *error;
@property(nonatomic, strong) id value;
@property(nonatomic) CGImageRef image;
@end

@implementation NucleusCaptureBox
- (void)dealloc {
    if (_image != NULL) CGImageRelease(_image);
}
@end

// Waits for [sem]. ScreenCaptureKit completes on its own queue, so a plain wait is safe on any
// thread — including the main thread, whose run loop is deliberately not pumped here: turning
// it would dispatch the app's own events re-entrantly from inside a capture call.
static BOOL waitFor(dispatch_semaphore_t sem) {
    return dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(CAPTURE_TIMEOUT_SECONDS * NSEC_PER_SEC))) == 0;
}

static BOOL isPermissionError(NSError *error) {
    return error != nil && error.code == SC_ERROR_USER_DECLINED;
}

// Fetches the shareable content; returns a status and fills [out] on success.
API_AVAILABLE(macos(14.0))
static jint shareableContent(BOOL onScreenOnly, SCShareableContent **out, NSString **why) {
    NucleusCaptureBox *box = [NucleusCaptureBox new];
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [SCShareableContent getShareableContentExcludingDesktopWindows:NO
                                               onScreenWindowsOnly:onScreenOnly
                                                 completionHandler:^(SCShareableContent *content, NSError *error) {
        @synchronized(box) {
            box.value = content;
            box.error = error;
            box.done = YES;
        }
        dispatch_semaphore_signal(sem);
    }];
    if (!waitFor(sem)) {
        @synchronized(box) { box.abandoned = YES; }
        *why = @"Timed out waiting for SCShareableContent";
        return STATUS_TIMEOUT;
    }
    if (box.value == nil) {
        *why = box.error.localizedDescription ?: @"SCShareableContent returned nothing";
        if (isPermissionError(box.error) || !CGPreflightScreenCaptureAccess()) return STATUS_PERMISSION_DENIED;
        return STATUS_FAILED;
    }
    *out = box.value;
    return STATUS_OK;
}

// Captures with SCScreenshotManager; returns a status and a +1 image in [out].
API_AVAILABLE(macos(14.0))
static jint screenshot(SCContentFilter *filter, SCStreamConfiguration *config, CGImageRef *out, NSString **why) {
    NucleusCaptureBox *box = [NucleusCaptureBox new];
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [SCScreenshotManager captureImageWithFilter:filter
                                  configuration:config
                              completionHandler:^(CGImageRef image, NSError *error) {
        @synchronized(box) {
            // The image is only valid for the handler's duration: retain it for the caller,
            // unless the caller already gave up (the box then releases nothing it never got).
            if (image != NULL && !box.abandoned) box.image = CGImageRetain(image);
            box.error = error;
            box.done = YES;
        }
        dispatch_semaphore_signal(sem);
    }];
    if (!waitFor(sem)) {
        @synchronized(box) { box.abandoned = YES; }
        *why = @"Timed out waiting for SCScreenshotManager";
        return STATUS_TIMEOUT;
    }
    CGImageRef image = NULL;
    NSError *error = nil;
    @synchronized(box) {
        image = box.image;
        box.image = NULL; // ownership moves to the caller
        error = box.error;
    }
    if (image == NULL) {
        *why = error.localizedDescription ?: @"SCScreenshotManager returned no image";
        if (isPermissionError(error) || !CGPreflightScreenCaptureAccess()) return STATUS_PERMISSION_DENIED;
        return STATUS_FAILED;
    }
    *out = image;
    return STATUS_OK;
}

API_AVAILABLE(macos(14.0))
static void configureCommon(SCStreamConfiguration *config, BOOL includeCursor) {
    config.showsCursor = includeCursor;
    config.pixelFormat = kCVPixelFormatType_32BGRA;
    config.colorSpaceName = kCGColorSpaceSRGB;
    config.scalesToFit = NO;
    config.captureResolution = SCCaptureResolutionBest;
}

// ---------------------------------------------------------------------------------------------
// Displays
// ---------------------------------------------------------------------------------------------

typedef struct {
    CGRect bounds;  // points, global coordinates
    size_t widthPx;
    size_t heightPx;
} DisplayGeometry;

static BOOL displayGeometry(CGDirectDisplayID display, DisplayGeometry *out) {
    CGRect bounds = CGDisplayBounds(display);
    if (CGRectIsEmpty(bounds)) return NO;
    size_t widthPx = 0;
    size_t heightPx = 0;
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode(display);
    if (mode != NULL) {
        widthPx = CGDisplayModeGetPixelWidth(mode);
        heightPx = CGDisplayModeGetPixelHeight(mode);
        CGDisplayModeRelease(mode);
    }
    if (widthPx == 0 || heightPx == 0) {
        widthPx = CGDisplayPixelsWide(display);
        heightPx = CGDisplayPixelsHigh(display);
    }
    out->bounds = bounds;
    out->widthPx = widthPx;
    out->heightPx = heightPx;
    return widthPx > 0 && heightPx > 0;
}

static BOOL isActiveDisplay(CGDirectDisplayID display) {
    CGDirectDisplayID displays[MAX_DISPLAYS];
    uint32_t count = 0;
    if (CGGetActiveDisplayList(MAX_DISPLAYS, displays, &count) != kCGErrorSuccess) return NO;
    for (uint32_t i = 0; i < count; i++) {
        if (displays[i] == display) return YES;
    }
    return NO;
}

static BOOL parseDisplayId(JNIEnv *env, jstring id, CGDirectDisplayID *out) {
    if (id == NULL) return NO;
    const char *chars = (*env)->GetStringUTFChars(env, id, NULL);
    if (chars == NULL) {
        nucleus_jni_clear_exception(env);
        return NO;
    }
    char *end = NULL;
    unsigned long long value = strtoull(chars, &end, 10);
    BOOL ok = end != chars && *end == '\0' && value <= UINT32_MAX;
    (*env)->ReleaseStringUTFChars(env, id, chars);
    if (ok) *out = (CGDirectDisplayID)value;
    return ok;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeBackend(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (hasScreenCaptureKit()) return BACKEND_SCREEN_CAPTURE_KIT;
    if (cgCreateDisplayImageForRect() != NULL) return BACKEND_CORE_GRAPHICS;
    return BACKEND_NONE;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeListDisplays(
    JNIEnv *env, jclass clazz, jobject sink, jobjectArray message) {
    (void)clazz;
    @autoreleasepool {
        CGDirectDisplayID displays[MAX_DISPLAYS];
        uint32_t count = 0;
        CGError err = CGGetActiveDisplayList(MAX_DISPLAYS, displays, &count);
        if (err != kCGErrorSuccess) {
            setMessage(env, message, [NSString stringWithFormat:@"CGGetActiveDisplayList failed: %d", err]);
            return STATUS_FAILED;
        }
        jclass sinkClass = (*env)->GetObjectClass(env, sink);
        jmethodID add = (*env)->GetMethodID(env, sinkClass, "add", "(Ljava/lang/String;Ljava/lang/String;IIIIIIFZ)V");
        if (add == NULL) {
            nucleus_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, sinkClass);
            setMessage(env, message, @"DisplayCollector.add not found");
            return STATUS_FAILED;
        }
        for (uint32_t i = 0; i < count; i++) {
            CGDirectDisplayID display = displays[i];
            DisplayGeometry geometry;
            if (!displayGeometry(display, &geometry)) continue;
            // Names come from Core Graphics only: NSScreen is AppKit state owned by the main
            // thread, and this runs on any thread.
            NSString *name = CGDisplayIsBuiltin(display)
                ? @"Built-in Display"
                : [NSString stringWithFormat:@"Display %u", display];
            NSString *idText = [NSString stringWithFormat:@"%u", display];
            jstring jid = (*env)->NewStringUTF(env, idText.UTF8String);
            jstring jname = (*env)->NewStringUTF(env, name.UTF8String);
            if (jid == NULL || jname == NULL) {
                nucleus_jni_clear_exception(env);
                if (jid != NULL) (*env)->DeleteLocalRef(env, jid);
                if (jname != NULL) (*env)->DeleteLocalRef(env, jname);
                continue;
            }
            CGRect b = geometry.bounds;
            jfloat scale = (jfloat)((CGFloat)geometry.widthPx / b.size.width);
            (*env)->CallVoidMethod(
                env, sink, add, jid, jname,
                (jint)lround(b.origin.x), (jint)lround(b.origin.y),
                (jint)lround(b.size.width), (jint)lround(b.size.height),
                (jint)geometry.widthPx, (jint)geometry.heightPx,
                scale, (jboolean)(CGDisplayIsMain(display) ? JNI_TRUE : JNI_FALSE));
            nucleus_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, jid);
            (*env)->DeleteLocalRef(env, jname);
        }
        (*env)->DeleteLocalRef(env, sinkClass);
        return STATUS_OK;
    }
}

API_AVAILABLE(macos(14.0))
static jintArray captureDisplayWithKit(
    JNIEnv *env, CGDirectDisplayID displayId, CGRect regionPx, CGFloat scale, jboolean includeCursor,
    jintArray result, jobjectArray message) {
    NSString *why = nil;
    SCShareableContent *content = nil;
    jint status = shareableContent(YES, &content, &why);
    if (status != STATUS_OK) return fail(env, result, message, status, why);

    SCDisplay *target = nil;
    for (SCDisplay *display in content.displays) {
        if (display.displayID == displayId) {
            target = display;
            break;
        }
    }
    if (target == nil) {
        return fail(env, result, message, STATUS_DISPLAY_NOT_FOUND,
                    [NSString stringWithFormat:@"ScreenCaptureKit does not list display %u", displayId]);
    }

    SCContentFilter *filter = [[SCContentFilter alloc] initWithDisplay:target excludingWindows:@[]];
    SCStreamConfiguration *config = [SCStreamConfiguration new];
    configureCommon(config, includeCursor);
    config.width = (size_t)regionPx.size.width;
    config.height = (size_t)regionPx.size.height;
    // sourceRect is in points, relative to the display.
    config.sourceRect = CGRectMake(regionPx.origin.x / scale, regionPx.origin.y / scale,
                                   regionPx.size.width / scale, regionPx.size.height / scale);

    CGImageRef image = NULL;
    status = screenshot(filter, config, &image, &why);
    if (status != STATUS_OK) return fail(env, result, message, status, why);
    jintArray pixels = imageToPixels(env, image, result, message);
    CGImageRelease(image);
    return pixels;
}

static jintArray captureDisplayWithCoreGraphics(
    JNIEnv *env, CGDirectDisplayID displayId, CGRect regionPx, CGFloat scale,
    jintArray result, jobjectArray message) {
    CreateDisplayImageForRectFn create = cgCreateDisplayImageForRect();
    if (create == NULL) {
        return fail(env, result, message, STATUS_UNSUPPORTED, @"CGDisplayCreateImageForRect is unavailable");
    }
    // Without the permission Core Graphics silently returns the wallpaper alone.
    if (!CGPreflightScreenCaptureAccess()) {
        return fail(env, result, message, STATUS_PERMISSION_DENIED, @"Screen recording permission is not granted");
    }
    CGRect points = CGRectMake(regionPx.origin.x / scale, regionPx.origin.y / scale,
                               regionPx.size.width / scale, regionPx.size.height / scale);
    CGImageRef image = create(displayId, points);
    if (image == NULL) {
        return fail(env, result, message, STATUS_FAILED, @"CGDisplayCreateImageForRect returned nothing");
    }
    jintArray pixels = imageToPixels(env, image, result, message);
    CGImageRelease(image);
    return pixels;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureDisplay(
    JNIEnv *env, jclass clazz, jstring displayId, jint x, jint y, jint width, jint height,
    jboolean includeCursor, jintArray result, jobjectArray message) {
    (void)clazz;
    @autoreleasepool {
        CGDirectDisplayID display = 0;
        if (!parseDisplayId(env, displayId, &display) || !isActiveDisplay(display)) {
            return fail(env, result, message, STATUS_DISPLAY_NOT_FOUND, @"No active display with this id");
        }
        DisplayGeometry geometry;
        if (!displayGeometry(display, &geometry)) {
            return fail(env, result, message, STATUS_DISPLAY_NOT_FOUND, @"The display reports no geometry");
        }
        CGFloat scale = (CGFloat)geometry.widthPx / geometry.bounds.size.width;
        if (!(scale > 0)) scale = 1;

        // Region in display pixels, clipped to the display; width <= 0 is the whole display.
        long long left = 0;
        long long top = 0;
        long long right = (long long)geometry.widthPx;
        long long bottom = (long long)geometry.heightPx;
        if (width > 0 && height > 0) {
            long long rx = x;
            long long ry = y;
            long long rr = rx + width;
            long long rb = ry + height;
            if (rx > left) left = rx;
            if (ry > top) top = ry;
            if (rr < right) right = rr;
            if (rb < bottom) bottom = rb;
        }
        if (right <= left || bottom <= top) {
            return fail(env, result, message, STATUS_INVALID_REGION,
                        [NSString stringWithFormat:@"Region does not intersect the %zux%zu display", geometry.widthPx, geometry.heightPx]);
        }
        CGRect regionPx = CGRectMake((CGFloat)left, (CGFloat)top, (CGFloat)(right - left), (CGFloat)(bottom - top));

        if (hasScreenCaptureKit()) {
            if (@available(macOS 14.0, *)) {
                return captureDisplayWithKit(env, display, regionPx, scale, includeCursor, result, message);
            }
        }
        return captureDisplayWithCoreGraphics(env, display, regionPx, scale, result, message);
    }
}

// ---------------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------------

API_AVAILABLE(macos(14.0))
static jintArray captureWindowWithKit(
    JNIEnv *env, CGWindowID windowId, jboolean includeCursor, jintArray result, jobjectArray message) {
    NSString *why = nil;
    SCShareableContent *content = nil;
    jint status = shareableContent(NO, &content, &why);
    if (status != STATUS_OK) return fail(env, result, message, status, why);

    SCWindow *target = nil;
    for (SCWindow *window in content.windows) {
        if (window.windowID == windowId) {
            target = window;
            break;
        }
    }
    if (target == nil) {
        return fail(env, result, message, STATUS_WINDOW_NOT_FOUND,
                    [NSString stringWithFormat:@"ScreenCaptureKit does not list window %u", windowId]);
    }

    SCContentFilter *filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:target];
    CGRect rect = filter.contentRect;
    CGFloat scale = filter.pointPixelScale;
    if (!(scale > 0)) scale = 1;
    size_t w = (size_t)llround(rect.size.width * scale);
    size_t h = (size_t)llround(rect.size.height * scale);
    if (w == 0 || h == 0) {
        return fail(env, result, message, STATUS_WINDOW_NOT_FOUND, @"The window has no visible area");
    }
    SCStreamConfiguration *config = [SCStreamConfiguration new];
    configureCommon(config, includeCursor);
    config.width = w;
    config.height = h;
    config.ignoreShadowsSingleWindow = YES;

    CGImageRef image = NULL;
    status = screenshot(filter, config, &image, &why);
    if (status != STATUS_OK) return fail(env, result, message, status, why);
    jintArray pixels = imageToPixels(env, image, result, message);
    CGImageRelease(image);
    return pixels;
}

static jintArray captureWindowWithCoreGraphics(
    JNIEnv *env, CGWindowID windowId, jintArray result, jobjectArray message) {
    CreateWindowListImageFn create = cgCreateWindowListImage();
    if (create == NULL) {
        return fail(env, result, message, STATUS_UNSUPPORTED, @"CGWindowListCreateImage is unavailable");
    }
    if (!CGPreflightScreenCaptureAccess()) {
        return fail(env, result, message, STATUS_PERMISSION_DENIED, @"Screen recording permission is not granted");
    }
    CGImageRef image = create(CGRectNull, kCGWindowListOptionIncludingWindow, windowId,
                              kCGWindowImageBoundsIgnoreFraming | kCGWindowImageBestResolution);
    if (image == NULL || CGImageGetWidth(image) == 0 || CGImageGetHeight(image) == 0) {
        if (image != NULL) CGImageRelease(image);
        return fail(env, result, message, STATUS_WINDOW_NOT_FOUND,
                    [NSString stringWithFormat:@"No capturable window %u", windowId]);
    }
    jintArray pixels = imageToPixels(env, image, result, message);
    CGImageRelease(image);
    return pixels;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureWindow(
    JNIEnv *env, jclass clazz, jlong windowId, jboolean includeCursor, jintArray result, jobjectArray message) {
    (void)clazz;
    @autoreleasepool {
        if (windowId <= 0 || windowId > (jlong)UINT32_MAX) {
            return fail(env, result, message, STATUS_WINDOW_NOT_FOUND, @"Not a CGWindowID");
        }
        CGWindowID window = (CGWindowID)windowId;
        if (hasScreenCaptureKit()) {
            if (@available(macOS 14.0, *)) {
                return captureWindowWithKit(env, window, includeCursor, result, message);
            }
        }
        return captureWindowWithCoreGraphics(env, window, result, message);
    }
}

// ---------------------------------------------------------------------------------------------
// Permission
// ---------------------------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePermissionStatus(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    // The public API cannot tell "denied" from "never asked" (that is private TCC state), so a
    // missing grant reads as not determined: a request may still prompt.
    return CGPreflightScreenCaptureAccess() ? PERMISSION_GRANTED : PERMISSION_NOT_DETERMINED;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeRequestPermission(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return CGRequestScreenCaptureAccess() ? PERMISSION_GRANTED : PERMISSION_DENIED;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePortalScreenshot(
    JNIEnv *env, jclass clazz, jboolean interactive, jint timeoutMs, jintArray result, jobjectArray message) {
    (void)clazz;
    (void)interactive;
    (void)timeoutMs;
    return fail(env, result, message, STATUS_UNSUPPORTED, @"The screenshot portal is Linux-only");
}

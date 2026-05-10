#import <Cocoa/Cocoa.h>
#include <jni.h>

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_macos_NativeMacBridge_nativeUpdateColors(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {
    if (nsWindowPtr == 0) return;
    NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            id delegate = [window delegate];
            if (delegate && [delegate respondsToSelector:@selector(updateColors)]) {
                [delegate performSelector:@selector(updateColors)];
            }
        }
    });
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_macos_NativeMacBridge_nativeUpdateFullScreenButtons(
    JNIEnv *env, jclass clazz, jlong nsWindowPtr) {
    if (nsWindowPtr == 0) return;
    NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            id delegate = [window delegate];
            if (delegate && [delegate respondsToSelector:@selector(updateFullScreenButtons)]) {
                [delegate performSelector:@selector(updateFullScreenButtons)];
            }
        }
    });
}

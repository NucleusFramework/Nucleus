#import <Foundation/Foundation.h>
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_NativeLayoutDirectionBridge_nativeIsRTL(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        NSString *language = [[NSLocale preferredLanguages] firstObject];
        if (!language) return JNI_FALSE;
        NSLocaleLanguageDirection direction =
            [NSLocale characterDirectionForLanguage:language];
        return direction == NSLocaleLanguageDirectionRightToLeft
               ? JNI_TRUE
               : JNI_FALSE;
    }
}

// Temperature sensors — Windows stub.
// Per-core CPU temperature on Windows requires Ring 0 MSR access which is not
// available through any standard user-mode API. This file returns 0 components.
// On Linux and macOS the native implementations read /sys/class/hwmon and
// IOKit respectively, which work without elevated privileges.

#include "nucleus_system_info_common.h"

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCount(
    JNIEnv *env, jclass clazz) {
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentLabels(
    JNIEnv *env, jclass clazz) {
    return to_string_array(env, NULL, 0);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentTemperatures(
    JNIEnv *env, jclass clazz) {
    return (*env)->NewFloatArray(env, 0);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentMaxTemperatures(
    JNIEnv *env, jclass clazz) {
    return (*env)->NewFloatArray(env, 0);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeComponentCriticalTemperatures(
    JNIEnv *env, jclass clazz) {
    return (*env)->NewFloatArray(env, 0);
}

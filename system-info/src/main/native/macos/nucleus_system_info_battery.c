// Battery information via IOKit IOPMPowerSource on macOS.

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>
#include <limits.h>

// Helper: get the first IOPMPowerSource properties dictionary. Caller must CFRelease.
static CFDictionaryRef copy_battery_properties(void) {
    CFMutableDictionaryRef matching = IOServiceMatching("IOPMPowerSource");
    if (!matching) return NULL;

    io_iterator_t iterator = 0;
    kern_return_t kr = IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator);
    if (kr != KERN_SUCCESS || !iterator) return NULL;

    io_object_t service = IOIteratorNext(iterator);
    IOObjectRelease(iterator);
    if (!service) return NULL;

    CFMutableDictionaryRef props = NULL;
    kr = IORegistryEntryCreateCFProperties(service, &props, kCFAllocatorDefault, 0);
    IOObjectRelease(service);
    if (kr != KERN_SUCCESS || !props) return NULL;

    return props;
}

static Boolean get_bool(CFDictionaryRef dict, CFStringRef key) {
    CFBooleanRef val = CFDictionaryGetValue(dict, key);
    if (!val || CFGetTypeID(val) != CFBooleanGetTypeID()) return false;
    return CFBooleanGetValue(val);
}

static int32_t get_int32(CFDictionaryRef dict, CFStringRef key, int32_t fallback) {
    CFNumberRef val = CFDictionaryGetValue(dict, key);
    if (!val || CFGetTypeID(val) != CFNumberGetTypeID()) return fallback;
    int32_t result = fallback;
    CFNumberGetValue(val, kCFNumberSInt32Type, &result);
    return result;
}

static char *get_string(CFDictionaryRef dict, CFStringRef key) {
    CFStringRef val = CFDictionaryGetValue(dict, key);
    if (!val || CFGetTypeID(val) != CFStringGetTypeID()) return NULL;
    CFIndex len = CFStringGetLength(val);
    CFIndex maxSize = CFStringGetMaximumSizeForEncoding(len, kCFStringEncodingUTF8) + 1;
    char *buf = (char *)malloc((size_t)maxSize);
    if (!buf) return NULL;
    if (!CFStringGetCString(val, buf, maxSize, kCFStringEncodingUTF8)) {
        free(buf);
        return NULL;
    }
    return buf;
}

// On Apple Silicon, CurrentCapacity/MaxCapacity return percentages.
// Prefer AppleRawCurrentCapacity/AppleRawMaxCapacity when available.
static int32_t get_raw_or_standard_capacity(CFDictionaryRef dict, CFStringRef rawKey, CFStringRef stdKey) {
    int32_t raw = get_int32(dict, rawKey, -1);
    if (raw >= 0) return raw;
    return get_int32(dict, stdKey, 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryPresent(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return JNI_FALSE;
    Boolean installed = get_bool(props, CFSTR("BatteryInstalled"));
    CFRelease(props);
    return installed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryExternalConnected(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return JNI_FALSE;
    jboolean result = get_bool(props, CFSTR("ExternalConnected")) ? JNI_TRUE : JNI_FALSE;
    CFRelease(props);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryIsCharging(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return JNI_FALSE;
    jboolean result = get_bool(props, CFSTR("IsCharging")) ? JNI_TRUE : JNI_FALSE;
    CFRelease(props);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryFullyCharged(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return JNI_FALSE;
    jboolean result = get_bool(props, CFSTR("FullyCharged")) ? JNI_TRUE : JNI_FALSE;
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryCurrentCapacity(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    jint result = get_raw_or_standard_capacity(props,
        CFSTR("AppleRawCurrentCapacity"), CFSTR("CurrentCapacity"));
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryMaxCapacity(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    jint result = get_raw_or_standard_capacity(props,
        CFSTR("AppleRawMaxCapacity"), CFSTR("MaxCapacity"));
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryDesignCapacity(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    jint result = get_int32(props, CFSTR("DesignCapacity"), 0);
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryCycleCount(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    jint result = get_int32(props, CFSTR("CycleCount"), 0);
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryVoltage(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    jint result = get_int32(props, CFSTR("Voltage"), 0);
    CFRelease(props);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryAmperage(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return 0;
    int32_t amp = get_int32(props, CFSTR("Amperage"), 0);
    CFRelease(props);
    return (jint)(amp < 0 ? -amp : amp);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryTemperature(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return (jfloat)NAN;
    int32_t raw = get_int32(props, CFSTR("Temperature"), INT_MIN);
    CFRelease(props);
    if (raw == INT_MIN) return (jfloat)NAN;
    return (jfloat)(raw / 100.0f);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryTimeRemaining(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return -1;
    int32_t time = get_int32(props, CFSTR("TimeRemaining"), -1);
    CFRelease(props);
    // INT_MAX is a sentinel for "calculating" or unknown
    if (time == INT_MAX || time < 0) return -1;
    return (jint)time;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryManufacturer(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return NULL;
    char *val = get_string(props, CFSTR("Manufacturer"));
    CFRelease(props);
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatteryModelName(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return NULL;
    char *val = get_string(props, CFSTR("DeviceName"));
    CFRelease(props);
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBatterySerialNumber(
    JNIEnv *env, jclass clazz) {
    CFDictionaryRef props = copy_battery_properties();
    if (!props) return NULL;
    char *val = get_string(props, CFSTR("Serial"));
    CFRelease(props);
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

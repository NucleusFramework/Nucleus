// Hardware information: motherboard and product details.
// Sources: IOKit IOPlatformExpertDevice, sysctl hw.model

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>

// Read a string property from IOPlatformExpertDevice
static char *read_platform_property(const char *property_name) {
    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault,
        IOServiceMatching("IOPlatformExpertDevice"));
    if (!service) return NULL;

    CFStringRef key = CFStringCreateWithCString(kCFAllocatorDefault, property_name,
                                                 kCFStringEncodingUTF8);
    CFTypeRef value = IORegistryEntryCreateCFProperty(service, key, kCFAllocatorDefault, 0);
    CFRelease(key);
    IOObjectRelease(service);

    if (!value) return NULL;

    char *result = NULL;
    CFTypeID type = CFGetTypeID(value);

    if (type == CFStringGetTypeID()) {
        CFIndex len = CFStringGetMaximumSizeForEncoding(
            CFStringGetLength((CFStringRef)value), kCFStringEncodingUTF8) + 1;
        result = (char *)malloc(len);
        if (result) {
            if (!CFStringGetCString((CFStringRef)value, result, len, kCFStringEncodingUTF8)) {
                free(result);
                result = NULL;
            }
        }
    } else if (type == CFDataGetTypeID()) {
        // Some properties (e.g., board-id, manufacturer) are stored as CFData (bytes)
        CFIndex len = CFDataGetLength((CFDataRef)value);
        result = (char *)malloc(len + 1);
        if (result) {
            CFDataGetBytes((CFDataRef)value, CFRangeMake(0, len), (UInt8 *)result);
            result[len] = '\0';
            // Trim trailing NUL bytes that IOKit sometimes pads
            while (len > 0 && result[len - 1] == '\0') len--;
            result[len] = '\0';
        }
    }

    CFRelease(value);
    return result;
}

// Motherboard — on Mac, "board" maps to the logic board
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeMotherboardName(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("board-id");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeMotherboardVendor(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("manufacturer");
    if (!val) return to_jstring(env, "Apple Inc.");
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeMotherboardVersion(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("version");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeMotherboardSerial(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("IOPlatformSerialNumber");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeMotherboardAssetTag(
    JNIEnv *env, jclass clazz) {
    // Macs don't have asset tags in IORegistry
    return NULL;
}

// Product
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductName(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("product-name");
    if (!val) {
        // Fallback to hw.model
        val = sysctl_string("hw.model");
    }
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductFamily(
    JNIEnv *env, jclass clazz) {
    // Derive family from model identifier (e.g., "MacBookPro18,1" -> "MacBook Pro")
    char *model = sysctl_string("hw.model");
    if (!model) return NULL;
    const char *family = NULL;
    if (strncmp(model, "MacBookPro", 10) == 0) family = "MacBook Pro";
    else if (strncmp(model, "MacBookAir", 10) == 0) family = "MacBook Air";
    else if (strncmp(model, "MacBook", 7) == 0) family = "MacBook";
    else if (strncmp(model, "Macmini", 7) == 0) family = "Mac mini";
    else if (strncmp(model, "MacPro", 6) == 0) family = "Mac Pro";
    else if (strncmp(model, "iMacPro", 7) == 0) family = "iMac Pro";
    else if (strncmp(model, "iMac", 4) == 0) family = "iMac";
    else if (strncmp(model, "Mac", 3) == 0) family = "Mac";
    free(model);
    return family ? to_jstring(env, family) : NULL;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductSerial(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("IOPlatformSerialNumber");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductSku(
    JNIEnv *env, jclass clazz) {
    // Macs don't have SKU in IORegistry; use hw.model as closest equivalent
    char *val = sysctl_string("hw.model");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductUuid(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("IOPlatformUUID");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductVersion(
    JNIEnv *env, jclass clazz) {
    char *val = sysctl_string("hw.model");
    if (!val) return NULL;
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProductVendor(
    JNIEnv *env, jclass clazz) {
    char *val = read_platform_property("manufacturer");
    if (!val) return to_jstring(env, "Apple Inc.");
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

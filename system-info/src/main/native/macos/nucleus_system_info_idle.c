// Idle time detection via IOKit HIDIdleTime on macOS.

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeIdleTimeSeconds(
    JNIEnv *env, jclass clazz) {
    io_iterator_t iter = 0;
    if (IOServiceGetMatchingServices(kIOMainPortDefault,
            IOServiceMatching("IOHIDSystem"), &iter) != KERN_SUCCESS) {
        return (jlong)-1;
    }

    io_registry_entry_t entry = IOIteratorNext(iter);
    IOObjectRelease(iter);
    if (!entry) return (jlong)-1;

    CFMutableDictionaryRef dict = NULL;
    kern_return_t kr = IORegistryEntryCreateCFProperties(
        entry, &dict, kCFAllocatorDefault, 0);
    IOObjectRelease(entry);
    if (kr != KERN_SUCCESS || !dict) return (jlong)-1;

    int64_t nanoseconds = 0;
    CFNumberRef obj = CFDictionaryGetValue(dict, CFSTR("HIDIdleTime"));
    if (obj && CFGetTypeID(obj) == CFNumberGetTypeID()) {
        CFNumberGetValue(obj, kCFNumberSInt64Type, &nanoseconds);
    }
    CFRelease(dict);

    if (nanoseconds <= 0) return (jlong)0;
    return (jlong)(nanoseconds / 1000000000LL);
}

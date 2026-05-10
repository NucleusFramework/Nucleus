// Disk information: name, filesystem, mount point, space, kind, removable, read-only.
// Sources: getmntinfo(), statfs, IOKit for disk type and external detection
//
// APFS volumes (disk7s2) are synthesized by AppleAPFSContainer and don't appear
// as IOMedia children of IOBlockStorageDriver.  To find the physical device we
// strip the volume suffix to get the "whole disk" BSD name (disk7), match that
// with IOBSDNameMatching, and walk up to IOBlockStorageDriver / IOBlockStorageDevice
// for Device Characteristics and Protocol Characteristics.

#include "nucleus_system_info_common.h"
#include <sys/mount.h>
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>

#define MAX_DISKS 256

typedef struct {
    char name[256];
    char fs_type[64];
    char mount_point[512];
    unsigned long long total_space;
    unsigned long long available_space;
    char kind[16];
    int removable;
    int read_only;
} disk_entry_t;

static int g_disk_count = 0;
static disk_entry_t g_disks[MAX_DISKS];

// Check if a filesystem type is a real disk (not virtual/pseudo)
static int is_real_fs(const char *fs_type, const char *mount_from, const char *mount_on) {
    if (strcmp(fs_type, "devfs") == 0) return 0;
    if (strcmp(fs_type, "autofs") == 0) return 0;
    if (strcmp(fs_type, "nullfs") == 0) return 0;
    if (strcmp(fs_type, "vmhgfs") == 0) return 0;
    if (strcmp(mount_on, "/dev") == 0) return 0;
    if (strncmp(mount_from, "map ", 4) == 0) return 0;
    return 1;
}

// Strip partition/slice suffix from a BSD name to get the whole disk.
// "disk7s2" -> "disk7",  "disk0s1" -> "disk0"
static void get_whole_disk_name(const char *bsd_name, char *out, size_t out_size) {
    strncpy(out, bsd_name, out_size - 1);
    out[out_size - 1] = '\0';
    // Remove /dev/ prefix if present
    char *name = out;
    if (strncmp(name, "/dev/", 5) == 0) {
        memmove(out, out + 5, strlen(out + 5) + 1);
    }
    // Find 's' separator (e.g., "disk7s2" -> strip "s2")
    char *s = strrchr(out, 's');
    if (s && s > out && s[-1] >= '0' && s[-1] <= '9' && s[1] >= '0' && s[1] <= '9') {
        *s = '\0';
    }
}

// Walk up the IORegistry from a service to find a parent with "Device Characteristics"
// or "Protocol Characteristics". Returns a retained CFDictionaryRef or NULL.
static CFDictionaryRef find_ancestor_property(io_service_t start, CFStringRef prop_key) {
    io_service_t current = start;
    IOObjectRetain(current);
    int depth = 0;
    while (current && depth < 30) {
        CFTypeRef val = IORegistryEntryCreateCFProperty(current, prop_key, kCFAllocatorDefault, 0);
        if (val) {
            IOObjectRelease(current);
            return (CFDictionaryRef)val;
        }
        io_service_t parent = 0;
        kern_return_t kr = IORegistryEntryGetParentEntry(current, kIOServicePlane, &parent);
        IOObjectRelease(current);
        if (kr != KERN_SUCCESS) break;
        current = parent;
        depth++;
    }
    if (current) IOObjectRelease(current);
    return NULL;
}

typedef struct {
    char kind[16];     // "SSD", "HDD", or "Unknown"
    int external;      // 1 if external, 0 if internal
} disk_hw_info_t;

// Look up physical disk hardware info (kind + external) via IOKit for a BSD name
static void lookup_disk_hw_info(const char *bsd_name, disk_hw_info_t *hw) {
    strncpy(hw->kind, "Unknown", sizeof(hw->kind));
    hw->external = 0;

    // Get the whole-disk BSD name
    char whole[64];
    get_whole_disk_name(bsd_name, whole, sizeof(whole));

    CFMutableDictionaryRef matching = IOBSDNameMatching(kIOMainPortDefault, 0, whole);
    if (!matching) return;

    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault, matching);
    if (!service) return;

    // Look for Device Characteristics (on IOBlockStorageDriver or device)
    CFDictionaryRef dev_chars = find_ancestor_property(service, CFSTR("Device Characteristics"));
    if (dev_chars) {
        CFStringRef medium = CFDictionaryGetValue(dev_chars, CFSTR("Medium Type"));
        if (medium) {
            if (CFStringCompare(medium, CFSTR("Solid State"), 0) == kCFCompareEqualTo) {
                strncpy(hw->kind, "SSD", sizeof(hw->kind));
            } else if (CFStringCompare(medium, CFSTR("Rotational"), 0) == kCFCompareEqualTo) {
                strncpy(hw->kind, "HDD", sizeof(hw->kind));
            }
        }
        CFRelease(dev_chars);
    }

    // Look for Protocol Characteristics to detect external
    CFDictionaryRef proto_chars = find_ancestor_property(service, CFSTR("Protocol Characteristics"));
    if (proto_chars) {
        CFStringRef location = CFDictionaryGetValue(proto_chars, CFSTR("Physical Interconnect Location"));
        if (location && CFStringCompare(location, CFSTR("External"), 0) == kCFCompareEqualTo) {
            hw->external = 1;
        }
        CFRelease(proto_chars);
    }

    IOObjectRelease(service);
}

static void refresh_disks(void) {
    g_disk_count = 0;
    struct statfs *mounts = NULL;
    int count = getmntinfo(&mounts, MNT_NOWAIT);
    if (count <= 0 || !mounts) return;

    for (int i = 0; i < count && g_disk_count < MAX_DISKS; i++) {
        struct statfs *sf = &mounts[i];
        if (!is_real_fs(sf->f_fstypename, sf->f_mntfromname, sf->f_mntonname)) continue;

        disk_entry_t *d = &g_disks[g_disk_count];
        memset(d, 0, sizeof(disk_entry_t));
        strncpy(d->name, sf->f_mntfromname, sizeof(d->name) - 1);
        strncpy(d->fs_type, sf->f_fstypename, sizeof(d->fs_type) - 1);
        strncpy(d->mount_point, sf->f_mntonname, sizeof(d->mount_point) - 1);
        d->total_space = (unsigned long long)sf->f_blocks * sf->f_bsize;
        d->available_space = (unsigned long long)sf->f_bavail * sf->f_bsize;
        d->read_only = (sf->f_flags & MNT_RDONLY) ? 1 : 0;

        // Query IOKit for hardware info (disk kind + external status)
        disk_hw_info_t hw;
        lookup_disk_hw_info(sf->f_mntfromname, &hw);

        // Fallback: APFS is almost always on SSD
        if (strcmp(hw.kind, "Unknown") == 0 && strcmp(sf->f_fstypename, "apfs") == 0) {
            strncpy(hw.kind, "SSD", sizeof(hw.kind));
        }
        strncpy(d->kind, hw.kind, sizeof(d->kind) - 1);
        d->removable = hw.external;

        g_disk_count++;
    }
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return (jint)g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].name;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].fs_type;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].mount_point;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskTotalSpaces(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_disk_count * sizeof(jlong));
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].total_space;
    jlongArray result = (*env)->NewLongArray(env, g_disk_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskAvailableSpaces(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_disk_count * sizeof(jlong));
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].available_space;
    jlongArray result = (*env)->NewLongArray(env, g_disk_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].kind;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskRemovable(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].removable ? JNI_TRUE : JNI_FALSE;
    jbooleanArray result = (*env)->NewBooleanArray(env, g_disk_count);
    (*env)->SetBooleanArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskReadOnly(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].read_only ? JNI_TRUE : JNI_FALSE;
    jbooleanArray result = (*env)->NewBooleanArray(env, g_disk_count);
    (*env)->SetBooleanArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

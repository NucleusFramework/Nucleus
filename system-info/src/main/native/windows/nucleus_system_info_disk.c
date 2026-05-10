// Disk information: volumes, file systems, mount points, space, kind, removable, read-only.
// Sources: FindFirstVolumeW, GetVolumeInformationW, GetDiskFreeSpaceExW, DeviceIoControl

#include "nucleus_system_info_common.h"
#include <winioctl.h>

#define MAX_DISKS 128

typedef struct {
    char name[256];
    char file_system[64];
    char mount_point[512];
    ULONGLONG total_space;
    ULONGLONG available_space;
    char kind[16]; // "SSD", "HDD", "Unknown"
    BOOL removable;
    BOOL read_only;
} disk_entry_t;

static disk_entry_t g_disks[MAX_DISKS];
static int g_disk_count = 0;

// Detect if a volume is SSD by checking seek penalty property
static const char *detect_disk_kind(const wchar_t *volume_path) {
    // volume_path like "\\?\Volume{GUID}\"
    // We need "\\?\Volume{GUID}" (no trailing backslash) for CreateFileW
    wchar_t dev_path[256];
    wcsncpy(dev_path, volume_path, 255);
    dev_path[255] = L'\0';
    size_t len = wcslen(dev_path);
    if (len > 0 && dev_path[len - 1] == L'\\') dev_path[len - 1] = L'\0';

    HANDLE hDev = CreateFileW(dev_path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE,
                               NULL, OPEN_EXISTING, 0, NULL);
    if (hDev == INVALID_HANDLE_VALUE) return "Unknown";

    STORAGE_PROPERTY_QUERY query;
    memset(&query, 0, sizeof(query));
    query.PropertyId = StorageDeviceSeekPenaltyProperty;
    query.QueryType = PropertyStandardQuery;

    DEVICE_SEEK_PENALTY_DESCRIPTOR desc;
    DWORD bytes_returned = 0;
    BOOL ok = DeviceIoControl(hDev, IOCTL_STORAGE_QUERY_PROPERTY,
                               &query, sizeof(query), &desc, sizeof(desc),
                               &bytes_returned, NULL);
    CloseHandle(hDev);

    if (ok && bytes_returned >= sizeof(desc)) {
        return desc.IncursSeekPenalty ? "HDD" : "SSD";
    }
    return "Unknown";
}

static void refresh_disks(void) {
    g_disk_count = 0;

    wchar_t volume_name[MAX_PATH];
    HANDLE hFind = FindFirstVolumeW(volume_name, MAX_PATH);
    if (hFind == INVALID_HANDLE_VALUE) return;

    do {
        if (g_disk_count >= MAX_DISKS) break;
        disk_entry_t *d = &g_disks[g_disk_count];
        memset(d, 0, sizeof(*d));

        // Get drive type
        UINT drive_type = GetDriveTypeW(volume_name);
        if (drive_type == DRIVE_NO_ROOT_DIR || drive_type == DRIVE_UNKNOWN) continue;

        d->removable = (drive_type == DRIVE_REMOVABLE || drive_type == DRIVE_CDROM);

        // Get volume name and file system
        wchar_t vol_label[256], fs_name[64];
        DWORD serial = 0, max_comp = 0, flags = 0;
        if (GetVolumeInformationW(volume_name, vol_label, 256, &serial, &max_comp, &flags, fs_name, 64)) {
            char *label_utf8 = wchar_to_utf8(vol_label);
            char *fs_utf8 = wchar_to_utf8(fs_name);
            strncpy(d->name, label_utf8 ? label_utf8 : "", sizeof(d->name) - 1);
            strncpy(d->file_system, fs_utf8 ? fs_utf8 : "", sizeof(d->file_system) - 1);
            if (label_utf8) free(label_utf8);
            if (fs_utf8) free(fs_utf8);
            d->read_only = (flags & FILE_READ_ONLY_VOLUME) ? TRUE : FALSE;
        }

        // Get mount point (drive letter)
        wchar_t paths[512];
        DWORD paths_len = 0;
        if (GetVolumePathNamesForVolumeNameW(volume_name, paths, 512, &paths_len)) {
            // paths is a multi-string (null-separated, double-null terminated)
            // Take the first path
            char *mp = wchar_to_utf8(paths);
            strncpy(d->mount_point, mp ? mp : "", sizeof(d->mount_point) - 1);
            if (mp) free(mp);
        }

        // Skip volumes with no mount point
        if (d->mount_point[0] == '\0') continue;

        // Get space
        ULARGE_INTEGER free_bytes, total_bytes, free_bytes_caller;
        if (GetDiskFreeSpaceExW(volume_name, &free_bytes_caller, &total_bytes, &free_bytes)) {
            d->total_space = total_bytes.QuadPart;
            d->available_space = free_bytes_caller.QuadPart;
        }

        // Detect SSD vs HDD
        const char *kind = detect_disk_kind(volume_name);
        strncpy(d->kind, kind, sizeof(d->kind) - 1);

        g_disk_count++;
    } while (FindNextVolumeW(hFind, volume_name, MAX_PATH));

    FindVolumeClose(hFind);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) names[i] = g_disks[i].name;
    return to_string_array(env, names, g_disk_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    const char *fs[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) fs[i] = g_disks[i].file_system;
    return to_string_array(env, fs, g_disk_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    const char *mps[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) mps[i] = g_disks[i].mount_point;
    return to_string_array(env, mps, g_disk_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskTotalSpaces(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_disk_count);
    jlong *vals = (jlong *)malloc(g_disk_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].total_space;
    (*env)->SetLongArrayRegion(env, arr, 0, g_disk_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskAvailableSpaces(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_disk_count);
    jlong *vals = (jlong *)malloc(g_disk_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].available_space;
    (*env)->SetLongArrayRegion(env, arr, 0, g_disk_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    const char *kinds[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) kinds[i] = g_disks[i].kind;
    return to_string_array(env, kinds, g_disk_count);
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskRemovable(
    JNIEnv *env, jclass clazz) {
    jbooleanArray arr = (*env)->NewBooleanArray(env, g_disk_count);
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    if (!vals) return arr;
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].removable ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, arr, 0, g_disk_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskReadOnly(
    JNIEnv *env, jclass clazz) {
    jbooleanArray arr = (*env)->NewBooleanArray(env, g_disk_count);
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    if (!vals) return arr;
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].read_only ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, arr, 0, g_disk_count, vals);
    free(vals);
    return arr;
}

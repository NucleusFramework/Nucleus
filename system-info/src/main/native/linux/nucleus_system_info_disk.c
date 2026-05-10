// Disk information: name, filesystem, mount point, space, kind, removable, read-only.
// Sources: /proc/mounts, statvfs(), /sys/block/

#include "nucleus_system_info_common.h"
#include <sys/statvfs.h>
#include <mntent.h>

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

// Check if a filesystem type is a real disk (not virtual)
static int is_real_fs(const char *fs_type) {
    static const char *virtual_fs[] = {
        "sysfs", "proc", "tmpfs", "devtmpfs", "devpts", "cgroup", "cgroup2",
        "pstore", "debugfs", "securityfs", "configfs", "fusectl", "mqueue",
        "hugetlbfs", "tracefs", "binfmt_misc", "autofs", "rpc_pipefs",
        "nfsd", "overlay", "squashfs", "fuse.portal", "fuse.gvfsd-fuse",
        NULL
    };
    for (int i = 0; virtual_fs[i]; i++) {
        if (strcmp(fs_type, virtual_fs[i]) == 0) return 0;
    }
    return 1;
}

// Determine disk kind from /sys/block/<dev>/queue/rotational
static const char *detect_disk_kind(const char *dev_name) {
    // Extract base device name (e.g., "sda" from "/dev/sda1")
    const char *base = dev_name;
    if (strncmp(base, "/dev/", 5) == 0) base += 5;
    // Strip partition number and "mapper/" prefix
    char clean[64];
    strncpy(clean, base, sizeof(clean) - 1);
    clean[sizeof(clean) - 1] = '\0';
    // Remove trailing digits (partition number)
    size_t len = strlen(clean);
    while (len > 0 && clean[len - 1] >= '0' && clean[len - 1] <= '9') clean[--len] = '\0';
    // Remove 'p' suffix for nvme (nvme0n1p1 -> nvme0n1)
    if (len > 0 && clean[len - 1] == 'p') clean[--len] = '\0';

    char path[256];
    snprintf(path, sizeof(path), "/sys/block/%s/queue/rotational", clean);
    char buf[8];
    if (read_file_line(path, buf, sizeof(buf)) > 0) {
        return (buf[0] == '1') ? "HDD" : "SSD";
    }
    return "Unknown";
}

static void refresh_disks(void) {
    g_disk_count = 0;
    FILE *f = setmntent("/proc/mounts", "r");
    if (!f) return;
    struct mntent *ent;
    while ((ent = getmntent(f)) && g_disk_count < MAX_DISKS) {
        if (!is_real_fs(ent->mnt_type)) continue;
        if (strncmp(ent->mnt_fsname, "/dev/", 5) != 0 &&
            strcmp(ent->mnt_type, "nfs") != 0 &&
            strcmp(ent->mnt_type, "nfs4") != 0 &&
            strcmp(ent->mnt_type, "cifs") != 0 &&
            strcmp(ent->mnt_type, "smb") != 0) continue;

        disk_entry_t *d = &g_disks[g_disk_count];
        strncpy(d->name, ent->mnt_fsname, sizeof(d->name) - 1);
        strncpy(d->fs_type, ent->mnt_type, sizeof(d->fs_type) - 1);
        strncpy(d->mount_point, ent->mnt_dir, sizeof(d->mount_point) - 1);

        struct statvfs st;
        if (statvfs(ent->mnt_dir, &st) == 0) {
            d->total_space = (unsigned long long)st.f_blocks * st.f_frsize;
            d->available_space = (unsigned long long)st.f_bavail * st.f_frsize;
            d->read_only = (st.f_flag & ST_RDONLY) ? 1 : 0;
        }

        strncpy(d->kind, detect_disk_kind(ent->mnt_fsname), sizeof(d->kind) - 1);

        // Check removable via /sys/block/<dev>/removable
        char dev_base[64];
        const char *base = ent->mnt_fsname;
        if (strncmp(base, "/dev/", 5) == 0) base += 5;
        strncpy(dev_base, base, sizeof(dev_base) - 1);
        dev_base[sizeof(dev_base) - 1] = '\0';
        size_t dlen = strlen(dev_base);
        while (dlen > 0 && dev_base[dlen - 1] >= '0' && dev_base[dlen - 1] <= '9') dev_base[--dlen] = '\0';
        if (dlen > 0 && dev_base[dlen - 1] == 'p') dev_base[--dlen] = '\0';
        char rem_path[256];
        snprintf(rem_path, sizeof(rem_path), "/sys/block/%s/removable", dev_base);
        char rem_buf[8];
        d->removable = (read_file_line(rem_path, rem_buf, sizeof(rem_buf)) > 0 && rem_buf[0] == '1') ? 1 : 0;

        g_disk_count++;
    }
    endmntent(f);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return (jint)g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].name;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].fs_type;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].mount_point;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskTotalSpaces(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskAvailableSpaces(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].kind;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskRemovable(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskReadOnly(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].read_only ? JNI_TRUE : JNI_FALSE;
    jbooleanArray result = (*env)->NewBooleanArray(env, g_disk_count);
    (*env)->SetBooleanArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

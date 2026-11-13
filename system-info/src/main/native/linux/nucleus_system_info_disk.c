// Disk information: mounted volumes with space, kind, removable, read-only,
// volume id and connection type, plus a mount table change monitor.
//
// Sources:
// - /proc/self/mountinfo: what is mounted, from which device (major:minor),
//   with which options. Polling it (POLLPRI) is how the kernel signals a mount
//   table change; udev never sees mounts.
// - /sys/dev/block and /sys/class/block: partition -> whole disk, stacked
//   devices (dm, LUKS, LVM, md, bcache) -> their slaves, and the device chain
//   up to the bus. Read directly rather than through the udev database, which
//   is absent in Flatpak.
// - /dev/disk/by-uuid, /dev/disk/by-partuuid: volume ids.
// - statvfs(): space, on a throwaway thread so a dead NFS/CIFS server (whose
//   statvfs blocks in an unkillable state) cannot hang the caller.

#define _GNU_SOURCE
#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/sysmacros.h>
#include <time.h>

#define MAX_DISKS 256
#define MAX_STACK_DEPTH 8
#define STATVFS_TIMEOUT_MS 1000

// Mirrored by the declaration order of dev.nucleusframework.systeminfo.model.DiskConnection.
enum {
    CONNECTION_UNKNOWN,
    CONNECTION_INTERNAL,
    CONNECTION_EXTERNAL,
    CONNECTION_NETWORK,
    CONNECTION_VIRTUAL,
};

typedef struct {
    char name[256];
    char fs_type[64];
    char mount_point[512];
    unsigned long long total_space;
    unsigned long long available_space;
    char kind[16];
    int removable;
    int read_only;
    char volume_id[256];
    int connection;
} disk_entry_t;

// Read back by the nativeDisk*() calls right after nativeDiskCount(); the Kotlin
// side serializes the whole sequence.
static int g_disk_count = 0;
static disk_entry_t g_disks[MAX_DISKS];

// ---------------------------------------------------------------------------
// File system filters
// ---------------------------------------------------------------------------

// Check if a filesystem type is a real disk (not virtual)
static int is_real_fs(const char *fs_type) {
    static const char *virtual_fs[] = {
        "sysfs", "proc", "tmpfs", "devtmpfs", "devpts", "cgroup", "cgroup2",
        "pstore", "debugfs", "securityfs", "configfs", "fusectl", "mqueue",
        "hugetlbfs", "tracefs", "binfmt_misc", "autofs", "rpc_pipefs",
        "nfsd", "overlay", "squashfs", "fuse.portal", "fuse.gvfsd-fuse",
        "nsfs", "efivarfs", "bpf", "ramfs",
        NULL
    };
    for (int i = 0; virtual_fs[i]; i++) {
        if (strcmp(fs_type, virtual_fs[i]) == 0) return 0;
    }
    return 1;
}

static int is_network_fs(const char *fs_type, const char *source) {
    static const char *network_fs[] = {
        "nfs", "nfs4", "cifs", "smb3", "smbfs", "ncpfs", "afs", "ceph",
        "glusterfs", "9p", "davfs", "fuse.sshfs", "fuse.rclone", "fuse.s3fs",
        NULL
    };
    for (int i = 0; network_fs[i]; i++) {
        if (strcmp(fs_type, network_fs[i]) == 0) return 1;
    }
    // Other FUSE remotes: "user@host:/path", "remote:", "//server/share".
    return strncmp(fs_type, "fuse.", 5) == 0 &&
           (strncmp(source, "//", 2) == 0 || strchr(source, ':') != NULL);
}

// ---------------------------------------------------------------------------
// mountinfo parsing
// ---------------------------------------------------------------------------

// Decodes the \ooo escapes mountinfo uses for spaces, tabs, newlines and backslashes.
static void unescape_octal(char *s) {
    char *out = s;
    while (*s) {
        if (s[0] == '\\' && s[1] >= '0' && s[1] <= '7' && s[2] >= '0' && s[2] <= '7' &&
            s[3] >= '0' && s[3] <= '7') {
            *out++ = (char)(((s[1] - '0') << 6) | ((s[2] - '0') << 3) | (s[3] - '0'));
            s += 4;
        } else {
            *out++ = *s++;
        }
    }
    *out = '\0';
}

static int has_option(const char *options, const char *option) {
    size_t len = strlen(option);
    for (const char *p = options; p && *p; p = strchr(p, ',') ? strchr(p, ',') + 1 : NULL) {
        if (strncmp(p, option, len) == 0 && (p[len] == ',' || p[len] == '\0')) return 1;
    }
    return 0;
}

typedef struct {
    unsigned int major_id;
    unsigned int minor_id;
    char mount_point[512];
    char options[256];
    char fs_type[64];
    char source[256];
    char super_options[256];
} mount_line_t;

// "36 35 98:0 /root /mnt rw,noatime master:1 - ext3 /dev/sda1 rw,errors=continue"
static int parse_mount_line(char *line, mount_line_t *m) {
    char *fields[6];
    char *save = NULL;
    char *token = strtok_r(line, " \n", &save);
    int count = 0;
    while (token && count < 6) {
        fields[count++] = token;
        token = strtok_r(NULL, " \n", &save);
    }
    if (count < 6) return 0;
    // Optional fields (shared:N, master:N, …) run until the "-" separator.
    while (token && strcmp(token, "-") != 0) token = strtok_r(NULL, " \n", &save);
    if (!token) return 0;
    char *fs_type = strtok_r(NULL, " \n", &save);
    char *source = strtok_r(NULL, " \n", &save);
    char *super_options = strtok_r(NULL, " \n", &save);
    if (!fs_type || !source) return 0;

    if (sscanf(fields[2], "%u:%u", &m->major_id, &m->minor_id) != 2) return 0;
    snprintf(m->mount_point, sizeof(m->mount_point), "%s", fields[4]);
    snprintf(m->options, sizeof(m->options), "%s", fields[5]);
    snprintf(m->fs_type, sizeof(m->fs_type), "%s", fs_type);
    snprintf(m->source, sizeof(m->source), "%s", source);
    snprintf(m->super_options, sizeof(m->super_options), "%s", super_options ? super_options : "");
    unescape_octal(m->mount_point);
    unescape_octal(m->source);
    return 1;
}

// ---------------------------------------------------------------------------
// Block device resolution and classification
// ---------------------------------------------------------------------------

// The /sys/class/block name of the mounted device ("nvme0n1p2", "dm-0", "sdb1").
// btrfs reports an anonymous 0:N device in mountinfo, so fall back to the source node.
static int block_name_of(const mount_line_t *m, char *out, size_t out_size) {
    unsigned int major_id = m->major_id;
    unsigned int minor_id = m->minor_id;
    char link[64];
    char target[PATH_MAX];
    for (int attempt = 0; attempt < 2; attempt++) {
        snprintf(link, sizeof(link), "/sys/dev/block/%u:%u", major_id, minor_id);
        ssize_t len = readlink(link, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            const char *base = strrchr(target, '/');
            snprintf(out, out_size, "%s", base ? base + 1 : target);
            return 1;
        }
        struct stat st;
        if (attempt > 0 || stat(m->source, &st) != 0 || !S_ISBLK(st.st_mode)) break;
        major_id = major(st.st_rdev);
        minor_id = minor(st.st_rdev);
    }
    return 0;
}

static int starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static int has_subsystem(const char *device_dir, const char *subsystem) {
    char link[PATH_MAX];
    char target[PATH_MAX];
    snprintf(link, sizeof(link), "%s/subsystem", device_dir);
    ssize_t len = readlink(link, target, sizeof(target) - 1);
    if (len <= 0) return 0;
    target[len] = '\0';
    const char *base = strrchr(target, '/');
    return strcmp(base ? base + 1 : target, subsystem) == 0;
}

// Walks the device chain upwards. The closest device that says "fixed" or
// "removable" in its removable attribute decides: the kernel sets it from the
// firmware's description of the port, which is right for USB, Thunderbolt and
// external PCIe alike. Same walk as util-linux sysfs_devchain_is_removable()
// (public domain), the source of lsblk's HOTPLUG column. When no device in the
// chain knows, a USB or FireWire ancestor still means external.
static int chain_connection(const char *device_path) {
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s", device_path);
    int on_hotplug_bus = 0;
    while (strlen(dir) > strlen("/sys/devices")) {
        char path[PATH_MAX + 16];
        char value[16];
        snprintf(path, sizeof(path), "%s/removable", dir);
        if (read_file_line(path, value, sizeof(value)) > 0) {
            if (strcmp(value, "fixed") == 0) return CONNECTION_INTERNAL;
            if (strcmp(value, "removable") == 0) return CONNECTION_EXTERNAL;
        }
        if (has_subsystem(dir, "usb") || has_subsystem(dir, "firewire")) on_hotplug_bus = 1;
        char *slash = strrchr(dir, '/');
        if (!slash) break;
        *slash = '\0';
    }
    return on_hotplug_bus ? CONNECTION_EXTERNAL : CONNECTION_INTERNAL;
}

typedef struct {
    int removable_media; // a leaf reports removable media (card reader, optical drive)
    int rotational;      // -1 unknown, 0 SSD, 1 HDD
} block_traits_t;

static int classify_leaf(const char *name, const char *device_path, block_traits_t *traits) {
    char path[PATH_MAX + 32];
    char value[16];

    snprintf(path, sizeof(path), "%s/removable", device_path);
    if (read_file_line(path, value, sizeof(value)) > 0 && value[0] == '1') traits->removable_media = 1;
    snprintf(path, sizeof(path), "%s/queue/rotational", device_path);
    if (read_file_line(path, value, sizeof(value)) > 0) {
        if (value[0] == '1') traits->rotational = 1;
        else if (traits->rotational < 0) traits->rotational = 0;
    }

    if (starts_with(name, "nbd")) return CONNECTION_NETWORK;
    if (starts_with(name, "loop") || starts_with(name, "zram") || starts_with(name, "ram") ||
        strstr(device_path, "/devices/virtual/")) {
        return CONNECTION_VIRTUAL;
    }
    if (starts_with(name, "mmcblk")) {
        // The kernel does not mark SD cards removable; the card type tells eMMC from SD.
        snprintf(path, sizeof(path), "%s/device/type", device_path);
        if (read_file_line(path, value, sizeof(value)) > 0) {
            return strcmp(value, "MMC") == 0 ? CONNECTION_INTERNAL : CONNECTION_EXTERNAL;
        }
    }
    return chain_connection(device_path);
}

// Classifies a block device by its whole-disk leaves: a partition by its disk, a
// dm/md/bcache device by every device it is stacked on. Leaves that disagree
// (an LVM volume spanning an internal and a USB disk) count as internal.
static int classify_block(const char *name, int depth, block_traits_t *traits) {
    char path[PATH_MAX];
    char device_path[PATH_MAX];
    snprintf(path, sizeof(path), "/sys/class/block/%s", name);
    if (!realpath(path, device_path)) return CONNECTION_UNKNOWN;

    char partition[PATH_MAX + 16];
    snprintf(partition, sizeof(partition), "%s/partition", device_path);
    if (access(partition, F_OK) == 0) {
        char *slash = strrchr(device_path, '/');
        if (slash) *slash = '\0';
    }
    const char *disk_name = strrchr(device_path, '/');
    disk_name = disk_name ? disk_name + 1 : device_path;

    if (depth < MAX_STACK_DEPTH) {
        char slaves[PATH_MAX + 16];
        snprintf(slaves, sizeof(slaves), "%s/slaves", device_path);
        DIR *dir = opendir(slaves);
        if (dir) {
            int result = -1;
            struct dirent *entry;
            while ((entry = readdir(dir))) {
                if (entry->d_name[0] == '.') continue;
                int connection = classify_block(entry->d_name, depth + 1, traits);
                result = result < 0 || result == connection ? connection : CONNECTION_INTERNAL;
            }
            closedir(dir);
            if (result >= 0) return result;
        }
    }
    return classify_leaf(disk_name, device_path, traits);
}

// The file system UUID, else the partition UUID, of a block device.
static int volume_id_of(const char *block_name, char *out, size_t out_size) {
    static const char *dirs[] = { "/dev/disk/by-uuid", "/dev/disk/by-partuuid", NULL };
    for (int i = 0; dirs[i]; i++) {
        DIR *dir = opendir(dirs[i]);
        if (!dir) continue;
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            if (entry->d_name[0] == '.') continue;
            char link[PATH_MAX];
            char target[PATH_MAX];
            snprintf(link, sizeof(link), "%s/%s", dirs[i], entry->d_name);
            ssize_t len = readlink(link, target, sizeof(target) - 1);
            if (len <= 0) continue;
            target[len] = '\0';
            const char *base = strrchr(target, '/');
            if (strcmp(base ? base + 1 : target, block_name) == 0) {
                snprintf(out, out_size, "%s", entry->d_name);
                closedir(dir);
                return 1;
            }
        }
        closedir(dir);
    }
    return 0;
}

// ---------------------------------------------------------------------------
// statvfs with a timeout
// ---------------------------------------------------------------------------

typedef struct probe {
    char path[512];
    struct statvfs st;
    int ok;
    int done;
    int abandoned;
    struct probe *next;
} probe_t;

static pthread_mutex_t probe_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t probe_done = PTHREAD_COND_INITIALIZER;
// Probes that timed out and are still blocked in the kernel.
static probe_t *stuck_probes = NULL;

static void *probe_thread(void *arg) {
    probe_t *p = (probe_t *)arg;
    struct statvfs st;
    int ok = statvfs(p->path, &st) == 0;
    pthread_mutex_lock(&probe_lock);
    p->st = st;
    p->ok = ok;
    p->done = 1;
    if (p->abandoned) {
        for (probe_t **link = &stuck_probes; *link; link = &(*link)->next) {
            if (*link == p) {
                *link = p->next;
                break;
            }
        }
        free(p);
    } else {
        pthread_cond_broadcast(&probe_done);
    }
    pthread_mutex_unlock(&probe_lock);
    return NULL;
}

// A mount whose previous probe is still stuck is not probed again until that probe
// returns: a dead server must not cost one blocked thread per scan.
static int timed_statvfs(const char *path, struct statvfs *out) {
    pthread_mutex_lock(&probe_lock);
    for (probe_t *s = stuck_probes; s; s = s->next) {
        if (strcmp(s->path, path) == 0) {
            pthread_mutex_unlock(&probe_lock);
            return 0;
        }
    }
    pthread_mutex_unlock(&probe_lock);

    probe_t *p = calloc(1, sizeof(probe_t));
    if (!p) return 0;
    snprintf(p->path, sizeof(p->path), "%s", path);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, 64 * 1024);
    pthread_t thread;
    int created = pthread_create(&thread, &attr, probe_thread, p) == 0;
    pthread_attr_destroy(&attr);
    if (!created) {
        free(p);
        return 0;
    }

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += STATVFS_TIMEOUT_MS / 1000;
    deadline.tv_nsec += (long)(STATVFS_TIMEOUT_MS % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }

    int ok = 0;
    pthread_mutex_lock(&probe_lock);
    while (!p->done) {
        if (pthread_cond_timedwait(&probe_done, &probe_lock, &deadline) == ETIMEDOUT) break;
    }
    if (p->done) {
        *out = p->st;
        ok = p->ok;
        free(p);
    } else {
        p->abandoned = 1;
        p->next = stuck_probes;
        stuck_probes = p;
    }
    pthread_mutex_unlock(&probe_lock);
    return ok;
}

// ---------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------

static void fill_entry(disk_entry_t *d, const mount_line_t *m) {
    memset(d, 0, sizeof(*d));
    snprintf(d->name, sizeof(d->name), "%s", m->source);
    snprintf(d->fs_type, sizeof(d->fs_type), "%s", m->fs_type);
    snprintf(d->mount_point, sizeof(d->mount_point), "%s", m->mount_point);
    snprintf(d->kind, sizeof(d->kind), "Unknown");
    d->read_only = has_option(m->options, "ro") || has_option(m->super_options, "ro");

    char block[256];
    if (is_network_fs(m->fs_type, m->source)) {
        d->connection = CONNECTION_NETWORK;
        snprintf(d->volume_id, sizeof(d->volume_id), "%s", m->source);
    } else if (block_name_of(m, block, sizeof(block))) {
        block_traits_t traits = { 0, -1 };
        d->connection = classify_block(block, 0, &traits);
        d->removable = traits.removable_media || d->connection == CONNECTION_EXTERNAL;
        // A loop device reports whatever its queue says, not the medium holding the image.
        if (traits.rotational >= 0 && d->connection != CONNECTION_VIRTUAL) {
            snprintf(d->kind, sizeof(d->kind), "%s", traits.rotational ? "HDD" : "SSD");
        }
        volume_id_of(block, d->volume_id, sizeof(d->volume_id));
    } else {
        d->connection = CONNECTION_UNKNOWN;
    }

    struct statvfs st;
    if (timed_statvfs(m->mount_point, &st)) {
        d->total_space = (unsigned long long)st.f_blocks * st.f_frsize;
        d->available_space = (unsigned long long)st.f_bavail * st.f_frsize;
    }
}

static void refresh_disks(void) {
    g_disk_count = 0;
    FILE *f = fopen("/proc/self/mountinfo", "re");
    if (!f) return;
    char *line = NULL;
    size_t capacity = 0;
    while (getline(&line, &capacity, f) > 0) {
        mount_line_t m;
        if (!parse_mount_line(line, &m)) continue;
        if (!is_real_fs(m.fs_type)) continue;
        if (strncmp(m.source, "/dev/", 5) != 0 && !is_network_fs(m.fs_type, m.source)) continue;

        // A mount point mounted over again: the later mount is the visible one.
        int slot = g_disk_count;
        for (int i = 0; i < g_disk_count; i++) {
            if (strcmp(g_disks[i].mount_point, m.mount_point) == 0) {
                slot = i;
                break;
            }
        }
        if (slot == MAX_DISKS) break;
        fill_entry(&g_disks[slot], &m);
        if (slot == g_disk_count) g_disk_count++;
    }
    free(line);
    fclose(f);
}

// ---------------------------------------------------------------------------
// JNI: snapshot
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return (jint)g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].name;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].fs_type;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].mount_point;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskTotalSpaces(
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
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskAvailableSpaces(
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
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].kind;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskRemovable(
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
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskReadOnly(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jboolean *vals = (jboolean *)malloc(g_disk_count * sizeof(jboolean));
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].read_only ? JNI_TRUE : JNI_FALSE;
    jbooleanArray result = (*env)->NewBooleanArray(env, g_disk_count);
    (*env)->SetBooleanArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

// Empty strings for volumes without an id.
JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskVolumeIds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].volume_id;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDiskConnections(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    jint *vals = (jint *)malloc(g_disk_count * sizeof(jint));
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jint)g_disks[i].connection;
    jintArray result = (*env)->NewIntArray(env, g_disk_count);
    (*env)->SetIntArrayRegion(env, result, 0, g_disk_count, vals);
    free(vals);
    return result;
}

// ---------------------------------------------------------------------------
// Mount table monitor
// ---------------------------------------------------------------------------

typedef struct {
    JavaVM *jvm;
    jclass bridge;      // global ref
    jmethodID callback; // static void onDisksChanged()
    pthread_t thread;
    int mountinfo_fd;
    int stop_fd;        // eventfd written by stop
} DiskMonitor;

static DiskMonitor *disk_monitor; // guarded by the Kotlin side (start/stop are serialized)

static void *diskMonitorThread(void *arg) {
    DiskMonitor *m = (DiskMonitor *)arg;
    JNIEnv *env;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-disk-monitor", NULL };
    if ((*m->jvm)->AttachCurrentThreadAsDaemon(m->jvm, (void **)&env, &args) != JNI_OK) return NULL;

    // mountinfo is always readable: POLLPRI (with POLLERR) alone marks a change, and
    // the kernel re-arms it on every poll.
    struct pollfd fds[2] = {
        { .fd = m->mountinfo_fd, .events = POLLPRI },
        { .fd = m->stop_fd, .events = POLLIN },
    };
    for (;;) {
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[1].revents) break;
        if (fds[0].revents & (POLLPRI | POLLERR)) {
            (*env)->CallStaticVoidMethod(env, m->bridge, m->callback);
            nucleus_jni_clear_exception(env);
        }
    }

    (*m->jvm)->DetachCurrentThread(m->jvm);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeStartDiskMonitor(
    JNIEnv *env, jclass clazz) {
    if (disk_monitor) return JNI_TRUE;

    DiskMonitor *m = calloc(1, sizeof(DiskMonitor));
    if (!m) return JNI_FALSE;
    m->callback = (*env)->GetStaticMethodID(env, clazz, "onDisksChanged", "()V");
    if (!m->callback) {
        nucleus_jni_clear_exception(env);
        free(m);
        return JNI_FALSE;
    }
    // Opened here rather than on the thread: the kernel only reports changes made
    // after the open, and the caller scans right after this returns.
    m->mountinfo_fd = open("/proc/self/mountinfo", O_RDONLY | O_CLOEXEC);
    m->stop_fd = eventfd(0, EFD_CLOEXEC);
    if (m->mountinfo_fd < 0 || m->stop_fd < 0) {
        if (m->mountinfo_fd >= 0) close(m->mountinfo_fd);
        if (m->stop_fd >= 0) close(m->stop_fd);
        free(m);
        return JNI_FALSE;
    }
    (*env)->GetJavaVM(env, &m->jvm);
    m->bridge = (*env)->NewGlobalRef(env, clazz);

    if (pthread_create(&m->thread, NULL, diskMonitorThread, m) != 0) {
        close(m->mountinfo_fd);
        close(m->stop_fd);
        (*env)->DeleteGlobalRef(env, m->bridge);
        free(m);
        return JNI_FALSE;
    }
    disk_monitor = m;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeStopDiskMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    DiskMonitor *m = disk_monitor;
    if (!m) return;
    disk_monitor = NULL;

    uint64_t one = 1;
    if (write(m->stop_fd, &one, sizeof(one)) < 0) { /* the eventfd cannot be full */ }
    pthread_join(m->thread, NULL);
    close(m->mountinfo_fd);
    close(m->stop_fd);
    (*env)->DeleteGlobalRef(env, m->bridge);
    free(m);
}

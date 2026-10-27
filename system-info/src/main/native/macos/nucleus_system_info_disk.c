// Disk information: mounted volumes with space, kind, removable, read-only,
// connection type and volume id, plus a volume monitor and an unmount approval hook.
//
// Volumes come from getfsstat(MNT_NOWAIT), which never waits on a file system.
// Volumes the Finder hides (MNT_DONTBROWSE: VM, Preboot, Update, the Data volume
// behind /, simulator runtimes) are left out.
//
// Connection type and volume id come from DiskArbitration. APFS synthesized disks
// inherit DeviceInternal / DeviceProtocol from their physical store, so no walk is
// needed there. Classification, in order:
//   - VolumeNetwork, a non-local mount or a network file system -> NETWORK
//   - DeviceProtocol "Virtual Interface", or no DeviceInternal key -> VIRTUAL
//     (disk images, Time Machine images)
//   - DeviceInternal false -> EXTERNAL (USB, Thunderbolt, FireWire)
//   - DeviceProtocol "Secure Digital" -> EXTERNAL even on a built-in SD slot
//   - otherwise INTERNAL
// MediaRemovable alone is not trusted: disk images report it too.
//
// The disk kind (SSD / HDD) still comes from IOKit. APFS volumes (disk7s2) are
// synthesized by AppleAPFSContainer and don't appear as IOMedia children of
// IOBlockStorageDriver. To find the physical device we strip the volume suffix to
// get the "whole disk" BSD name (disk7), match that with IOBSDNameMatching, and walk
// up to IOBlockStorageDriver / IOBlockStorageDevice for Device Characteristics.

#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <errno.h>
#include <limits.h>
#include <notify.h>
#include <pthread.h>
#include <sys/mount.h>
#include <time.h>
#include <CoreFoundation/CoreFoundation.h>
#include <DiskArbitration/DiskArbitration.h>
#include <IOKit/IOKitLib.h>
#include <dispatch/dispatch.h>

#define MAX_DISKS 256
#define STATFS_TIMEOUT_MS 1000

// DiskConnection ordinals, mirrored in DiskConnection.kt.
enum {
    CONNECTION_UNKNOWN = 0,
    CONNECTION_INTERNAL,
    CONNECTION_EXTERNAL,
    CONNECTION_NETWORK,
    CONNECTION_VIRTUAL,
};

typedef struct {
    char name[MNAMELEN];
    char fs_type[MFSTYPENAMELEN];
    char mount_point[MNAMELEN];
    unsigned long long total_space;
    unsigned long long available_space;
    char kind[16];
    int removable;
    int read_only;
    char volume_id[MNAMELEN + MFSTYPENAMELEN + 1];
    int connection;
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

// Network file systems normally clear MNT_LOCAL; FUSE ones (sshfs over macFUSE) may not,
// so their source decides: "host:path" or "//host/share".
static int is_network_fs(const struct statfs *sf) {
    static const char *const types[] = { "smbfs", "nfs", "afpfs", "webdav", "cifs", "ftp", NULL };
    if (!(sf->f_flags & MNT_LOCAL)) return 1;
    for (int i = 0; types[i]; i++) {
        if (strcmp(sf->f_fstypename, types[i]) == 0) return 1;
    }
    if (strncmp(sf->f_mntfromname, "/dev/", 5) == 0) return 0;
    return strncmp(sf->f_mntfromname, "//", 2) == 0 || strchr(sf->f_mntfromname, ':') != NULL;
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

// Walk up the IORegistry from a service to find a parent with "Device Characteristics".
// Returns a retained CFDictionaryRef or NULL.
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

// Physical disk kind ("SSD", "HDD", or "Unknown") via IOKit for a BSD name.
static void lookup_disk_kind(const char *bsd_name, char *kind, size_t kind_size) {
    snprintf(kind, kind_size, "Unknown");

    char whole[64];
    get_whole_disk_name(bsd_name, whole, sizeof(whole));

    CFMutableDictionaryRef matching = IOBSDNameMatching(kIOMainPortDefault, 0, whole);
    if (!matching) return;

    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault, matching);
    if (!service) return;

    CFDictionaryRef dev_chars = find_ancestor_property(service, CFSTR("Device Characteristics"));
    if (dev_chars) {
        CFStringRef medium = CFDictionaryGetValue(dev_chars, CFSTR("Medium Type"));
        if (medium && CFGetTypeID(medium) == CFStringGetTypeID()) {
            if (CFStringCompare(medium, CFSTR("Solid State"), 0) == kCFCompareEqualTo) {
                snprintf(kind, kind_size, "SSD");
            } else if (CFStringCompare(medium, CFSTR("Rotational"), 0) == kCFCompareEqualTo) {
                snprintf(kind, kind_size, "HDD");
            }
        }
        CFRelease(dev_chars);
    }

    IOObjectRelease(service);
}

// ---------------------------------------------------------------------------
// DiskArbitration descriptions
// ---------------------------------------------------------------------------

// Only used by scans, which DiskMonitor.kt serializes under diskScanLock. Never scheduled:
// DADiskCreateFromBSDName / DADiskCopyDescription do not need a run loop or a queue.
static DASessionRef g_scan_session;

// -1 when the key is absent or not a boolean.
static int description_bool(CFDictionaryRef description, CFStringRef key) {
    CFTypeRef value = CFDictionaryGetValue(description, key);
    if (!value || CFGetTypeID(value) != CFBooleanGetTypeID()) return -1;
    return CFBooleanGetValue((CFBooleanRef)value) ? 1 : 0;
}

static int description_string_is(CFDictionaryRef description, CFStringRef key, CFStringRef expected) {
    CFTypeRef value = CFDictionaryGetValue(description, key);
    return value && CFGetTypeID(value) == CFStringGetTypeID() &&
           CFStringCompare((CFStringRef)value, expected, 0) == kCFCompareEqualTo;
}

static int classify(CFDictionaryRef description) {
    if (description_bool(description, kDADiskDescriptionVolumeNetworkKey) == 1) return CONNECTION_NETWORK;
    int internal = description_bool(description, kDADiskDescriptionDeviceInternalKey);
    if (internal < 0 ||
        description_string_is(description, kDADiskDescriptionDeviceProtocolKey, CFSTR("Virtual Interface"))) {
        return CONNECTION_VIRTUAL;
    }
    if (internal == 0) return CONNECTION_EXTERNAL;
    // Built-in SD slots sit on an internal bus but take removable cards.
    if (description_string_is(description, kDADiskDescriptionDeviceProtocolKey, CFSTR("Secure Digital"))) {
        return CONNECTION_EXTERNAL;
    }
    return CONNECTION_INTERNAL;
}

static void uuid_string(CFDictionaryRef description, char *out, size_t out_size) {
    out[0] = '\0';
    CFTypeRef uuid = CFDictionaryGetValue(description, kDADiskDescriptionVolumeUUIDKey);
    if (!uuid || CFGetTypeID(uuid) != CFUUIDGetTypeID()) return;
    CFStringRef text = CFUUIDCreateString(kCFAllocatorDefault, (CFUUIDRef)uuid);
    if (!text) return;
    if (!CFStringGetCString(text, out, (CFIndex)out_size, kCFStringEncodingUTF8)) out[0] = '\0';
    CFRelease(text);
}

// Fills connection, removable and volume_id of a local /dev volume.
static void describe_local(disk_entry_t *d) {
    if (!g_scan_session) g_scan_session = DASessionCreate(kCFAllocatorDefault);
    if (!g_scan_session) return;
    DADiskRef disk = DADiskCreateFromBSDName(kCFAllocatorDefault, g_scan_session, d->name);
    if (!disk) return;
    // NULL while the disk is being torn down (surprise removal).
    CFDictionaryRef description = DADiskCopyDescription(disk);
    CFRelease(disk);
    if (!description) return;

    d->connection = classify(description);
    // A disk image is ejectable "removable media", but nothing physical can be pulled out.
    int removable_media = description_bool(description, kDADiskDescriptionMediaRemovableKey) == 1;
    d->removable = d->connection == CONNECTION_EXTERNAL ||
                   (removable_media && d->connection != CONNECTION_VIRTUAL);
    uuid_string(description, d->volume_id, sizeof(d->volume_id));
    CFRelease(description);
}

// ---------------------------------------------------------------------------
// statfs with a timeout
// ---------------------------------------------------------------------------

typedef struct probe {
    char path[MNAMELEN];
    struct statfs st;
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
    struct statfs st;
    int ok = statfs(p->path, &st) == 0;
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
static int timed_statfs(const char *path, struct statfs *out) {
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
    deadline.tv_sec += STATFS_TIMEOUT_MS / 1000;
    deadline.tv_nsec += (long)(STATFS_TIMEOUT_MS % 1000) * 1000000L;
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

static void fill_entry(disk_entry_t *d, const struct statfs *sf) {
    memset(d, 0, sizeof(*d));
    snprintf(d->name, sizeof(d->name), "%s", sf->f_mntfromname);
    snprintf(d->fs_type, sizeof(d->fs_type), "%s", sf->f_fstypename);
    snprintf(d->mount_point, sizeof(d->mount_point), "%s", sf->f_mntonname);
    snprintf(d->kind, sizeof(d->kind), "Unknown");
    d->read_only = (sf->f_flags & MNT_RDONLY) ? 1 : 0;

    struct statfs st;
    int has_space;
    if (is_network_fs(sf)) {
        d->connection = CONNECTION_NETWORK;
        snprintf(d->volume_id, sizeof(d->volume_id), "%s:%s", sf->f_fstypename, sf->f_mntfromname);
        // Never wait on a server that is gone.
        has_space = timed_statfs(sf->f_mntonname, &st);
    } else {
        if (strncmp(sf->f_mntfromname, "/dev/", 5) == 0) {
            describe_local(d);
            if (d->connection != CONNECTION_VIRTUAL) {
                lookup_disk_kind(sf->f_mntfromname, d->kind, sizeof(d->kind));
                // Fallback: APFS is almost always on SSD
                if (strcmp(d->kind, "Unknown") == 0 && strcmp(sf->f_fstypename, "apfs") == 0) {
                    snprintf(d->kind, sizeof(d->kind), "SSD");
                }
            }
        }
        // MNT_NOWAIT figures may be stale; a local statfs is cheap.
        has_space = statfs(sf->f_mntonname, &st) == 0;
    }
    if (has_space) {
        d->total_space = (unsigned long long)st.f_blocks * st.f_bsize;
        d->available_space = (unsigned long long)st.f_bavail * st.f_bsize;
    }
}

static void refresh_disks(void) {
    g_disk_count = 0;
    // getmntinfo() returns a buffer shared by the whole process: use our own.
    int count = getfsstat(NULL, 0, MNT_NOWAIT);
    if (count <= 0) return;
    // Room for mounts that appear between the two calls.
    count += 8;
    struct statfs *mounts = calloc((size_t)count, sizeof(struct statfs));
    if (!mounts) return;
    count = getfsstat(mounts, (int)(count * sizeof(struct statfs)), MNT_NOWAIT);

    for (int i = 0; i < count && g_disk_count < MAX_DISKS; i++) {
        struct statfs *sf = &mounts[i];
        if (!is_real_fs(sf->f_fstypename, sf->f_mntfromname, sf->f_mntonname)) continue;
        // System volumes the Finder hides. "/" itself is browsable.
        if (sf->f_flags & MNT_DONTBROWSE) continue;
        fill_entry(&g_disks[g_disk_count], sf);
        g_disk_count++;
    }
    free(mounts);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return (jint)g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].name;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].fs_type;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].mount_point;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskTotalSpaces(
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskAvailableSpaces(
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].kind;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskRemovable(
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskReadOnly(
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskVolumeIds(
    JNIEnv *env, jclass clazz) {
    if (g_disk_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_disk_count * sizeof(char *));
    for (int i = 0; i < g_disk_count; i++) arr[i] = g_disks[i].volume_id;
    jobjectArray result = to_string_array(env, arr, g_disk_count);
    free(arr);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDiskConnections(
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
// Callbacks into Java from GCD worker threads
// ---------------------------------------------------------------------------

typedef struct {
    JavaVM *jvm;
    jclass bridge;      // global ref
    jmethodID callback;
} JavaTarget;

static int java_target_init(JNIEnv *env, jclass clazz, JavaTarget *t, const char *name, const char *sig) {
    t->callback = (*env)->GetStaticMethodID(env, clazz, name, sig);
    if (!t->callback) {
        nucleus_jni_clear_exception(env);
        return 0;
    }
    (*env)->GetJavaVM(env, &t->jvm);
    t->bridge = (*env)->NewGlobalRef(env, clazz);
    return 1;
}

// GCD recycles and retires its worker threads: a thread attached here is detached by
// java_release() before the block returns.
static JNIEnv *java_acquire(const JavaTarget *t, const char *thread_name, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    jint got = (*t->jvm)->GetEnv(t->jvm, (void **)&env, JNI_VERSION_1_6);
    if (got == JNI_OK) return env;
    if (got != JNI_EDETACHED) return NULL;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, (char *)thread_name, NULL };
    if ((*t->jvm)->AttachCurrentThreadAsDaemon(t->jvm, (void **)&env, &args) != JNI_OK) return NULL;
    *attached = 1;
    return env;
}

static void java_release(const JavaTarget *t, int attached) {
    if (attached) (*t->jvm)->DetachCurrentThread(t->jvm);
}

// The file system path of a disk's mounted volume, without a trailing slash except for "/".
static int volume_path_of(DADiskRef disk, char *out, size_t out_size) {
    CFDictionaryRef description = DADiskCopyDescription(disk);
    if (!description) return 0;
    int ok = 0;
    CFTypeRef url = CFDictionaryGetValue(description, kDADiskDescriptionVolumePathKey);
    if (url && CFGetTypeID(url) == CFURLGetTypeID()) {
        ok = CFURLGetFileSystemRepresentation((CFURLRef)url, true, (UInt8 *)out, (CFIndex)out_size) ? 1 : 0;
    }
    CFRelease(description);
    if (ok) {
        size_t len = strlen(out);
        while (len > 1 && out[len - 1] == '/') out[--len] = '\0';
    }
    return ok;
}

// ---------------------------------------------------------------------------
// Volume monitor
//
// One DASession on a private serial queue: no AppKit and no CFRunLoop, so it works
// with any toolkit. match = NULL so network and FUSE volumes come through too. A disk
// appears before it is mounted, so Appeared only counts once VolumePath is set, and the
// mount itself arrives as a VolumePath change. notify(3) mount / unmount covers the
// mounts DiskArbitration never sees (nullfs, some FUSE file systems).
// ---------------------------------------------------------------------------

typedef struct {
    JavaTarget java;
    dispatch_queue_t queue;
    DASessionRef session;
    int mount_token;
    int unmount_token;
} DiskMonitor;

static DiskMonitor *disk_monitor; // guarded by the Kotlin side (start/stop are serialized)

static void signal_disks_changed(DiskMonitor *m) {
    int attached;
    JNIEnv *env = java_acquire(&m->java, "nucleus-disk-monitor", &attached);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, m->java.bridge, m->java.callback);
    nucleus_jni_clear_exception(env);
    java_release(&m->java, attached);
}

static void on_disk_appeared(DADiskRef disk, void *context) {
    CFDictionaryRef description = DADiskCopyDescription(disk);
    if (!description) return;
    int mounted = CFDictionaryGetValue(description, kDADiskDescriptionVolumePathKey) != NULL;
    CFRelease(description);
    if (mounted) signal_disks_changed((DiskMonitor *)context);
}

static void on_disk_disappeared(DADiskRef disk, void *context) {
    (void)disk;
    signal_disks_changed((DiskMonitor *)context);
}

static void on_disk_description_changed(DADiskRef disk, CFArrayRef keys, void *context) {
    (void)disk;
    (void)keys;
    signal_disks_changed((DiskMonitor *)context);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStartDiskMonitor(
    JNIEnv *env, jclass clazz) {
    if (disk_monitor) return JNI_TRUE;

    DiskMonitor *m = calloc(1, sizeof(DiskMonitor));
    if (!m) return JNI_FALSE;
    m->mount_token = NOTIFY_TOKEN_INVALID;
    m->unmount_token = NOTIFY_TOKEN_INVALID;
    m->session = DASessionCreate(kCFAllocatorDefault);
    if (!m->session || !java_target_init(env, clazz, &m->java, "onDisksChanged", "()V")) {
        if (m->session) CFRelease(m->session);
        free(m);
        return JNI_FALSE;
    }
    m->queue = dispatch_queue_create("dev.nucleusframework.systeminfo.disks", DISPATCH_QUEUE_SERIAL);

    CFMutableArrayRef watched = CFArrayCreateMutable(kCFAllocatorDefault, 1, &kCFTypeArrayCallBacks);
    CFArrayAppendValue(watched, kDADiskDescriptionVolumePathKey);
    DARegisterDiskAppearedCallback(m->session, NULL, on_disk_appeared, m);
    DARegisterDiskDisappearedCallback(m->session, NULL, on_disk_disappeared, m);
    DARegisterDiskDescriptionChangedCallback(m->session, NULL, watched, on_disk_description_changed, m);
    CFRelease(watched);
    // Delivers Appeared for every existing disk first: one burst, one rescan, no emission.
    DASessionSetDispatchQueue(m->session, m->queue);

    notify_handler_t handler = ^(int token) {
        (void)token;
        signal_disks_changed(m);
    };
    notify_register_dispatch("com.apple.system.kernel.mount", &m->mount_token, m->queue, handler);
    notify_register_dispatch("com.apple.system.kernel.unmount", &m->unmount_token, m->queue, handler);

    disk_monitor = m;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStopDiskMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    DiskMonitor *m = disk_monitor;
    if (!m) return;
    disk_monitor = NULL;

    if (m->mount_token != NOTIFY_TOKEN_INVALID) notify_cancel(m->mount_token);
    if (m->unmount_token != NOTIFY_TOKEN_INVALID) notify_cancel(m->unmount_token);
    DAUnregisterCallback(m->session, (void *)on_disk_appeared, m);
    DAUnregisterCallback(m->session, (void *)on_disk_disappeared, m);
    DAUnregisterCallback(m->session, (void *)on_disk_description_changed, m);
    DASessionSetDispatchQueue(m->session, NULL);
    // Drain: once this returns, no callback can touch the monitor any more. The callbacks
    // only post to a channel on the Kotlin side, so waiting here cannot deadlock.
    dispatch_sync(m->queue, ^{});

    CFRelease(m->session);
    dispatch_release(m->queue);
    (*env)->DeleteGlobalRef(env, m->java.bridge);
    free(m);
}

// ---------------------------------------------------------------------------
// Unmount approval
//
// DiskArbitration asks every registered session before it unmounts a volume (Finder
// eject, diskutil unmount / eject, DADiskUnmount). Returning NULL approves; a dissenter
// refuses and the requester reports the volume as busy. diskarbitrationd gives up on a
// session after 10 s and then stops sending it approval requests until its queue drains;
// the Kotlin side answers within 3 s. A forced unmount, a surprise removal and a direct
// umount(2) never ask.
// ---------------------------------------------------------------------------

typedef struct {
    JavaTarget java;
    dispatch_queue_t queue;
    DASessionRef session;
} EjectApproval;

static EjectApproval *eject_approval; // guarded by the Kotlin side (start/stop are serialized)

static DADissenterRef on_unmount_approval(DADiskRef disk, void *context) {
    EjectApproval *a = (EjectApproval *)context;
    char path[PATH_MAX];
    if (!volume_path_of(disk, path, sizeof(path))) return NULL;

    int attached;
    JNIEnv *env = java_acquire(&a->java, "nucleus-eject-approval", &attached);
    if (!env) return NULL;
    jboolean allowed = JNI_TRUE;
    jstring jpath = (*env)->NewStringUTF(env, path);
    if (jpath) {
        allowed = (*env)->CallStaticBooleanMethod(env, a->java.bridge, a->java.callback, jpath);
        if ((*env)->ExceptionCheck(env)) {
            nucleus_jni_clear_exception(env);
            allowed = JNI_TRUE;
        }
        (*env)->DeleteLocalRef(env, jpath);
    } else {
        nucleus_jni_clear_exception(env);
    }
    java_release(&a->java, attached);

    // The caller releases the dissenter.
    return allowed ? NULL : DADissenterCreate(kCFAllocatorDefault, kDAReturnBusy, NULL);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStartEjectApproval(
    JNIEnv *env, jclass clazz) {
    if (eject_approval) return JNI_TRUE;

    EjectApproval *a = calloc(1, sizeof(EjectApproval));
    if (!a) return JNI_FALSE;
    a->session = DASessionCreate(kCFAllocatorDefault);
    if (!a->session ||
        !java_target_init(env, clazz, &a->java, "onEjectRequest", "(Ljava/lang/String;)Z")) {
        if (a->session) CFRelease(a->session);
        free(a);
        return JNI_FALSE;
    }
    a->queue = dispatch_queue_create("dev.nucleusframework.systeminfo.eject", DISPATCH_QUEUE_SERIAL);
    DARegisterDiskUnmountApprovalCallback(a->session, NULL, on_unmount_approval, a);
    DASessionSetDispatchQueue(a->session, a->queue);

    eject_approval = a;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStopEjectApproval(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    EjectApproval *a = eject_approval;
    if (!a) return;
    eject_approval = NULL;

    // Unregister, detach from the queue, drain it, then release. A request in flight
    // finishes first, within the Kotlin budget.
    DAUnregisterApprovalCallback(a->session, (void *)on_unmount_approval, a);
    DASessionSetDispatchQueue(a->session, NULL);
    dispatch_sync(a->queue, ^{});

    CFRelease(a->session);
    dispatch_release(a->queue);
    (*env)->DeleteGlobalRef(env, a->java.bridge);
    free(a);
}

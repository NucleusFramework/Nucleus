// Network connectivity and metered status detection on macOS.
// Uses NWPathMonitor (Network framework, macOS 10.14+) for both reachability
// and expensive-path detection (replaces deprecated SCNetworkReachability).

#include "nucleus_system_info_common.h"
#include <Network/Network.h>
#include <dispatch/dispatch.h>

// Synchronous NWPathMonitor query: creates a monitor, waits for the first
// path update via a dispatch semaphore, then captures the result and tears down.

typedef struct {
    nw_path_status_t status;
    bool expensive;
} path_snapshot_t;

static bool query_network_path(path_snapshot_t *out) {
    __block path_snapshot_t snapshot = { nw_path_status_invalid, false };
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);

    nw_path_monitor_t monitor = nw_path_monitor_create();
    nw_path_monitor_set_update_handler(monitor, ^(nw_path_t path) {
        snapshot.status = nw_path_get_status(path);
        snapshot.expensive = nw_path_is_expensive(path);
        dispatch_semaphore_signal(sem);
    });

    dispatch_queue_t queue = dispatch_queue_create("nucleus.connectivity", DISPATCH_QUEUE_SERIAL);
    nw_path_monitor_set_queue(monitor, queue);
    nw_path_monitor_start(monitor);

    long ok = dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 2LL * NSEC_PER_SEC));
    nw_path_monitor_cancel(monitor);
    nw_release(monitor);
    dispatch_release(queue);
    dispatch_release(sem);

    if (ok != 0) return false; // timeout
    *out = snapshot;
    return true;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeIsNetworkConnected(
    JNIEnv *env, jclass clazz) {

    path_snapshot_t snap;
    if (!query_network_path(&snap)) return JNI_FALSE;
    return (snap.status == nw_path_status_satisfied) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGetMeteredStatus(
    JNIEnv *env, jclass clazz) {

    path_snapshot_t snap;
    if (!query_network_path(&snap)) return 0; // UNKNOWN

    if (snap.status != nw_path_status_satisfied) return 0; // not connected → UNKNOWN

    return snap.expensive ? 2 : 1; // METERED or UNMETERED
}

// Network connectivity, metered status and change monitoring on macOS.
//
// Uses NWPathMonitor (Network framework, macOS 10.14+), the API behind
// Foundation's and Qt's reachability on Apple platforms:
//   - nw_path_get_status: satisfied / unsatisfied / requires-connection
//   - nw_path_is_expensive: cellular or personal hotspot
//   - nw_path_is_constrained: Low Data Mode (macOS 10.15+)
//
// NWPathMonitor does not probe the internet, so a satisfied path reports
// FULL and captive portals or limited connectivity are never detected.
//
// The change monitor keeps one NWPathMonitor on a private serial queue and
// only calls back into Java when the state actually changed: the monitor
// re-delivers identical paths whenever an unrelated interface flaps.

#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <Network/Network.h>
#include <dispatch/dispatch.h>

// Codes mirrored in ConnectivityMonitor.kt (connectivityInfoOf).
#define METERED_UNKNOWN 0
#define METERED_NO 1
#define METERED_YES 2

#define LEVEL_UNKNOWN 0
#define LEVEL_NONE 1
#define LEVEL_FULL 5

typedef struct {
    int available;
    int metered;
    int level;
} NetState;

static NetState state_of(nw_path_t path) {
    NetState state = { 0, METERED_UNKNOWN, LEVEL_UNKNOWN };
    switch (nw_path_get_status(path)) {
    case nw_path_status_satisfied:
        state.available = 1;
        state.level = LEVEL_FULL;
        break;
    case nw_path_status_unsatisfied:
        state.level = LEVEL_NONE;
        break;
    default:
        // invalid, or satisfiable: a connection (VPN on demand, cellular) would
        // have to be brought up first, so nothing can be said yet.
        return state;
    }
    if (state.available) {
        bool constrained = false;
        if (__builtin_available(macOS 10.15, *)) constrained = nw_path_is_constrained(path);
        state.metered = (nw_path_is_expensive(path) || constrained) ? METERED_YES : METERED_NO;
    }
    return state;
}

static jintArray to_java(JNIEnv *env, const NetState *state) {
    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) return NULL;
    jint values[3] = { state->available, state->metered, state->level };
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

// ---------------------------------------------------------------------------
// One-shot query
// ---------------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeQueryConnectivity(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    __block NetState snapshot = { 0, METERED_UNKNOWN, LEVEL_UNKNOWN };
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    dispatch_queue_t queue = dispatch_queue_create("dev.nucleusframework.connectivity.query", DISPATCH_QUEUE_SERIAL);

    nw_path_monitor_t monitor = nw_path_monitor_create();
    nw_path_monitor_set_queue(monitor, queue);
    nw_path_monitor_set_update_handler(monitor, ^(nw_path_t path) {
        snapshot = state_of(path);
        dispatch_semaphore_signal(sem);
    });
    nw_path_monitor_start(monitor);

    // A timeout leaves the snapshot "unknown", which is what it is.
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 2LL * NSEC_PER_SEC));
    nw_path_monitor_cancel(monitor);
    // Drain the queue so the handler never writes the snapshot after this frame is gone.
    dispatch_sync(queue, ^{});

    nw_release(monitor);
    dispatch_release(queue);
    dispatch_release(sem);
    return to_java(env, &snapshot);
}

// ---------------------------------------------------------------------------
// Change monitor
// ---------------------------------------------------------------------------

typedef struct {
    JavaVM *jvm;
    jclass bridge;            // global ref
    jmethodID callback;       // static void onConnectivityChanged(boolean, int, int)
    dispatch_queue_t queue;
    nw_path_monitor_t path_monitor;
    dispatch_semaphore_t cancelled;
    NetState last;            // only touched on queue
    int has_last;
} Monitor;

static Monitor *monitor;      // guarded by the Kotlin side (start/stop are serialized)

// Runs on the monitor's serial queue, whose GCD worker thread varies between calls.
static void publish(Monitor *m, const NetState *state) {
    if (m->has_last && memcmp(&m->last, state, sizeof(NetState)) == 0) return;
    m->last = *state;
    m->has_last = 1;

    JNIEnv *env = NULL;
    jint got = (*m->jvm)->GetEnv(m->jvm, (void **)&env, JNI_VERSION_1_6);
    int attached = 0;
    if (got == JNI_EDETACHED) {
        JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-connectivity-monitor", NULL };
        if ((*m->jvm)->AttachCurrentThreadAsDaemon(m->jvm, (void **)&env, &args) != JNI_OK) return;
        attached = 1;
    } else if (got != JNI_OK) {
        return;
    }

    (*env)->CallStaticVoidMethod(env, m->bridge, m->callback,
                                 (jboolean)(state->available ? JNI_TRUE : JNI_FALSE),
                                 (jint)state->metered, (jint)state->level);
    nucleus_jni_clear_exception(env);

    // GCD recycles and retires its worker threads: never leave one attached.
    if (attached) (*m->jvm)->DetachCurrentThread(m->jvm);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStartConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    if (monitor) return JNI_TRUE;

    Monitor *m = calloc(1, sizeof(Monitor));
    if (!m) return JNI_FALSE;
    (*env)->GetJavaVM(env, &m->jvm);
    m->callback = (*env)->GetStaticMethodID(env, clazz, "onConnectivityChanged", "(ZII)V");
    if (!m->callback) {
        nucleus_jni_clear_exception(env);
        free(m);
        return JNI_FALSE;
    }
    m->bridge = (*env)->NewGlobalRef(env, clazz);
    m->queue = dispatch_queue_create("dev.nucleusframework.connectivity.monitor", DISPATCH_QUEUE_SERIAL);
    m->path_monitor = nw_path_monitor_create();
    nw_path_monitor_set_queue(m->path_monitor, m->queue);
    m->cancelled = dispatch_semaphore_create(0);
    nw_path_monitor_set_update_handler(m->path_monitor, ^(nw_path_t path) {
        NetState state = state_of(path);
        publish(m, &state);
    });
    dispatch_semaphore_t cancelled = m->cancelled;
    nw_path_monitor_set_cancel_handler(m->path_monitor, ^{
        dispatch_semaphore_signal(cancelled);
    });
    // The first update carries the current path, so the collector gets its initial state.
    nw_path_monitor_start(m->path_monitor);

    monitor = m;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeStopConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    Monitor *m = monitor;
    if (!m) return;
    monitor = NULL;

    // The cancel handler runs on the queue after the last update handler, so
    // once it fired no callback can touch the monitor any more.
    nw_path_monitor_cancel(m->path_monitor);
    dispatch_semaphore_wait(m->cancelled, DISPATCH_TIME_FOREVER);

    nw_release(m->path_monitor);
    dispatch_release(m->queue);
    dispatch_release(m->cancelled);
    (*env)->DeleteGlobalRef(env, m->bridge);
    free(m);
}

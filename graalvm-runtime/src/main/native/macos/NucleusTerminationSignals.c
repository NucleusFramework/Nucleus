#include <dispatch/dispatch.h>
#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <unistd.h>

// SIGINT / SIGTERM / SIGHUP for a native image compiled without Substrate's signal handling
// (the sandboxed Mac App Store image: its named POSIX semaphore is denied by the App Sandbox).
// Without this, those signals take their default action and the process dies without running
// the shutdown hooks. libdispatch delivers them through kqueue instead, which needs no
// semaphore; the handler hands the signal to TerminationSignals.onTerminationSignal, which
// exits like HotSpot does (shutdown hooks, then status 128 + signal).

static JavaVM *g_jvm;
static jclass g_class;
static jmethodID g_callback;

static void nucleus_signal_noop(int sig) {
    (void)sig;
}

static void nucleus_on_signal(void *context) {
    int sig = (int)(intptr_t)context;
    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) == JNI_OK) {
        // Does not return: the callback ends in System.exit (or Runtime.halt).
        (*env)->CallStaticVoidMethod(env, g_class, g_callback, (jint)sig);
    }
    _exit(128 + sig);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_graalvm_signal_TerminationSignals_nativeInstall(JNIEnv *env, jclass clazz) {
    if (g_class != NULL) {
        return JNI_TRUE;
    }
    if ((*env)->GetJavaVM(env, &g_jvm) != JNI_OK) {
        return JNI_FALSE;
    }
    // A missing method leaves NoSuchMethodError pending, which the Kotlin caller catches.
    g_callback = (*env)->GetStaticMethodID(env, clazz, "onTerminationSignal", "(I)V");
    if (g_callback == NULL) {
        return JNI_FALSE;
    }
    g_class = (jclass)(*env)->NewGlobalRef(env, clazz);

    // Not SIG_IGN: an ignored disposition survives exec, so every child process (ProcessBuilder,
    // Process.destroy's SIGTERM) would ignore these signals too. A handler is reset to the default
    // on exec, as HotSpot's are. Either way the dispatch source still sees the signal.
    struct sigaction action = {0};
    action.sa_handler = nucleus_signal_noop;
    action.sa_flags = SA_RESTART;
    sigemptyset(&action.sa_mask);

    static const int signals[] = {SIGINT, SIGTERM, SIGHUP};
    dispatch_queue_t queue = dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0);
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) {
        int sig = signals[i];
        // Replaces the default action (terminate), which would kill the process before the
        // dispatch source runs.
        sigaction(sig, &action, NULL);
        dispatch_source_t source =
            dispatch_source_create(DISPATCH_SOURCE_TYPE_SIGNAL, (uintptr_t)sig, 0, queue);
        if (source == NULL) {
            continue;
        }
        dispatch_set_context(source, (void *)(intptr_t)sig);
        dispatch_source_set_event_handler_f(source, nucleus_on_signal);
        // Kept for the life of the process.
        dispatch_resume(source);
    }
    return JNI_TRUE;
}

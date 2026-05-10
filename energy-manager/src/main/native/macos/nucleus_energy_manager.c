/**
 * JNI bridge for macOS energy efficiency mode.
 *
 * Provides native implementations for:
 *   - Checking if macOS energy APIs are available (always true on supported JDK)
 *   - Enabling efficiency mode (PRIO_DARWIN_BG + task_policy_set TIER_5)
 *   - Disabling efficiency mode (restore default priority and QoS tiers)
 *
 * PRIO_DARWIN_BG is the "master switch": a single syscall activates
 * CPU low priority, I/O throttling, network throttling, and E-core
 * confinement on Apple Silicon.
 *
 * task_policy_set with LATENCY_QOS_TIER_5 / THROUGHPUT_QOS_TIER_5
 * reinforces the signal via Mach task-level QoS parameters.
 *
 * No special privileges are required — any process can put itself
 * in background mode.
 *
 * Screen-awake (caffeine) support uses IOPMAssertionCreateWithName from
 * the IOKit framework to prevent display and system sleep.
 *
 * Linked libraries: libSystem (automatic), IOKit, CoreFoundation
 */

#include <jni.h>
#include <sys/resource.h>
#include <mach/mach.h>
#include <mach/task_policy.h>
#include <pthread/qos.h>
#include <errno.h>
#include <IOKit/pwr_mgt/IOPMLib.h>
#include <CoreFoundation/CoreFoundation.h>

/* Fallback definitions if headers do not provide these constants */
#ifndef PRIO_DARWIN_PROCESS
#define PRIO_DARWIN_PROCESS 4
#endif
#ifndef PRIO_DARWIN_BG
#define PRIO_DARWIN_BG 0x1000
#endif

/* ---- nativeIsSupported ------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeIsSupported(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    /*
     * setpriority exists since macOS 10.5, QoS since 10.10.
     * Any macOS version supported by a modern JDK has these APIs.
     */
    return JNI_TRUE;
}

/* ---- nativeEnableEfficiencyMode ---------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeEnableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int result = 0;

    /*
     * 1. Background mode via PRIO_DARWIN_BG
     *    -> CPU MAXPRI_THROTTLE, I/O throttle, net throttle,
     *       E-cores only on Apple Silicon
     */
    errno = 0;
    if (setpriority(PRIO_DARWIN_PROCESS, 0, PRIO_DARWIN_BG) != 0) {
        result = errno;
    }

    /*
     * 2. Latency/throughput tiers at maximum efficiency (optional,
     *    reinforces the signal via the Mach task subsystem)
     */
    struct task_qos_policy qos;
    qos.task_latency_qos_tier    = LATENCY_QOS_TIER_5;
    qos.task_throughput_qos_tier = THROUGHPUT_QOS_TIER_5;
    task_policy_set(mach_task_self(),
                    TASK_BASE_QOS_POLICY,
                    (task_policy_t)&qos,
                    TASK_QOS_POLICY_COUNT);
    /* Non-fatal — PRIO_DARWIN_BG is the primary mechanism */

    return (jint)result;
}

/* ---- nativeEnableLightEfficiencyMode ----------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeEnableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /*
     * Light mode: only task_policy_set with TIER_5 QoS.
     * This deprioritizes CPU scheduling without enabling
     * I/O throttling or network throttling (no PRIO_DARWIN_BG).
     */
    struct task_qos_policy qos;
    qos.task_latency_qos_tier    = LATENCY_QOS_TIER_5;
    qos.task_throughput_qos_tier = THROUGHPUT_QOS_TIER_5;
    kern_return_t kr = task_policy_set(mach_task_self(),
                                       TASK_BASE_QOS_POLICY,
                                       (task_policy_t)&qos,
                                       TASK_QOS_POLICY_COUNT);

    return (kr == KERN_SUCCESS) ? 0 : (jint)kr;
}

/* ---- nativeDisableLightEfficiencyMode --------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeDisableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* Reset tiers to unspecified — let the system decide */
    struct task_qos_policy qos;
    qos.task_latency_qos_tier    = LATENCY_QOS_TIER_UNSPECIFIED;
    qos.task_throughput_qos_tier = THROUGHPUT_QOS_TIER_UNSPECIFIED;
    kern_return_t kr = task_policy_set(mach_task_self(),
                                       TASK_BASE_QOS_POLICY,
                                       (task_policy_t)&qos,
                                       TASK_QOS_POLICY_COUNT);

    return (kr == KERN_SUCCESS) ? 0 : (jint)kr;
}

/* ---- nativeEnableThreadEfficiencyMode ---------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeEnableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /*
     * pthread_set_qos_class_self_np(QOS_CLASS_BACKGROUND, 0)
     * Sets the calling thread to background QoS:
     *   - Confined to E-cores on Apple Silicon
     *   - Lowest CPU priority
     *   - Does NOT affect I/O or network (unlike process-level PRIO_DARWIN_BG)
     *
     * Available since macOS 10.10.
     */
    int rc = pthread_set_qos_class_self_np(QOS_CLASS_BACKGROUND, 0);
    return (jint)rc;
}

/* ---- nativeDisableThreadEfficiencyMode --------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeDisableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* Restore to QOS_CLASS_DEFAULT — normal scheduling */
    int rc = pthread_set_qos_class_self_np(QOS_CLASS_DEFAULT, 0);
    return (jint)rc;
}

/* ---- nativeDisableEfficiencyMode --------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeDisableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int result = 0;

    /* Remove background mode */
    errno = 0;
    if (setpriority(PRIO_DARWIN_PROCESS, 0, 0) != 0) {
        result = errno;
    }

    /* Reset tiers to "unspecified" (let the system decide) */
    struct task_qos_policy qos;
    qos.task_latency_qos_tier    = LATENCY_QOS_TIER_UNSPECIFIED;
    qos.task_throughput_qos_tier = THROUGHPUT_QOS_TIER_UNSPECIFIED;
    task_policy_set(mach_task_self(),
                    TASK_BASE_QOS_POLICY,
                    (task_policy_t)&qos,
                    TASK_QOS_POLICY_COUNT);

    return (jint)result;
}

/* ==================================================================
 * Screen-awake (caffeine) via IOPMAssertion
 * ================================================================== */

/*
 * Global assertion ID. kIOPMNullAssertionID (0) means no active assertion.
 * Access is single-threaded from Kotlin (@Synchronized on the manager).
 */
static IOPMAssertionID g_assertionId = kIOPMNullAssertionID;

/* ---- nativeKeepScreenAwake --------------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeKeepScreenAwake(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* If already holding an assertion, release it first */
    if (g_assertionId != kIOPMNullAssertionID) {
        IOPMAssertionRelease(g_assertionId);
        g_assertionId = kIOPMNullAssertionID;
    }

    /*
     * kIOPMAssertPreventUserIdleDisplaySleep prevents both display and
     * system sleep — equivalent to ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED
     * on Windows.
     */
    CFStringRef reason = CFSTR("Nucleus EnergyManager keepScreenAwake");
    IOReturn ret = IOPMAssertionCreateWithName(
        kIOPMAssertPreventUserIdleDisplaySleep,
        kIOPMAssertionLevelOn,
        reason,
        &g_assertionId);

    if (ret != kIOReturnSuccess) {
        g_assertionId = kIOPMNullAssertionID;
        return (jint)ret;
    }
    return 0;
}

/* ---- nativeReleaseScreenAwake ------------------------------------ */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeReleaseScreenAwake(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    if (g_assertionId == kIOPMNullAssertionID) {
        return 0; /* Nothing to release */
    }

    IOReturn ret = IOPMAssertionRelease(g_assertionId);
    g_assertionId = kIOPMNullAssertionID;

    return (ret == kIOReturnSuccess) ? 0 : (jint)ret;
}

/* ---- nativeIsScreenAwakeActive ----------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_energymanager_macos_NativeMacOsEnergyBridge_nativeIsScreenAwakeActive(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return g_assertionId != kIOPMNullAssertionID ? JNI_TRUE : JNI_FALSE;
}

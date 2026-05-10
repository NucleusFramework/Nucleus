// Idle time detection via GetLastInputInfo + GetTickCount64 on Windows.

#include "nucleus_system_info_common.h"

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeIdleTimeSeconds(
    JNIEnv *env, jclass clazz) {
    LASTINPUTINFO lii;
    lii.cbSize = sizeof(LASTINPUTINFO);

    if (!GetLastInputInfo(&lii))
        return (jlong)-1;

    // GetLastInputInfo returns a 32-bit tick count that wraps every ~49.7 days.
    // Reconstruct a 64-bit timestamp using GetTickCount64 to handle the wraparound.
    ULONGLONG tickCount64 = GetTickCount64();
    DWORD tickCountLow = (DWORD)tickCount64;
    ULONGLONG lastInput64 = (tickCount64 & 0xFFFFFFFF00000000ULL) | (ULONGLONG)lii.dwTime;

    if (lii.dwTime > tickCountLow)
        lastInput64 -= 0x100000000ULL;

    ULONGLONG idleMs = tickCount64 - lastInput64;
    return (jlong)(idleMs / 1000ULL);
}

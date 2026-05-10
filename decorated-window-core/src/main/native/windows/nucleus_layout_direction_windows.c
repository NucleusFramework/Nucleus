/**
 * JNI bridge for Windows layout direction detection.
 *
 * Uses GetLocaleInfoEx with LOCALE_IREADINGLAYOUT to query the OS
 * reading layout for the current user locale.
 *
 * Return values of LOCALE_IREADINGLAYOUT:
 *   0 = left-to-right
 *   1 = right-to-left
 *   2 = top-to-bottom LTR (e.g. Mongolian)
 *   3 = top-to-bottom RTL
 *
 * Linked libraries: kernel32.lib
 */

#include <jni.h>
#include <windows.h>

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_NativeLayoutDirectionBridge_nativeIsRTL(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    WCHAR buf[4] = {0};
    int ret = GetLocaleInfoEx(
        LOCALE_NAME_USER_DEFAULT,
        LOCALE_IREADINGLAYOUT,
        buf, 4);

    if (ret > 0 && buf[0] == L'1') {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

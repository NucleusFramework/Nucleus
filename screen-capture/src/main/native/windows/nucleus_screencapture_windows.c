/*
 * Windows backend of dev.nucleusframework.screencapture.
 *
 * Displays are captured with GDI (BitBlt of the screen DC, CAPTUREBLT so layered windows
 * are included) and windows with PrintWindow(PW_RENDERFULLCONTENT), which asks DWM for the
 * window's redirection surface — occluded parts and DirectX/ANGLE content included.
 *
 * Every entry point runs in a per-monitor-v2 DPI context for the calling thread only: the
 * rectangles, the cursor position and the pixels are then physical, whatever the process
 * awareness is, and the thread's context is restored before returning.
 */

#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <dwmapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <process.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>

#include "../../../../../native-common/nucleus_jni.h"

#define STATUS_OK 0
#define STATUS_UNSUPPORTED 1
#define STATUS_DISPLAY_NOT_FOUND 3
#define STATUS_WINDOW_NOT_FOUND 4
#define STATUS_FAILED 6
#define STATUS_INVALID_REGION 8

#define PERMISSION_NOT_REQUIRED 3
#define BACKEND_GDI 1

#define MAX_MONITORS 64
/* Largest capture: keeps w * h * 4 inside a DWORD and w * h inside a jint. */
#define MAX_PIXELS 0x1FFFFFFFLL

#ifndef PW_RENDERFULLCONTENT
#define PW_RENDERFULLCONTENT 0x00000002
#endif

typedef DPI_AWARENESS_CONTEXT(WINAPI *SetThreadDpiAwarenessContextFn)(DPI_AWARENESS_CONTEXT);
typedef HRESULT(WINAPI *GetDpiForMonitorFn)(HMONITOR, int, UINT *, UINT *);

static INIT_ONCE g_init_once = INIT_ONCE_STATIC_INIT;
static SetThreadDpiAwarenessContextFn g_set_thread_dpi;
static GetDpiForMonitorFn g_get_dpi_for_monitor;

static BOOL CALLBACK resolve_functions(PINIT_ONCE once, PVOID param, PVOID *ctx) {
    (void)once;
    (void)param;
    (void)ctx;
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32 != NULL) {
        g_set_thread_dpi =
            (SetThreadDpiAwarenessContextFn)(void *)GetProcAddress(user32, "SetThreadDpiAwarenessContext");
    }
    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore != NULL) {
        g_get_dpi_for_monitor = (GetDpiForMonitorFn)(void *)GetProcAddress(shcore, "GetDpiForMonitor");
    }
    return TRUE;
}

static void ensure_init(void) {
    InitOnceExecuteOnce(&g_init_once, resolve_functions, NULL, NULL);
}

/* Switches the calling thread to per-monitor-v2 (v1 before Windows 10 1703); returns the old context. */
static DPI_AWARENESS_CONTEXT enter_physical_pixels(void) {
    ensure_init();
    if (g_set_thread_dpi == NULL) return NULL;
    DPI_AWARENESS_CONTEXT old = g_set_thread_dpi(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    if (old == NULL) old = g_set_thread_dpi(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE);
    return old;
}

static void leave_physical_pixels(DPI_AWARENESS_CONTEXT old) {
    if (old != NULL && g_set_thread_dpi != NULL) g_set_thread_dpi(old);
}

/* ---------------------------------------------------------------- result plumbing */

static void set_result(JNIEnv *env, jintArray result, int status, int width, int height) {
    jint values[3] = {status, width, height};
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
}

static void set_message(JNIEnv *env, jobjectArray message, const char *text) {
    if (message == NULL || text == NULL) return;
    jstring s = (*env)->NewStringUTF(env, text);
    if (s == NULL) {
        nucleus_jni_clear_exception(env);
        return;
    }
    (*env)->SetObjectArrayElement(env, message, 0, s);
    (*env)->DeleteLocalRef(env, s);
}

static void fail(JNIEnv *env, jintArray result, jobjectArray message, int status, const char *what) {
    char buffer[256];
    DWORD error = GetLastError();
    if (error != 0) {
        snprintf(buffer, sizeof(buffer), "%s (Win32 error %lu)", what, (unsigned long)error);
    } else {
        snprintf(buffer, sizeof(buffer), "%s", what);
    }
    set_result(env, result, status, 0, 0);
    set_message(env, message, buffer);
}

/* ---------------------------------------------------------------- monitors */

typedef struct {
    RECT rect;
    WCHAR device[CCHDEVICENAME];
    BOOL primary;
    UINT dpi;
} Monitor;

typedef struct {
    Monitor items[MAX_MONITORS];
    int count;
} MonitorList;

static BOOL CALLBACK collect_monitor(HMONITOR handle, HDC dc, LPRECT rect, LPARAM param) {
    (void)dc;
    (void)rect;
    MonitorList *list = (MonitorList *)param;
    if (list->count >= MAX_MONITORS) return FALSE;
    MONITORINFOEXW info;
    ZeroMemory(&info, sizeof(info));
    info.cbSize = sizeof(info);
    if (!GetMonitorInfoW(handle, (MONITORINFO *)&info)) return TRUE;
    Monitor *m = &list->items[list->count++];
    m->rect = info.rcMonitor;
    lstrcpynW(m->device, info.szDevice, CCHDEVICENAME);
    m->primary = (info.dwFlags & MONITORINFOF_PRIMARY) != 0;
    m->dpi = USER_DEFAULT_SCREEN_DPI;
    if (g_get_dpi_for_monitor != NULL) {
        UINT dx = 0, dy = 0;
        if (SUCCEEDED(g_get_dpi_for_monitor(handle, 0 /* MDT_EFFECTIVE_DPI */, &dx, &dy)) && dx > 0) m->dpi = dx;
    }
    return TRUE;
}

/* Must run in the physical-pixel context. */
static void enumerate_monitors(MonitorList *list) {
    list->count = 0;
    EnumDisplayMonitors(NULL, NULL, collect_monitor, (LPARAM)list);
}

/* The monitor's friendly name ("DELL U2720Q") through the display configuration API. */
static BOOL friendly_name(const WCHAR *device, WCHAR *out, int capacity) {
    UINT32 path_count = 0, mode_count = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &path_count, &mode_count) != ERROR_SUCCESS) return FALSE;
    if (path_count == 0) return FALSE;
    DISPLAYCONFIG_PATH_INFO *paths = (DISPLAYCONFIG_PATH_INFO *)calloc(path_count, sizeof(*paths));
    DISPLAYCONFIG_MODE_INFO *modes = (DISPLAYCONFIG_MODE_INFO *)calloc(mode_count > 0 ? mode_count : 1, sizeof(*modes));
    BOOL found = FALSE;
    if (paths != NULL && modes != NULL &&
        QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &path_count, paths, &mode_count, modes, NULL) == ERROR_SUCCESS) {
        for (UINT32 i = 0; i < path_count && !found; i++) {
            DISPLAYCONFIG_SOURCE_DEVICE_NAME source;
            ZeroMemory(&source, sizeof(source));
            source.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
            source.header.size = sizeof(source);
            source.header.adapterId = paths[i].sourceInfo.adapterId;
            source.header.id = paths[i].sourceInfo.id;
            if (DisplayConfigGetDeviceInfo(&source.header) != ERROR_SUCCESS) continue;
            if (lstrcmpiW(source.viewGdiDeviceName, device) != 0) continue;
            DISPLAYCONFIG_TARGET_DEVICE_NAME target;
            ZeroMemory(&target, sizeof(target));
            target.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
            target.header.size = sizeof(target);
            target.header.adapterId = paths[i].targetInfo.adapterId;
            target.header.id = paths[i].targetInfo.id;
            if (DisplayConfigGetDeviceInfo(&target.header) == ERROR_SUCCESS && target.monitorFriendlyDeviceName[0] != 0) {
                lstrcpynW(out, target.monitorFriendlyDeviceName, capacity);
                found = TRUE;
            }
        }
    }
    free(paths);
    free(modes);
    return found;
}

/* ---------------------------------------------------------------- pixels */

typedef struct {
    HDC screen;
    HDC memory;
    HBITMAP bitmap;
    HGDIOBJ previous;
    uint32_t *bits;
    int width;
    int height;
} Surface;

static BOOL surface_open(Surface *s, int width, int height) {
    ZeroMemory(s, sizeof(*s));
    s->width = width;
    s->height = height;
    s->screen = GetDC(NULL);
    if (s->screen == NULL) return FALSE;
    s->memory = CreateCompatibleDC(s->screen);
    if (s->memory == NULL) return FALSE;
    BITMAPINFO info;
    ZeroMemory(&info, sizeof(info));
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height; /* top-down */
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = NULL;
    s->bitmap = CreateDIBSection(s->screen, &info, DIB_RGB_COLORS, &bits, NULL, 0);
    if (s->bitmap == NULL || bits == NULL) return FALSE;
    s->bits = (uint32_t *)bits;
    s->previous = SelectObject(s->memory, s->bitmap);
    return TRUE;
}

static void surface_close(Surface *s) {
    if (s->memory != NULL && s->previous != NULL) SelectObject(s->memory, s->previous);
    if (s->bitmap != NULL) DeleteObject(s->bitmap);
    if (s->memory != NULL) DeleteDC(s->memory);
    if (s->screen != NULL) ReleaseDC(NULL, s->screen);
    ZeroMemory(s, sizeof(*s));
}

/* Draws the cursor as it is on screen, [origin] being the surface's screen position. */
static void draw_cursor(HDC dc, int origin_x, int origin_y) {
    CURSORINFO cursor;
    ZeroMemory(&cursor, sizeof(cursor));
    cursor.cbSize = sizeof(cursor);
    if (!GetCursorInfo(&cursor) || !(cursor.flags & CURSOR_SHOWING) || cursor.hCursor == NULL) return;
    int hot_x = 0, hot_y = 0;
    ICONINFO icon;
    if (GetIconInfo(cursor.hCursor, &icon)) {
        hot_x = (int)icon.xHotspot;
        hot_y = (int)icon.yHotspot;
        if (icon.hbmMask != NULL) DeleteObject(icon.hbmMask);
        if (icon.hbmColor != NULL) DeleteObject(icon.hbmColor);
    }
    DrawIconEx(dc, cursor.ptScreenPos.x - hot_x - origin_x, cursor.ptScreenPos.y - hot_y - origin_y, cursor.hCursor,
               0, 0, 0, NULL, DI_NORMAL);
}

/*
 * Copies [width] x [height] pixels starting at ([x], [y]) of the surface into a new jintArray,
 * alpha forced opaque (GDI leaves it undefined).
 */
static jintArray to_java(JNIEnv *env, const Surface *s, int x, int y, int width, int height) {
    jintArray array = (*env)->NewIntArray(env, width * height);
    if (array == NULL) return NULL; /* OutOfMemoryError pending */
    for (int row = 0; row < height; row++) {
        uint32_t *line = s->bits + (size_t)(y + row) * (size_t)s->width + (size_t)x;
        for (int col = 0; col < width; col++) line[col] |= 0xFF000000u;
        (*env)->SetIntArrayRegion(env, array, row * width, width, (const jint *)line);
    }
    return array;
}

static BOOL too_large(int width, int height) {
    return (long long)width * (long long)height > MAX_PIXELS;
}

/* Blits the screen rectangle into a new surface; FALSE on failure. */
static BOOL blit_screen(Surface *s, int left, int top, int width, int height, BOOL cursor) {
    if (!surface_open(s, width, height)) return FALSE;
    if (!BitBlt(s->memory, 0, 0, width, height, s->screen, left, top, SRCCOPY | CAPTUREBLT)) return FALSE;
    if (cursor) draw_cursor(s->memory, left, top);
    GdiFlush();
    return TRUE;
}

/* ---------------------------------------------------------------- PrintWindow off-thread */

/*
 * PrintWindow sends WM_PRINT to the window's thread and waits for it without a timeout: a
 * window whose thread does not pump (busy, or blocked on the very thread that asked for the
 * capture) would block the caller for good. It therefore runs on a worker thread; when it
 * does not finish in time the caller falls back to the screen and abandons the job, which the
 * worker frees whenever PrintWindow eventually returns.
 */
#define PRINT_WINDOW_TIMEOUT_MS 2000

typedef struct {
    volatile LONG refs;
    HWND hwnd;
    int width;
    int height;
    BOOL cursor;
    int origin_x;
    int origin_y;
    BOOL ok;
    HANDLE done;
    Surface surface;
} PrintJob;

static void print_job_release(PrintJob *job) {
    if (InterlockedDecrement(&job->refs) != 0) return;
    surface_close(&job->surface);
    if (job->done != NULL) CloseHandle(job->done);
    free(job);
}

static void print_job_run(PrintJob *job) {
    if (surface_open(&job->surface, job->width, job->height) &&
        PrintWindow(job->hwnd, job->surface.memory, PW_RENDERFULLCONTENT)) {
        if (job->cursor) draw_cursor(job->surface.memory, job->origin_x, job->origin_y);
        GdiFlush();
        job->ok = TRUE;
    }
}

static unsigned __stdcall print_job_thread(void *param) {
    PrintJob *job = (PrintJob *)param;
    DPI_AWARENESS_CONTEXT old = enter_physical_pixels();
    print_job_run(job);
    leave_physical_pixels(old);
    SetEvent(job->done);
    print_job_release(job);
    return 0;
}

/* The printed window, or NULL (failed or timed out); release the job with print_job_release. */
static PrintJob *print_window(HWND hwnd, int width, int height, BOOL cursor, int origin_x, int origin_y) {
    PrintJob *job = (PrintJob *)calloc(1, sizeof(PrintJob));
    if (job == NULL) return NULL;
    job->refs = 1;
    job->hwnd = hwnd;
    job->width = width;
    job->height = height;
    job->cursor = cursor;
    job->origin_x = origin_x;
    job->origin_y = origin_y;

    /* The window's own thread would wait on itself: print inline, the message is a direct call. */
    if (GetWindowThreadProcessId(hwnd, NULL) == GetCurrentThreadId()) {
        print_job_run(job);
    } else {
        job->done = CreateEventW(NULL, TRUE, FALSE, NULL);
        if (job->done == NULL) {
            print_job_release(job);
            return NULL;
        }
        InterlockedIncrement(&job->refs);
        HANDLE thread = (HANDLE)_beginthreadex(NULL, 0, print_job_thread, job, 0, NULL);
        if (thread == NULL) {
            InterlockedDecrement(&job->refs);
            print_job_release(job);
            return NULL;
        }
        CloseHandle(thread);
        if (WaitForSingleObject(job->done, PRINT_WINDOW_TIMEOUT_MS) != WAIT_OBJECT_0) {
            print_job_release(job);
            return NULL;
        }
    }
    if (!job->ok) {
        print_job_release(job);
        return NULL;
    }
    return job;
}

/* ---------------------------------------------------------------- JNI */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeBackend(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return BACKEND_GDI;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeListDisplays(JNIEnv *env, jclass cls,
                                                                                         jobject sink,
                                                                                         jobjectArray message) {
    (void)cls;
    jclass sink_class = (*env)->GetObjectClass(env, sink);
    jmethodID add = (*env)->GetMethodID(env, sink_class, "add", "(Ljava/lang/String;Ljava/lang/String;IIIIIIFZ)V");
    (*env)->DeleteLocalRef(env, sink_class);
    if (add == NULL) {
        nucleus_jni_clear_exception(env);
        set_message(env, message, "DisplayCollector.add not found");
        return STATUS_FAILED;
    }

    MonitorList *list = (MonitorList *)calloc(1, sizeof(MonitorList));
    if (list == NULL) return STATUS_FAILED;
    DPI_AWARENESS_CONTEXT old = enter_physical_pixels();
    enumerate_monitors(list);
    leave_physical_pixels(old);

    int status = STATUS_OK;
    for (int i = 0; i < list->count; i++) {
        const Monitor *m = &list->items[i];
        WCHAR name[128];
        if (!friendly_name(m->device, name, 128)) lstrcpynW(name, m->device, 128);
        jstring id = (*env)->NewString(env, (const jchar *)m->device, (jsize)lstrlenW(m->device));
        jstring label = (*env)->NewString(env, (const jchar *)name, (jsize)lstrlenW(name));
        if (id == NULL || label == NULL) {
            status = STATUS_FAILED;
            break; /* OutOfMemoryError pending: let it propagate */
        }
        int width = m->rect.right - m->rect.left;
        int height = m->rect.bottom - m->rect.top;
        (*env)->CallVoidMethod(env, sink, add, id, label, (jint)m->rect.left, (jint)m->rect.top, (jint)width,
                               (jint)height, (jint)width, (jint)height, (jfloat)m->dpi / 96.0f,
                               (jboolean)(m->primary ? JNI_TRUE : JNI_FALSE));
        (*env)->DeleteLocalRef(env, id);
        (*env)->DeleteLocalRef(env, label);
        if (nucleus_jni_clear_exception(env)) {
            set_message(env, message, "DisplayCollector.add threw");
            status = STATUS_FAILED;
            break;
        }
    }
    free(list);
    return status;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureDisplay(
    JNIEnv *env, jclass cls, jstring display_id, jint x, jint y, jint width, jint height, jboolean include_cursor,
    jintArray result, jobjectArray message) {
    (void)cls;
    WCHAR device[CCHDEVICENAME];
    const jchar *chars = (*env)->GetStringChars(env, display_id, NULL);
    if (chars == NULL) return NULL;
    jsize length = (*env)->GetStringLength(env, display_id);
    if (length >= CCHDEVICENAME) length = CCHDEVICENAME - 1;
    memcpy(device, chars, (size_t)length * sizeof(WCHAR));
    device[length] = 0;
    (*env)->ReleaseStringChars(env, display_id, chars);

    MonitorList *list = (MonitorList *)calloc(1, sizeof(MonitorList));
    if (list == NULL) {
        fail(env, result, message, STATUS_FAILED, "Out of memory");
        return NULL;
    }
    DPI_AWARENESS_CONTEXT old = enter_physical_pixels();
    enumerate_monitors(list);
    const Monitor *monitor = NULL;
    for (int i = 0; i < list->count; i++) {
        if (lstrcmpiW(list->items[i].device, device) == 0) {
            monitor = &list->items[i];
            break;
        }
    }

    jintArray pixels = NULL;
    if (monitor == NULL) {
        SetLastError(0);
        fail(env, result, message, STATUS_DISPLAY_NOT_FOUND, "No such display");
    } else {
        int display_width = monitor->rect.right - monitor->rect.left;
        int display_height = monitor->rect.bottom - monitor->rect.top;
        long long left = 0, top = 0, right = display_width, bottom = display_height;
        if (width > 0 && height > 0) {
            left = x > 0 ? x : 0;
            top = y > 0 ? y : 0;
            right = (long long)x + width < display_width ? (long long)x + width : display_width;
            bottom = (long long)y + height < display_height ? (long long)y + height : display_height;
        }
        int w = (int)(right - left);
        int h = (int)(bottom - top);
        if (right <= left || bottom <= top) {
            SetLastError(0);
            fail(env, result, message, STATUS_INVALID_REGION, "Region does not intersect the display");
        } else if (too_large(w, h)) {
            SetLastError(0);
            fail(env, result, message, STATUS_FAILED, "Region too large");
        } else {
            Surface surface;
            ZeroMemory(&surface, sizeof(surface));
            if (blit_screen(&surface, monitor->rect.left + (int)left, monitor->rect.top + (int)top, w, h,
                            include_cursor)) {
                pixels = to_java(env, &surface, 0, 0, w, h);
                if (pixels != NULL) set_result(env, result, STATUS_OK, w, h);
            } else {
                fail(env, result, message, STATUS_FAILED, "BitBlt failed (secure desktop or session locked?)");
            }
            surface_close(&surface);
        }
    }
    leave_physical_pixels(old);
    free(list);
    return pixels;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureWindow(JNIEnv *env, jclass cls,
                                                                                          jlong window_id,
                                                                                          jboolean include_cursor,
                                                                                          jintArray result,
                                                                                          jobjectArray message) {
    (void)cls;
    HWND hwnd = (HWND)(intptr_t)window_id;
    SetLastError(0);
    if (hwnd == NULL || !IsWindow(hwnd)) {
        fail(env, result, message, STATUS_WINDOW_NOT_FOUND, "No such window");
        return NULL;
    }
    if (IsIconic(hwnd) || !IsWindowVisible(hwnd)) {
        fail(env, result, message, STATUS_WINDOW_NOT_FOUND, "Window is minimized or hidden");
        return NULL;
    }

    DPI_AWARENESS_CONTEXT old = enter_physical_pixels();
    jintArray pixels = NULL;
    RECT window_rect, frame;
    if (!GetWindowRect(hwnd, &window_rect)) {
        fail(env, result, message, STATUS_WINDOW_NOT_FOUND, "GetWindowRect failed");
        leave_physical_pixels(old);
        return NULL;
    }
    /* The visible frame, without the invisible resize borders and the shadow. */
    if (FAILED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &frame, sizeof(frame)))) frame = window_rect;
    if (!IntersectRect(&frame, &frame, &window_rect)) frame = window_rect;

    int full_width = window_rect.right - window_rect.left;
    int full_height = window_rect.bottom - window_rect.top;
    int w = frame.right - frame.left;
    int h = frame.bottom - frame.top;
    if (w <= 0 || h <= 0 || too_large(full_width, full_height)) {
        SetLastError(0);
        fail(env, result, message, STATUS_WINDOW_NOT_FOUND, "Window has no capturable area");
        leave_physical_pixels(old);
        return NULL;
    }

    Surface surface;
    ZeroMemory(&surface, sizeof(surface));
    PrintJob *job = NULL;
    if (!IsHungAppWindow(hwnd)) {
        job = print_window(hwnd, full_width, full_height, include_cursor, window_rect.left, window_rect.top);
    }
    if (job != NULL) {
        pixels = to_java(env, &job->surface, frame.left - window_rect.left, frame.top - window_rect.top, w, h);
        if (pixels != NULL) set_result(env, result, STATUS_OK, w, h);
        print_job_release(job);
    } else if (!(*env)->ExceptionCheck(env)) {
        /* Hung, slow or refusing window: what is visible of it on screen. */
        if (blit_screen(&surface, frame.left, frame.top, w, h, include_cursor)) {
            pixels = to_java(env, &surface, 0, 0, w, h);
            if (pixels != NULL) set_result(env, result, STATUS_OK, w, h);
        } else {
            fail(env, result, message, STATUS_FAILED, "PrintWindow and BitBlt failed");
        }
        surface_close(&surface);
    }
    leave_physical_pixels(old);
    return pixels;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePermissionStatus(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return PERMISSION_NOT_REQUIRED;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeRequestPermission(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return PERMISSION_NOT_REQUIRED;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePortalScreenshot(
    JNIEnv *env, jclass cls, jboolean interactive, jint timeout_ms, jintArray result, jobjectArray message) {
    (void)cls;
    (void)interactive;
    (void)timeout_ms;
    SetLastError(0);
    fail(env, result, message, STATUS_UNSUPPORTED, "xdg-desktop-portal is Linux only");
    return NULL;
}

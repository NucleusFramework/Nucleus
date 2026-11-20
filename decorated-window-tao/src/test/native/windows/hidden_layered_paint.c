/* Bounded regression test against the actual GL helper DLL, without a JVM or
 * Tao event loop. Run from an x64 Native Tools command prompt:
 *
 * cl /nologo /W4 hidden_layered_paint.c /link user32.lib
 * hidden_layered_paint.exe <nucleus_tao_gl.dll> <ANGLE-directory>
 *
 * The ANGLE directory must contain libEGL.dll and libGLESv2.dll. The original
 * ValidateRect handler fails after 100 dispatches; BeginPaint/EndPaint clears
 * the update in one child paint. No process is left busy or waiting on exit.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>

typedef int64_t (WINAPI *AttachFn)(void *, void *, int64_t);
typedef void (WINAPI *ResizeFn)(void *, void *, int64_t, int, int, float);
typedef void (WINAPI *DetachFn)(void *, void *, int64_t);

static HWND surface;

static BOOL CALLBACK findSurface(HWND hwnd, LPARAM unused) {
    wchar_t name[128];
    (void)unused;
    if (GetClassNameW(hwnd, name, 128) && wcscmp(name, L"NucleusTaoGlSurface") == 0) {
        surface = hwnd;
        return FALSE;
    }
    return TRUE;
}

static int testHiddenLayeredParent(AttachFn attach, ResizeFn resize, DetachFn detach) {
    WNDCLASSW wc = {0};
    HWND parent = NULL;
    int64_t attachment = 0;
    int result = 1;
    int paints = 0;
    int dispatched = 0;
    MSG msg;
    RECT update;
    BOOL pendingBefore;
    BOOL pendingAfter;

    /* Class style 0 deliberately excludes CS_OWNDC: it is not required to
     * reproduce the problem. The popup remains hidden throughout the test. */
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = L"NucleusHiddenLayeredPaintTest";
    if (!RegisterClassW(&wc)) {
        fprintf(stderr, "RegisterClass failed: %lu\n", GetLastError());
        return 1;
    }

    parent = CreateWindowExW(0, wc.lpszClassName, L"", WS_POPUP,
        0, 0, 26, 26, NULL, NULL, wc.hInstance, NULL);
    if (!parent) {
        fprintf(stderr, "CreateWindowEx failed: %lu\n", GetLastError());
        goto cleanup;
    }

    /* These JNI entry points do not dereference env/class. Calling them here
     * exercises the production surface WndProc, not a copy of its handler. */
    attachment = attach(NULL, NULL, (int64_t)(intptr_t)parent);
    if (!attachment) {
        fprintf(stderr, "GL attachment failed; check the ANGLE DLLs.\n");
        goto cleanup;
    }
    resize(NULL, NULL, attachment, 1, 1, 1.0f);
    EnumChildWindows(parent, findSurface, 0);
    if (!surface) {
        fprintf(stderr, "The production GL surface was not created.\n");
        goto cleanup;
    }

    SetLastError(0);
    if (!SetWindowLongPtrW(parent, GWL_EXSTYLE, WS_EX_LAYERED) && GetLastError()) {
        fprintf(stderr, "Setting WS_EX_LAYERED failed: %lu\n", GetLastError());
        goto cleanup;
    }
    if (!SetLayeredWindowAttributes(parent, 0, 255, LWA_ALPHA)) {
        fprintf(stderr, "SetLayeredWindowAttributes failed: %lu\n", GetLastError());
        goto cleanup;
    }

    pendingBefore = GetUpdateRect(surface, &update, FALSE);
    printf("before: pending=%d rect=%ld,%ld,%ld,%ld\n", pendingBefore,
        update.left, update.top, update.right, update.bottom);

    /* Limit by dispatch count, not elapsed time, so a broken handler cannot
     * monopolize the thread indefinitely or hang the test runner. */
    while (dispatched < 100 && PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
        if (msg.hwnd == surface && msg.message == WM_PAINT) ++paints;
        DispatchMessageW(&msg);
        ++dispatched;
    }

    pendingAfter = GetUpdateRect(surface, &update, FALSE);
    printf("after: pending=%d child_paints=%d dispatched=%d\n",
        pendingAfter, paints, dispatched);
    if (pendingAfter || dispatched == 100) {
        fprintf(stderr, "FAIL: hidden layered child paint state was not cleared.\n");
        goto cleanup;
    }
    puts("PASS: production GL surface completed the paint cycle.");
    result = 0;

cleanup:
    if (attachment) detach(NULL, NULL, attachment);
    if (parent) DestroyWindow(parent);
    UnregisterClassW(wc.lpszClassName, wc.hInstance);
    return result;
}

int wmain(int argc, wchar_t **argv) {
    wchar_t path[32768];
    HMODULE egl;
    HMODULE gles;
    HMODULE helper;
    AttachFn attach;
    ResizeFn resize;
    DetachFn detach;
    int result;

    if (argc != 3) {
        fputs("Usage: hidden_layered_paint.exe <GL-helper-DLL> <ANGLE-directory>\n", stderr);
        return 1;
    }
    /* Load the specified ANGLE pair before the GL helper's basename lookup. */
    swprintf_s(path, 32768, L"%ls\\libGLESv2.dll", argv[2]);
    gles = LoadLibraryW(path);
    swprintf_s(path, 32768, L"%ls\\libEGL.dll", argv[2]);
    egl = LoadLibraryW(path);
    helper = LoadLibraryW(argv[1]);
    if (!egl || !gles || !helper) {
        fprintf(stderr, "DLL loading failed: %lu\n", GetLastError());
        return 1;
    }

    attach = (AttachFn)(void *)GetProcAddress(helper,
        "Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeAttach");
    resize = (ResizeFn)(void *)GetProcAddress(helper,
        "Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeResize");
    detach = (DetachFn)(void *)GetProcAddress(helper,
        "Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeDetach");
    if (!attach || !resize || !detach) {
        fputs("Required GL helper entry points are missing.\n", stderr);
        return 1;
    }

    result = testHiddenLayeredParent(attach, resize, detach);
    FreeLibrary(helper);
    FreeLibrary(egl);
    FreeLibrary(gles);
    return result;
}

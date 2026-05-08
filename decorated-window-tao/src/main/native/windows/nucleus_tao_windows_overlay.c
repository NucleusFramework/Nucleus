/**
 * JNI overlay HWND lifecycle + WndProc for the Tao Windows NativeView.
 *
 * Creates an owned WS_POPUP HWND with WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
 * hosts a Compose scene rendered through a transparent WGL context (see
 * nucleus_tao_windows_overlay_gl.c — Phase 4). WndProc handles
 * WM_NCHITTEST (region-based click-through), WM_MOUSEACTIVATE
 * (MA_NOACTIVATE), WM_DPICHANGED, and pointer/wheel forwarding to the
 * JNI callback.
 *
 * The window class registers with `CS_OWNDC` so `GetDC(hwnd)` returns a
 * stable HDC for the lifetime of the window — required by `wglMakeCurrent`
 * which would otherwise see different HDCs each frame.
 *
 * Owner-tracking: each overlay installs a chained subclass on its owner
 * HWND that listens for WM_WINDOWPOSCHANGED + WM_NCDESTROY,
 * batch-repositioning itself via DeferWindowPos. Multiple overlays per
 * owner are supported via a per-owner linked list of listeners chained
 * behind a single subclass. The plan suggested modifying the existing
 * deco.c subclass; we use a chained subclass-on-top instead — same
 * effect (Win32 subclass chains are first-in-last-out so deco.c's
 * handler still runs first), no cross-DLL coupling.
 *
 * Phase 3 paints a solid red FillRect placeholder so we can validate
 * the z-order + size + region-based click-through before WGL is wired
 * up; Phase 4 replaces the WM_PAINT handler with a no-op (SwapBuffers
 * owns the pixels from then on).
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include "nucleus_tao_windows_overlay_internal.h"

/* ============================================================ */
/*  Constants + globals                                         */
/* ============================================================ */

#define EVT_PTR_DOWN  1
#define EVT_PTR_UP    2
#define EVT_PTR_MOVE  3

#define BTN_NONE      0
#define BTN_PRIMARY   1
#define BTN_SECONDARY 2
#define BTN_MIDDLE    3

#define WHEEL_DELTA_F  120.0f

#define MAX_REGIONS 64

typedef struct OwnerNode OwnerNode;
typedef struct OverlayState OverlayState;

struct OverlayState {
    HWND hwnd;
    HWND owner;
    OwnerNode *ownerNode;
    OverlayState *nextOnOwner;

    /* Region table (overlay-local pixels, top-left origin). */
    CRITICAL_SECTION regionLock;
    float regions[MAX_REGIONS * 4];
    int regionCount;

    jobject pointerCb;  /* OverlayEventCallback */
    jobject keyCb;      /* OverlayKeyCallback (Phase 8) */

    int xPx;
    int yPx;
    int widthPx;
    int heightPx;

    /* WGL resources owned by overlay_gl.c. */
    GlSurface gl;

    /* Cursor handle to apply in WM_SETCURSOR. NULL → use the class
     * default (IDC_ARROW). Updated by nativeSetCursor when Compose's
     * setPointerIcon fires from the overlay scene's PlatformContext. */
    HCURSOR cursor;
};

struct OwnerNode {
    HWND owner;
    WNDPROC originalProc;
    OverlayState *firstOverlay;
    OwnerNode *next;
};

static CRITICAL_SECTION sOwnerListLock;
static OwnerNode *sOwnerList = NULL;
static volatile LONG sGlobalsInited = 0;
static const wchar_t *kOwnerProp = L"NucleusTaoOverlayOwnerNode";

static JavaVM *sJVM = NULL;
static jclass sPointerCbClass = NULL;
static jmethodID sOnPointerMethod = NULL; /* (IFFII)V */
static jmethodID sOnScrollMethod = NULL;  /* (FFFF)V */
static volatile LONG sCacheInited = 0;

static const wchar_t *kOverlayClassName = L"NucleusTaoOverlayCls";
static volatile LONG sClassRegistered = 0;

typedef DPI_AWARENESS_CONTEXT (WINAPI *PFN_SetThreadDpiAwarenessContext)(DPI_AWARENESS_CONTEXT);
static PFN_SetThreadDpiAwarenessContext pSetThreadDpiAwarenessContext = NULL;
static volatile LONG sDpiApiResolved = 0;

static void ensureGlobalsInit(void) {
    if (InterlockedCompareExchange(&sGlobalsInited, 1, 0) == 0) {
        InitializeCriticalSection(&sOwnerListLock);
    }
    if (InterlockedCompareExchange(&sDpiApiResolved, 1, 0) == 0) {
        HMODULE u = GetModuleHandleW(L"user32.dll");
        if (u) {
            pSetThreadDpiAwarenessContext = (PFN_SetThreadDpiAwarenessContext)
                GetProcAddress(u, "SetThreadDpiAwarenessContext");
        }
    }
}

static JNIEnv *attachThread(void) {
    if (sJVM == NULL) return NULL;
    JNIEnv *env = NULL;
    jint st = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (st == JNI_OK) return env;
    if (st == JNI_EDETACHED) {
        if ((*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) == JNI_OK) {
            return env;
        }
    }
    return NULL;
}

static void ensurePointerCallbackCache(JNIEnv *env, jobject sample) {
    if (sCacheInited) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (sample == NULL) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (!local) return;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return;
    jmethodID m1 = (*env)->GetMethodID(env, global, "onPointerEvent", "(IFFII)V");
    jmethodID m2 = (*env)->GetMethodID(env, global, "onScroll", "(FFFF)V");
    if (m1 && m2) {
        sPointerCbClass = global;
        sOnPointerMethod = m1;
        sOnScrollMethod = m2;
        InterlockedExchange(&sCacheInited, 1);
    } else {
        (*env)->DeleteGlobalRef(env, global);
    }
}

/* ============================================================ */
/*  Region hit-testing                                          */
/* ============================================================ */

static BOOL pointInsideAnyRegion(OverlayState *s, int xLocal, int yLocal) {
    BOOL hit = FALSE;
    EnterCriticalSection(&s->regionLock);
    for (int i = 0; i < s->regionCount; i++) {
        float x = s->regions[i * 4 + 0];
        float y = s->regions[i * 4 + 1];
        float w = s->regions[i * 4 + 2];
        float h = s->regions[i * 4 + 3];
        if ((float)xLocal >= x && (float)xLocal < x + w &&
            (float)yLocal >= y && (float)yLocal < y + h) {
            hit = TRUE;
            break;
        }
    }
    LeaveCriticalSection(&s->regionLock);
    return hit;
}

/* ============================================================ */
/*  Overlay WndProc                                             */
/* ============================================================ */

static int modifierMask(void) {
    int m = 0;
    if (GetKeyState(VK_SHIFT)   & 0x8000) m |= 0x01;
    if (GetKeyState(VK_CONTROL) & 0x8000) m |= 0x02;
    if (GetKeyState(VK_MENU)    & 0x8000) m |= 0x04;
    if (GetKeyState(VK_LWIN)    & 0x8000) m |= 0x08;
    if (GetKeyState(VK_RWIN)    & 0x8000) m |= 0x08;
    return m;
}

static void dispatchPointer(OverlayState *s, int type, int button, LPARAM lParam) {
    if (!s->pointerCb) return;
    JNIEnv *env = attachThread();
    if (!env || !sOnPointerMethod) return;
    int x = (short)LOWORD(lParam);
    int y = (short)HIWORD(lParam);
    (*env)->CallVoidMethod(env, s->pointerCb, sOnPointerMethod,
        (jint)type, (jfloat)x, (jfloat)y, (jint)button, (jint)modifierMask());
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void dispatchScroll(OverlayState *s, int xLocal, int yLocal,
                           float dx, float dy) {
    if (!s->pointerCb) return;
    JNIEnv *env = attachThread();
    if (!env || !sOnScrollMethod) return;
    (*env)->CallVoidMethod(env, s->pointerCb, sOnScrollMethod,
        (jfloat)xLocal, (jfloat)yLocal, (jfloat)dx, (jfloat)dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static LRESULT CALLBACK overlayWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    OverlayState *s = (OverlayState *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_MOUSEACTIVATE:
        return MA_NOACTIVATE;

    case WM_NCHITTEST: {
        if (!s) return HTCLIENT;
        POINT pt;
        pt.x = (short)LOWORD(l);
        pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        return pointInsideAnyRegion(s, pt.x, pt.y) ? HTCLIENT : HTTRANSPARENT;
    }

    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN:
        /* Drag Win32 keyboard focus back to the owner (main HWND) on
         * any click into the overlay. The overlay has WS_EX_NOACTIVATE
         * so it can't be focused itself; the WebView2 child HWND
         * grabs Win32 focus on its own clicks and HOLDS it, which
         * means subsequent WM_KEYDOWN events go to WebView2 instead of
         * the main HWND — and the popupKeyHandlers chain never fires.
         * SetFocus(owner) on overlay click brings keys back to the
         * main pipeline so Compose's focused overlay TextField
         * receives typed input. */
        if (s && s->owner) SetFocus(s->owner);
        if (s) {
            int btn = (msg == WM_LBUTTONDOWN) ? BTN_PRIMARY :
                      (msg == WM_RBUTTONDOWN) ? BTN_SECONDARY : BTN_MIDDLE;
            dispatchPointer(s, EVT_PTR_DOWN, btn, l);
        }
        return 0;
    case WM_LBUTTONUP:   if (s) dispatchPointer(s, EVT_PTR_UP,   BTN_PRIMARY, l);   return 0;
    case WM_RBUTTONUP:   if (s) dispatchPointer(s, EVT_PTR_UP,   BTN_SECONDARY, l); return 0;
    case WM_MBUTTONUP:   if (s) dispatchPointer(s, EVT_PTR_UP,   BTN_MIDDLE, l);    return 0;
    case WM_MOUSEMOVE:   if (s) dispatchPointer(s, EVT_PTR_MOVE, BTN_NONE, l);      return 0;

    case WM_MOUSEWHEEL: {
        if (!s) break;
        short delta = (short)HIWORD(w);
        POINT pt;
        pt.x = (short)LOWORD(l);
        pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(s, pt.x, pt.y, 0.0f, (float)delta / WHEEL_DELTA_F);
        return 0;
    }
    case WM_MOUSEHWHEEL: {
        if (!s) break;
        short delta = (short)HIWORD(w);
        POINT pt;
        pt.x = (short)LOWORD(l);
        pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(s, pt.x, pt.y, (float)delta / WHEEL_DELTA_F, 0.0f);
        return 0;
    }

    case WM_DPICHANGED: {
        const RECT *prc = (const RECT *)l;
        if (prc) {
            SetWindowPos(hwnd, NULL, prc->left, prc->top,
                         prc->right - prc->left, prc->bottom - prc->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_ERASEBKGND:
        /* Suppress GDI fill — SwapBuffers owns the pixels. */
        return 1;

    case WM_DWMCOMPOSITIONCHANGED:
        if (s) nucleus_tao_overlay_gl_rearm_blur(&s->gl);
        return 0;

    case WM_SETCURSOR:
        /* WM_SETCURSOR lParam packs:
         *   LOWORD = hit-test code (HTCLIENT, HTLEFT, ...)
         *   HIWORD = mouse message id (WM_MOUSEMOVE etc.)
         * We override the cursor only inside the client area. */
        if (s && s->cursor && LOWORD(l) == HTCLIENT) {
            SetCursor(s->cursor);
            return TRUE;
        }
        break;
    }
    return DefWindowProcW(hwnd, msg, w, l);
}

/* ============================================================ */
/*  Owner subclass — chained WNDPROC for WM_WINDOWPOSCHANGED    */
/* ============================================================ */

static OwnerNode *findOwnerNodeLocked(HWND owner) {
    for (OwnerNode *n = sOwnerList; n; n = n->next) {
        if (n->owner == owner) return n;
    }
    return NULL;
}

static void repositionOverlaysFromOwnerLocked(OwnerNode *n) {
    POINT origin = {0, 0};
    ClientToScreen(n->owner, &origin);

    int count = 0;
    for (OverlayState *o = n->firstOverlay; o; o = o->nextOnOwner) count++;
    if (count == 0) return;

    HDWP defer = BeginDeferWindowPos(count);
    for (OverlayState *o = n->firstOverlay; o; o = o->nextOnOwner) {
        if (defer) {
            defer = DeferWindowPos(defer, o->hwnd, NULL,
                origin.x + o->xPx, origin.y + o->yPx,
                o->widthPx, o->heightPx,
                SWP_NOACTIVATE | SWP_NOZORDER | SWP_NOREDRAW | SWP_DEFERERASE);
        } else {
            SetWindowPos(o->hwnd, NULL,
                origin.x + o->xPx, origin.y + o->yPx,
                o->widthPx, o->heightPx,
                SWP_NOACTIVATE | SWP_NOZORDER | SWP_NOREDRAW | SWP_DEFERERASE);
        }
    }
    if (defer) EndDeferWindowPos(defer);
}

static LRESULT CALLBACK ownerSubclassProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    OwnerNode *n = (OwnerNode *)GetPropW(hwnd, kOwnerProp);
    WNDPROC orig = n ? n->originalProc : NULL;

    if (msg == WM_WINDOWPOSCHANGED) {
        LRESULT r = orig ? CallWindowProcW(orig, hwnd, msg, w, l)
                         : DefWindowProcW(hwnd, msg, w, l);
        if (n) {
            EnterCriticalSection(&sOwnerListLock);
            repositionOverlaysFromOwnerLocked(n);
            LeaveCriticalSection(&sOwnerListLock);
        }
        return r;
    }

    if (msg == WM_NCDESTROY) {
        if (n) {
            SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)orig);
            RemovePropW(hwnd, kOwnerProp);
            EnterCriticalSection(&sOwnerListLock);
            OwnerNode **pp = &sOwnerList;
            while (*pp && *pp != n) pp = &(*pp)->next;
            if (*pp == n) *pp = n->next;
            LeaveCriticalSection(&sOwnerListLock);
            HeapFree(GetProcessHeap(), 0, n);
        }
        return orig ? CallWindowProcW(orig, hwnd, msg, w, l)
                    : DefWindowProcW(hwnd, msg, w, l);
    }

    return orig ? CallWindowProcW(orig, hwnd, msg, w, l)
                : DefWindowProcW(hwnd, msg, w, l);
}

static OwnerNode *getOrCreateOwnerNode(HWND owner) {
    EnterCriticalSection(&sOwnerListLock);
    OwnerNode *n = findOwnerNodeLocked(owner);
    if (n) { LeaveCriticalSection(&sOwnerListLock); return n; }
    n = (OwnerNode *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(OwnerNode));
    if (!n) { LeaveCriticalSection(&sOwnerListLock); return NULL; }
    n->owner = owner;
    n->next = sOwnerList;
    sOwnerList = n;
    LeaveCriticalSection(&sOwnerListLock);

    LONG_PTR prev = SetWindowLongPtrW(owner, GWLP_WNDPROC, (LONG_PTR)ownerSubclassProc);
    n->originalProc = (WNDPROC)prev;
    SetPropW(owner, kOwnerProp, (HANDLE)n);
    return n;
}

static void registerOverlayWithOwner(OwnerNode *n, OverlayState *s) {
    EnterCriticalSection(&sOwnerListLock);
    s->ownerNode = n;
    s->nextOnOwner = n->firstOverlay;
    n->firstOverlay = s;
    LeaveCriticalSection(&sOwnerListLock);
}

static void unregisterOverlayFromOwner(OverlayState *s) {
    if (!s->ownerNode) return;
    EnterCriticalSection(&sOwnerListLock);
    OverlayState **pp = &s->ownerNode->firstOverlay;
    while (*pp && *pp != s) pp = &(*pp)->nextOnOwner;
    if (*pp == s) *pp = s->nextOnOwner;
    s->ownerNode = NULL;
    s->nextOnOwner = NULL;
    LeaveCriticalSection(&sOwnerListLock);
}

/* ============================================================ */
/*  Window class registration                                   */
/* ============================================================ */

static void ensureClassRegistered(void) {
    if (InterlockedCompareExchange(&sClassRegistered, 1, 0) != 0) return;
    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    /* CS_OWNDC: GetDC(hwnd) returns a stable HDC for the window's
     * lifetime — required so wglMakeCurrent's HDC matches across frames. */
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = overlayWndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = kOverlayClassName;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);
}

/* ============================================================ */
/*  JNI exports                                                 */
/* ============================================================ */

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeCreateOverlay(
    JNIEnv *env, jclass clazz, jlong ownerHwnd) {
    (void)clazz;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    HWND owner = (HWND)(uintptr_t)ownerHwnd;
    if (!IsWindow(owner)) return 0;
    ensureGlobalsInit();
    ensureClassRegistered();

    DPI_AWARENESS_CONTEXT prevDpi = NULL;
    if (pSetThreadDpiAwarenessContext) {
        prevDpi = pSetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }

    /* WS_POPUP top-level owned by the main HWND. Top-level so DWM
     * honors the per-pixel alpha of the back buffer (child HWNDs are
     * composited as opaque RGBA — alpha=0 pixels would render as black
     * instead of transparent). HTTRANSPARENT from WM_NCHITTEST routes
     * clicks via WindowFromPoint semantics to whatever's underneath
     * at the screen point, including child HWNDs of the owner.
     *
     * Window-follow is implemented Kotlin-side via TaoWindow.onMoved
     * — re-issue nativeSetOverlayFrame on each owner movement. */
    HWND hwnd = CreateWindowExW(
        WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
        kOverlayClassName, L"",
        WS_POPUP,
        0, 0, 1, 1,
        owner, NULL, GetModuleHandleW(NULL), NULL);

    if (pSetThreadDpiAwarenessContext && prevDpi) {
        pSetThreadDpiAwarenessContext(prevDpi);
    }

    if (!hwnd) return 0;

    OverlayState *s = (OverlayState *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY,
                                                sizeof(OverlayState));
    if (!s) { DestroyWindow(hwnd); return 0; }
    s->hwnd = hwnd;
    s->owner = owner;
    InitializeCriticalSection(&s->regionLock);
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)s);

    /* Set up the transparent WGL context BEFORE showing the window so
     * DwmEnableBlurBehindWindow + DWM polish (rounded corners, dark mode,
     * extended frame for shadow) is in effect on the very first paint. */
    s->gl.hwnd = hwnd;
    if (!nucleus_tao_overlay_gl_init(&s->gl)) {
        /* Init failed — tear down everything we created and return 0. */
        unregisterOverlayFromOwner(s);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        DestroyWindow(hwnd);
        DeleteCriticalSection(&s->regionLock);
        HeapFree(GetProcessHeap(), 0, s);
        return 0;
    }

    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    return (jlong)(uintptr_t)s;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayFrame(
    JNIEnv *env, jclass clazz, jlong overlay,
    jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s || !IsWindow(s->hwnd)) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    s->xPx = xPx; s->yPx = yPx;
    s->widthPx = widthPx; s->heightPx = heightPx;
    /* Convert owner-local coords to screen coords for SetWindowPos
     * (top-level WS_POPUP coords are screen-absolute). Called both on
     * Compose layout changes AND on TaoWindow.onMoved (Kotlin side). */
    POINT origin = {0, 0};
    ClientToScreen(s->owner, &origin);
    SetWindowPos(s->hwnd, NULL,
        origin.x + xPx, origin.y + yPx, widthPx, heightPx,
        SWP_NOACTIVATE | SWP_NOZORDER);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayRegions(
    JNIEnv *env, jclass clazz, jlong overlay, jfloatArray rectsXYWHPx, jint count) {
    (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s) return;
    if (count < 0) count = 0;
    if (count > MAX_REGIONS) count = MAX_REGIONS;

    EnterCriticalSection(&s->regionLock);
    s->regionCount = count;
    if (count > 0 && rectsXYWHPx) {
        (*env)->GetFloatArrayRegion(env, rectsXYWHPx, 0, count * 4, s->regions);
    }
    /* Snapshot rect data while holding the lock so we can build the
     * HRGN below without keeping the critical section open during the
     * GDI calls. */
    int snapshotCount = s->regionCount;
    float snapshot[MAX_REGIONS * 4];
    if (snapshotCount > 0) {
        for (int i = 0; i < snapshotCount * 4; i++) snapshot[i] = s->regions[i];
    }
    LeaveCriticalSection(&s->regionLock);

    /* Build a window region as the union of all interactive rects, in
     * overlay-local coords. SetWindowRgn restricts BOTH visibility AND
     * hit-testing to the region — outside it, the overlay HWND has no
     * presence, so clicks fall through directly to whatever's behind
     * (the WebView child HWND of the owner). This is the proper
     * Win32 way to do "click-through except in this rect" for a
     * top-level WS_POPUP — HTTRANSPARENT only routes to top-level
     * siblings, not to child HWNDs of the owner. */
    if (!IsWindow(s->hwnd)) return;
    HRGN combined = NULL;
    if (snapshotCount == 0) {
        /* No interactive regions yet — overlay has no clickable / visible
         * area. An empty region still draws nothing and consumes nothing. */
        combined = CreateRectRgn(0, 0, 0, 0);
    } else {
        for (int i = 0; i < snapshotCount; i++) {
            int x = (int)snapshot[i * 4 + 0];
            int y = (int)snapshot[i * 4 + 1];
            int w = (int)snapshot[i * 4 + 2];
            int h = (int)snapshot[i * 4 + 3];
            HRGN rect = CreateRectRgn(x, y, x + w, y + h);
            if (!rect) continue;
            if (!combined) {
                combined = rect;
            } else {
                CombineRgn(combined, combined, rect, RGN_OR);
                DeleteObject(rect);
            }
        }
        if (!combined) combined = CreateRectRgn(0, 0, 0, 0);
    }
    /* SetWindowRgn takes ownership on success; on failure we delete. */
    if (!SetWindowRgn(s->hwnd, combined, TRUE)) {
        DeleteObject(combined);
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayCallback(
    JNIEnv *env, jclass clazz, jlong overlay, jobject callback) {
    (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s) return;
    if (callback) ensurePointerCallbackCache(env, callback);

    jobject prev = s->pointerCb;
    s->pointerCb = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    if (prev) (*env)->DeleteGlobalRef(env, prev);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayKeyCallback(
    JNIEnv *env, jclass clazz, jlong overlay, jobject callback) {
    (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s) return;
    /* Phase 3 stores the ref; Phase 8 wires it into the host's key pipeline. */
    jobject prev = s->keyCb;
    s->keyCb = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    if (prev) (*env)->DeleteGlobalRef(env, prev);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)env; (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s || !s->gl.hdc || !s->gl.hglrc) return JNI_FALSE;
    return wglMakeCurrent(s->gl.hdc, s->gl.hglrc) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSwapBuffers(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)env; (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s || !s->gl.hdc) return;
    SwapBuffers(s->gl.hdc);
    DwmFlush();
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetCursor(
    JNIEnv *env, jclass clazz, jlong overlay, jint cursorCode) {
    (void)env; (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s) return;
    LPCWSTR id;
    switch (cursorCode) {
        case 1: id = (LPCWSTR)IDC_IBEAM; break;
        case 2: id = (LPCWSTR)IDC_HAND;  break;
        case 3: id = (LPCWSTR)IDC_CROSS; break;
        default: id = (LPCWSTR)IDC_ARROW; break;
    }
    s->cursor = LoadCursorW(NULL, id);
    /* Re-set the cursor immediately if the pointer is currently on us. */
    POINT pt;
    if (GetCursorPos(&pt)) {
        HWND hover = WindowFromPoint(pt);
        if (hover == s->hwnd && s->cursor) SetCursor(s->cursor);
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeReleaseOverlay(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)clazz;
    OverlayState *s = (OverlayState *)(uintptr_t)overlay;
    if (!s) return;

    unregisterOverlayFromOwner(s);

    /* Tear down WGL before destroying the HWND so wglDeleteContext sees
     * a live HDC. */
    nucleus_tao_overlay_gl_destroy(&s->gl);

    if (IsWindow(s->hwnd)) {
        SetWindowLongPtrW(s->hwnd, GWLP_USERDATA, 0);
        DestroyWindow(s->hwnd);
    }

    if (s->pointerCb) { (*env)->DeleteGlobalRef(env, s->pointerCb); s->pointerCb = NULL; }
    if (s->keyCb)     { (*env)->DeleteGlobalRef(env, s->keyCb);     s->keyCb = NULL; }

    DeleteCriticalSection(&s->regionLock);
    HeapFree(GetProcessHeap(), 0, s);
}

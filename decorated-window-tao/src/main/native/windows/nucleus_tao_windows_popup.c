/**
 * JNI popup HWND lifecycle for the Tao Windows NativeView.
 *
 * Each popup is a top-level WS_POPUP HWND owned by the parent (the Tao
 * main HWND, even for nested popups — single-level owner chain) with
 * WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_NOREDIRECTIONBITMAP.
 * Rendering goes through the shared `nucleus_tao_overlay_gl_init`
 * EGL/ANGLE + DirectComposition bridge (overlay_dcomp.cpp).
 *
 * Outside-click dismissal:
 *   - A thread-local `WH_MOUSE` hook (mouseHookProc) observes every
 *     mouse message scheduled on the UI thread and, for each
 *     WM_*BUTTONDOWN, walks the open-popup chain. Any popup whose rect
 *     does not contain the click point AND whose HWND is not itself the
 *     click target fires its outside listener (Compose's `Popup` then
 *     calls `onDismissRequest`).
 *   - The hook is process-local (single thread, our UI thread) and
 *     refcounted: every popup that registers an outside listener
 *     increments the count; the hook is unhooked when the last popup
 *     drops it. The hook NEVER consumes the message — it returns
 *     `CallNextHookEx` so the message dispatches normally to its real
 *     target HWND.
 *   - Mirrors macOS's `NSEvent.addLocalMonitor` semantics (observe,
 *     don't consume). The earlier `SetCapture(popupHwnd)` approach
 *     forced the popup HWND to become foreground despite
 *     WS_EX_NOACTIVATE — the parent received WM_KILLFOCUS, Compose
 *     rendered the parent as inactive, and clicks were swallowed
 *     because `WindowInfo.isWindowFocused` flipped to false.
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include <timeapi.h>

/* Posted by the WH_MOUSE_LL proc to defer the outside-click JNI upcall to
 * the panel's WndProc (a low-level hook must never block). wParam = button. */
#define WM_APP_OUTSIDE_CLICK (WM_APP + 0x51)
#include "nucleus_tao_windows_overlay_internal.h"

#define EVT_PTR_DOWN  1
#define EVT_PTR_UP    2
#define EVT_PTR_MOVE  3

#define BTN_NONE      0
#define BTN_PRIMARY   1
#define BTN_SECONDARY 2
#define BTN_MIDDLE    3

#define WHEEL_DELTA_F  120.0f

/* Win8+; not in every SDK header set our /NODEFAULTLIB build pulls. */
#ifndef WS_EX_NOREDIRECTIONBITMAP
#define WS_EX_NOREDIRECTIONBITMAP 0x00200000L
#endif

typedef struct PopupState PopupState;
struct PopupState {
    HWND parent;
    GlSurface gl;
    BOOL focusable;

    /* Client-area cursor, applied on WM_SETCURSOR. NULL = arrow. */
    HCURSOR cursor;

    /* JNI callbacks (jobject global refs). */
    jobject eventCb;
    jobject outsideListener;

    /* TRUE while the outside-click hook is active for this popup. */
    BOOL outsideMonitorActive;

    /* Logical content rect inside the HWND. The HWND can be larger than
     * content to include Compose-drawn shadows/elevation, matching AWT's
     * drawBounds vs boundsInWindow split. */
    int contentX;
    int contentY;
    int contentWidth;
    int contentHeight;

    /* Linked list of currently-open popups (for outside-click
     * disambiguation between sibling/nested popups). The list is
     * ordered most-recent-first. */
    PopupState *nextOpen;
};

/* ============================================================ */
/*  Globals                                                     */
/* ============================================================ */

static const wchar_t *kPopupClassName = L"NucleusTaoPopupCls";
static volatile LONG sPopupClassRegistered = 0;

static CRITICAL_SECTION sOpenListLock;
static volatile LONG sOpenListInited = 0;
static PopupState *sOpenChainHead = NULL;

/* WH_MOUSE thread-local hook: process-wide observation of mouse messages
 * on the UI thread WITHOUT consuming them, mirroring macOS's
 * `NSEvent.addLocalMonitor`. Replaces the earlier SetCapture-based
 * outside-click monitor, which forced the popup HWND to become foreground
 * (despite WS_EX_NOACTIVATE) and made the parent window receive
 * WM_KILLFOCUS — Compose then rendered the parent as inactive and
 * subsequent clicks were dropped because `WindowInfo.isWindowFocused`
 * went false. */
static HHOOK sMouseHook = NULL;
static volatile LONG sMouseHookRefcount = 0;

static JavaVM *sJVM = NULL;
static jclass sEventCbClass = NULL;
static jmethodID sOnPointerMethod = NULL; /* (IFFII)V */
static jmethodID sOnScrollMethod = NULL;  /* (FFFF)V */
static jmethodID sOnKeyMethod = NULL;     /* (IIII)V */
static jclass sOutsideClass = NULL;
static jmethodID sOnOutsideClickMethod = NULL; /* (II)V */
static volatile LONG sCacheInitedBits = 0;

typedef DPI_AWARENESS_CONTEXT (WINAPI *PFN_SetThreadDpiAwarenessContext)(DPI_AWARENESS_CONTEXT);
static PFN_SetThreadDpiAwarenessContext pSetThreadDpiAwarenessContext = NULL;
static volatile LONG sDpiResolved = 0;

static void ensureGlobalsInit(void) {
    if (InterlockedCompareExchange(&sOpenListInited, 1, 0) == 0) {
        InitializeCriticalSection(&sOpenListLock);
    }
    if (InterlockedCompareExchange(&sDpiResolved, 1, 0) == 0) {
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
    if (st == JNI_EDETACHED &&
        (*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) == JNI_OK) {
        return env;
    }
    return NULL;
}

static void ensureEventCallbackCache(JNIEnv *env, jobject sample) {
    if (sCacheInitedBits & 1) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (!sample) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (!local) return;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return;
    jmethodID m1 = (*env)->GetMethodID(env, global, "onPointerEvent", "(IFFII)V");
    jmethodID m2 = (*env)->GetMethodID(env, global, "onScroll", "(FFFF)V");
    jmethodID m3 = (*env)->GetMethodID(env, global, "onKeyEvent", "(IIII)V");
    if (m1 && m2 && m3) {
        sEventCbClass = global;
        sOnPointerMethod = m1; sOnScrollMethod = m2; sOnKeyMethod = m3;
        InterlockedOr(&sCacheInitedBits, 1);
    } else {
        (*env)->DeleteGlobalRef(env, global);
    }
}

static void ensureOutsideCallbackCache(JNIEnv *env, jobject sample) {
    if (sCacheInitedBits & 2) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (!sample) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (!local) return;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return;
    jmethodID m = (*env)->GetMethodID(env, global, "onOutsideClick", "(II)V");
    if (m) {
        sOutsideClass = global; sOnOutsideClickMethod = m;
        InterlockedOr(&sCacheInitedBits, 2);
    } else {
        (*env)->DeleteGlobalRef(env, global);
    }
}

/* ============================================================ */
/*  Active-popup chain (capture handoff disambiguation)          */
/* ============================================================ */

static void chainAdd(PopupState *p) {
    EnterCriticalSection(&sOpenListLock);
    p->nextOpen = sOpenChainHead;
    sOpenChainHead = p;
    LeaveCriticalSection(&sOpenListLock);
}

static void chainRemove(PopupState *p) {
    EnterCriticalSection(&sOpenListLock);
    PopupState **pp = &sOpenChainHead;
    while (*pp && *pp != p) pp = &(*pp)->nextOpen;
    if (*pp == p) *pp = p->nextOpen;
    p->nextOpen = NULL;
    LeaveCriticalSection(&sOpenListLock);
}

static PopupState *findPopupByHwndLocked(HWND hwnd) {
    for (PopupState *q = sOpenChainHead; q; q = q->nextOpen) {
        if (q->gl.hwnd == hwnd) return q;
    }
    return NULL;
}

static BOOL pointInsideContentLocal(PopupState *p, int x, int y) {
    if (!p) return FALSE;
    return x >= p->contentX &&
           y >= p->contentY &&
           x < p->contentX + p->contentWidth &&
           y < p->contentY + p->contentHeight;
}

static BOOL pointInsideContentScreen(PopupState *p, POINT pt) {
    if (!p || !IsWindow(p->gl.hwnd)) return FALSE;
    RECT wr;
    if (!GetWindowRect(p->gl.hwnd, &wr)) return FALSE;
    RECT content;
    content.left = wr.left + p->contentX;
    content.top = wr.top + p->contentY;
    content.right = content.left + p->contentWidth;
    content.bottom = content.top + p->contentHeight;
    return PtInRect(&content, pt);
}

/* ============================================================ */
/*  Event dispatch helpers                                      */
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

static void dispatchPointer(PopupState *p, int type, int button, LPARAM lParam) {
    if (!p->eventCb || !sOnPointerMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    int x = (short)LOWORD(lParam);
    int y = (short)HIWORD(lParam);
    (*env)->CallVoidMethod(env, p->eventCb, sOnPointerMethod,
        (jint)type, (jfloat)x, (jfloat)y, (jint)button, (jint)modifierMask());
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void dispatchScroll(PopupState *p, int xLocal, int yLocal,
                           float dx, float dy) {
    if (!p->eventCb || !sOnScrollMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    (*env)->CallVoidMethod(env, p->eventCb, sOnScrollMethod,
        (jfloat)xLocal, (jfloat)yLocal, (jfloat)dx, (jfloat)dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void fireOutsideClick(PopupState *p, int button) {
    if (!p->outsideListener || !sOnOutsideClickMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    (*env)->CallVoidMethod(env, p->outsideListener, sOnOutsideClickMethod,
        (jint)1 /* press */, (jint)button);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* WH_MOUSE hook proc: observes every mouse message scheduled for
 * dispatch on the UI thread. For each WM_*BUTTONDOWN, iterate the open
 * popup chain; for any popup whose outside-click listener is installed
 * and whose rect does NOT contain the click point (and whose HWND is
 * not itself the click target — nested-popup case), fire the listener.
 * Always returns CallNextHookEx so the message dispatches normally to
 * its target HWND — no SetCapture, no focus theft, no swallowed clicks. */
static LRESULT CALLBACK mouseHookProc(int code, WPARAM w, LPARAM l) {
    if (code != HC_ACTION) return CallNextHookEx(NULL, code, w, l);
    UINT msg = (UINT)w;
    if (msg != WM_LBUTTONDOWN && msg != WM_RBUTTONDOWN && msg != WM_MBUTTONDOWN) {
        return CallNextHookEx(NULL, code, w, l);
    }
    MOUSEHOOKSTRUCT *m = (MOUSEHOOKSTRUCT *)l;
    if (!m) return CallNextHookEx(NULL, code, w, l);
    int btn = (msg == WM_LBUTTONDOWN) ? BTN_PRIMARY :
              (msg == WM_RBUTTONDOWN) ? BTN_SECONDARY : BTN_MIDDLE;

    /* Snapshot the open chain under the lock; fire listeners without
     * holding it so JNI calls don't pin the lock across an upcall. */
    PopupState *snapshot[16];
    int n = 0;
    EnterCriticalSection(&sOpenListLock);
    for (PopupState *q = sOpenChainHead; q && n < 16; q = q->nextOpen) {
        snapshot[n++] = q;
    }
    /* Click inside ANY known popup content is treated as "inside everything" —
     * mirrors macOS's `e.window == p` short-circuit. Without this, the
     * outer dropdown's outside listener would fire when the user clicks
     * a nested submenu item. */
    PopupState *targetPopup = m->hwnd ? findPopupByHwndLocked(m->hwnd) : NULL;
    BOOL targetIsKnownPopup = targetPopup && pointInsideContentScreen(targetPopup, m->pt);
    LeaveCriticalSection(&sOpenListLock);
    if (targetIsKnownPopup) return CallNextHookEx(NULL, code, w, l);

    for (int i = 0; i < n; i++) {
        PopupState *q = snapshot[i];
        if (!q->outsideMonitorActive || !q->outsideListener) continue;
        if (pointInsideContentScreen(q, m->pt)) continue;
        fireOutsideClick(q, btn);
    }
    return CallNextHookEx(NULL, code, w, l);
}

/* Refcount counts install REQUESTS; the hook itself is (re)tried whenever
 * absent, so a failed SetWindowsHookExW doesn't poison later installs. */
static void installMouseHookIfNeeded(void) {
    InterlockedIncrement(&sMouseHookRefcount);
    if (sMouseHook == NULL) {
        sMouseHook = SetWindowsHookExW(WH_MOUSE, mouseHookProc,
                                       NULL, GetCurrentThreadId());
    }
}

static void uninstallMouseHookIfLast(void) {
    if (InterlockedDecrement(&sMouseHookRefcount) == 0) {
        if (sMouseHook) {
            UnhookWindowsHookEx(sMouseHook);
            sMouseHook = NULL;
        }
    }
}

/* Global (low-level) variant for OWNERLESS panels: a thread-local WH_MOUSE
 * hook only observes messages dispatched to this thread's windows, so it
 * never sees clicks landing on other applications or the desktop — exactly
 * the clicks a standalone tray popup must dismiss on. WH_MOUSE_LL is
 * system-wide; its callback runs on the installing thread's message loop
 * (the Tao event loop, which pumps continuously). */
static HHOOK sMouseHookLL = NULL;
static volatile LONG sMouseHookLLRefcount = 0;

static LRESULT CALLBACK mouseHookLLProc(int code, WPARAM w, LPARAM l) {
    if (code != HC_ACTION) return CallNextHookEx(NULL, code, w, l);
    UINT msg = (UINT)w;
    if (msg != WM_LBUTTONDOWN && msg != WM_RBUTTONDOWN && msg != WM_MBUTTONDOWN) {
        return CallNextHookEx(NULL, code, w, l);
    }
    MSLLHOOKSTRUCT *m = (MSLLHOOKSTRUCT *)l;
    if (!m) return CallNextHookEx(NULL, code, w, l);
    int btn = (msg == WM_LBUTTONDOWN) ? BTN_PRIMARY :
              (msg == WM_RBUTTONDOWN) ? BTN_SECONDARY : BTN_MIDDLE;

    /* Only ownerless popups: owner-based ones are covered by the thread
     * hook and would double-fire otherwise. Fixed-size snapshot: more than
     * 16 simultaneous ownerless popups silently drop the tail — far above
     * any real usage (one tray popup). */
    PopupState *snapshot[16];
    int n = 0;
    EnterCriticalSection(&sOpenListLock);
    for (PopupState *q = sOpenChainHead; q && n < 16; q = q->nextOpen) {
        if (q->parent == NULL) snapshot[n++] = q;
    }
    HWND target = WindowFromPoint(m->pt);
    PopupState *targetPopup = target ? findPopupByHwndLocked(target) : NULL;
    BOOL targetIsKnownPopup = targetPopup && pointInsideContentScreen(targetPopup, m->pt);
    LeaveCriticalSection(&sOpenListLock);
    if (targetIsKnownPopup) return CallNextHookEx(NULL, code, w, l);

    for (int i = 0; i < n; i++) {
        PopupState *q = snapshot[i];
        if (!q->outsideMonitorActive || !q->outsideListener) continue;
        if (pointInsideContentScreen(q, m->pt)) continue;
        if (!IsWindowVisible(q->gl.hwnd)) continue;
        /* NEVER call into Java from a low-level hook: the callback can
         * trigger a recomposition, and a WH_MOUSE_LL proc that exceeds
         * LowLevelHooksTimeout (~300 ms) gets silently unhooked by Windows
         * while stalling the system-wide mouse. Defer to the WndProc. */
        PostMessageW(q->gl.hwnd, WM_APP_OUTSIDE_CLICK, (WPARAM)btn, 0);
    }
    return CallNextHookEx(NULL, code, w, l);
}

/* Same request-count/retry-if-absent contract as installMouseHookIfNeeded. */
static void installMouseHookLLIfNeeded(void) {
    InterlockedIncrement(&sMouseHookLLRefcount);
    if (sMouseHookLL == NULL) {
        sMouseHookLL = SetWindowsHookExW(WH_MOUSE_LL, mouseHookLLProc,
                                         GetModuleHandleW(NULL), 0);
    }
}

static void uninstallMouseHookLLIfLast(void) {
    if (InterlockedDecrement(&sMouseHookLLRefcount) == 0) {
        if (sMouseHookLL) {
            UnhookWindowsHookEx(sMouseHookLL);
            sMouseHookLL = NULL;
        }
    }
}

/* Moves the keyboard focus (and the foreground slot) to hwnd. GetFocus()
 * only describes the calling thread's queue, so it can still report hwnd
 * while another application is foreground and receiving all keys — always
 * compare against GetForegroundWindow(). When another thread is foreground,
 * SetForegroundWindow is subject to the foreground lock; briefly attaching
 * to that thread's input queue bypasses it (classic tray-popup pattern). */
static void takeKeyboardFocus(HWND hwnd) {
    HWND fg = GetForegroundWindow();
    if (fg == hwnd) {
        if (GetFocus() != hwnd) SetFocus(hwnd);
        return;
    }
    DWORD fgThread = fg ? GetWindowThreadProcessId(fg, NULL) : 0;
    DWORD myThread = GetCurrentThreadId();
    if (fgThread != 0 && fgThread != myThread) {
        AttachThreadInput(myThread, fgThread, TRUE);
        SetForegroundWindow(hwnd);
        SetFocus(hwnd);
        AttachThreadInput(myThread, fgThread, FALSE);
    } else {
        SetForegroundWindow(hwnd);
        SetFocus(hwnd);
    }
}

/* ============================================================ */
/*  Popup WndProc                                               */
/* ============================================================ */

static LRESULT CALLBACK popupWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    PopupState *p = (PopupState *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_POINTERACTIVATE:
        return p && p->focusable ? PA_ACTIVATE : PA_NOACTIVATE;

    case WM_POINTERDOWN:
        if (p && p->parent && !p->focusable) {
            SetForegroundWindow(p->parent);
            SetActiveWindow(p->parent);
            SetFocus(p->parent);
        }
        break;

    case WM_MOUSEACTIVATE:
        return p && p->focusable ? MA_ACTIVATE : MA_NOACTIVATE;

    case WM_SETCURSOR:
        if (p && LOWORD(l) == HTCLIENT) {
            SetCursor(p->cursor ? p->cursor : LoadCursorW(NULL, (LPCWSTR)IDC_ARROW));
            return TRUE;
        }
        break;

    case WM_NCHITTEST: {
        if (!p) return HTCLIENT;
        POINT pt;
        pt.x = (short)LOWORD(l);
        pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        return pointInsideContentLocal(p, pt.x, pt.y) ? HTCLIENT : HTTRANSPARENT;
    }

    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN: {
        if (!p) break;
        /* Focusable standalone panel: take keyboard focus explicitly — the
         * WS_EX_NOACTIVATE style suppresses mouse activation, and there is
         * no owner window to route keys through. */
        if (p->focusable && p->parent == NULL) {
            takeKeyboardFocus(hwnd);
        }
        /* Outside-click handling moved to the WH_MOUSE hook
         * (mouseHookProc). Any click that reaches the WndProc here is
         * by definition inside the popup's rect — dispatch straight to
         * Compose. */
        int btn = (msg == WM_LBUTTONDOWN) ? BTN_PRIMARY :
                  (msg == WM_RBUTTONDOWN) ? BTN_SECONDARY : BTN_MIDDLE;
        dispatchPointer(p, EVT_PTR_DOWN, btn, l);
        return 0;
    }

    case WM_KEYDOWN:
    case WM_SYSKEYDOWN:
    case WM_KEYUP:
    case WM_SYSKEYUP: {
        /* Standalone panels receive keyboard input directly (owner-based
         * popups get keys forwarded from the focused host window instead). */
        if (!p || !p->eventCb || !sOnKeyMethod) break;
        int type = (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) ? 1 : 2;
        int vk = (int)w;
        int mods = 0;
        if (GetKeyState(VK_SHIFT)   & 0x8000) mods |= 0x1;
        if (GetKeyState(VK_CONTROL) & 0x8000) mods |= 0x2;
        if (GetKeyState(VK_MENU)    & 0x8000) mods |= 0x4;
        if ((GetKeyState(VK_LWIN) | GetKeyState(VK_RWIN)) & 0x8000) mods |= 0x8;
        /* AltGr == right Alt + a synthesized left Ctrl on Windows. Report it
         * as plain text input, not as a Ctrl+Alt chord (mirrors tao's
         * filter_out_altgr for the window key path). */
        if ((GetKeyState(VK_RMENU) & 0x8000) && (mods & 0x2)) mods &= ~(0x2 | 0x4);
        int codePoint = 0;
        if (type == 1 && !(mods & 0x2) && !(mods & 0x4)) {
            BYTE ks[256];
            if (GetKeyboardState(ks)) {
                WCHAR buf[4];
                UINT sc = (UINT)((l >> 16) & 0xFF);
                /* 0x4 = don't mutate kernel keyboard state (Win10 1607+),
                 * keeps dead-key handling intact. */
                int r = ToUnicode((UINT)vk, sc, ks, buf, 4, 0x4);
                if (r >= 2 && IS_SURROGATE_PAIR(buf[0], buf[1])) {
                    codePoint = 0x10000 + (((buf[0] - 0xD800) << 10) | (buf[1] - 0xDC00));
                } else if (r >= 1 && buf[0] >= 0x20) {
                    codePoint = (int)buf[0];
                }
            }
        }
        JNIEnv *envK = attachThread();
        if (envK) {
            (*envK)->CallVoidMethod(envK, p->eventCb, sOnKeyMethod,
                (jint)type, (jint)vk, (jint)codePoint, (jint)mods);
            if ((*envK)->ExceptionCheck(envK)) (*envK)->ExceptionClear(envK);
        }
        return 0;
    }
    case WM_APP_OUTSIDE_CLICK:
        if (p) fireOutsideClick(p, (int)w);
        return 0;

    case WM_LBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_PRIMARY, l);   return 0;
    case WM_RBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_SECONDARY, l); return 0;
    case WM_MBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_MIDDLE, l);    return 0;
    case WM_MOUSEMOVE:   if (p) dispatchPointer(p, EVT_PTR_MOVE, BTN_NONE, l);      return 0;

    case WM_MOUSEWHEEL: {
        if (!p) break;
        short delta = (short)HIWORD(w);
        POINT pt; pt.x = (short)LOWORD(l); pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(p, pt.x, pt.y, 0.0f, (float)delta / WHEEL_DELTA_F);
        return 0;
    }
    case WM_MOUSEHWHEEL: {
        if (!p) break;
        short delta = (short)HIWORD(w);
        POINT pt; pt.x = (short)LOWORD(l); pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(p, pt.x, pt.y, (float)delta / WHEEL_DELTA_F, 0.0f);
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
        return 1;
    }
    return DefWindowProcW(hwnd, msg, w, l);
}

static void ensurePopupClassRegistered(void) {
    if (InterlockedCompareExchange(&sPopupClassRegistered, 1, 0) != 0) return;
    WNDCLASSW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = popupWndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = kPopupClassName;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);
}

/* ============================================================ */
/*  JNI exports                                                 */
/* ============================================================ */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeCreatePanel(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)clazz;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    /* parentHwnd == 0: standalone (ownerless) panel. Coordinates are then
     * absolute screen coordinates and the panel has no owner window. */
    HWND parent = (HWND)(uintptr_t)parentHwnd;
    if (parentHwnd != 0 && !IsWindow(parent)) return 0;
    if (parentHwnd == 0) parent = NULL;
    ensureGlobalsInit();
    ensurePopupClassRegistered();

    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;

    /* Resolve parent client → screen for initial placement (coordinates are
     * already screen-absolute for ownerless panels). */
    POINT origin = {0, 0};
    if (parent) ClientToScreen(parent, &origin);

    DPI_AWARENESS_CONTEXT prevDpi = NULL;
    if (pSetThreadDpiAwarenessContext) {
        prevDpi = pSetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }

    /* WS_EX_NOREDIRECTIONBITMAP: see the matching comment in overlay.c.
     * Ownerless panels are topmost: with no owner window there is nothing
     * keeping them above other applications' windows. */
    DWORD exStyle = WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_NOREDIRECTIONBITMAP;
    if (parent == NULL) exStyle |= WS_EX_TOPMOST;
    HWND hwnd = CreateWindowExW(
        exStyle,
        kPopupClassName, L"",
        WS_POPUP,
        origin.x + xPx, origin.y + yPx, widthPx, heightPx,
        parent, NULL, GetModuleHandleW(NULL), NULL);

    if (pSetThreadDpiAwarenessContext && prevDpi) {
        pSetThreadDpiAwarenessContext(prevDpi);
    }

    if (!hwnd) return 0;

    PopupState *p = (PopupState *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY,
                                            sizeof(PopupState));
    if (!p) { DestroyWindow(hwnd); return 0; }
    p->parent = parent;
    p->gl.hwnd = hwnd;
    /* Borrow the EGL trio of THIS popup's parent host window (resolved
     * per-HWND in createSurface), not the global — survives a sibling
     * DecoratedDialog's attach/detach which would otherwise wipe it. */
    p->gl.hostHwnd = parent;
    p->focusable = FALSE;
    p->contentX = 0;
    p->contentY = 0;
    p->contentWidth = widthPx;
    p->contentHeight = heightPx;
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)p);

    if (!nucleus_tao_overlay_gl_init(&p->gl, FALSE)) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        DestroyWindow(hwnd);
        HeapFree(GetProcessHeap(), 0, p);
        return 0;
    }

    chainAdd(p);
    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    return (jlong)(uintptr_t)p;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetFrameInWindow(
    JNIEnv *env, jclass clazz, jlong panel, jint xPx, jint yPx, jint widthPx, jint heightPx,
    jint contentXPx, jint contentYPx, jint contentWidthPx, jint contentHeightPx) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !IsWindow(p->gl.hwnd)) return;
    if (p->parent && !IsWindow(p->parent)) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    if (contentWidthPx < 1) contentWidthPx = 1;
    if (contentHeightPx < 1) contentHeightPx = 1;
    p->contentX = (int)contentXPx;
    p->contentY = (int)contentYPx;
    p->contentWidth = (int)contentWidthPx;
    p->contentHeight = (int)contentHeightPx;
    POINT origin = {0, 0};
    if (p->parent) ClientToScreen(p->parent, &origin);
    SetWindowPos(p->gl.hwnd, NULL,
        origin.x + xPx, origin.y + yPx, widthPx, heightPx,
        SWP_NOACTIVATE | SWP_NOZORDER);
    /* DComp swapchains don't track the HWND — resize explicitly
     * (no-op when only the position changed). */
    nucleus_tao_overlay_gl_resize(&p->gl, widthPx, heightPx);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panel, jboolean focusable) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    p->focusable = focusable ? TRUE : FALSE;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeContentHwnd(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    return p ? (jlong)(uintptr_t)p->gl.hwnd : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return JNI_FALSE;
    return nucleus_tao_overlay_gl_make_current(&p->gl) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSwapBuffers(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    nucleus_tao_overlay_gl_present(&p->gl);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    if (callback) ensureEventCallbackCache(env, callback);
    jobject prev = p->eventCb;
    p->eventCb = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    if (prev) (*env)->DeleteGlobalRef(env, prev);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel, jobject listener) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !listener) return;
    ensureOutsideCallbackCache(env, listener);
    jobject prev = p->outsideListener;
    p->outsideListener = (*env)->NewGlobalRef(env, listener);
    if (prev) (*env)->DeleteGlobalRef(env, prev);
    /* Install (refcounted) the thread-local WH_MOUSE hook. Replaces the
     * earlier SetCapture(popupHwnd) — see sMouseHook comment for why
     * SetCapture caused the parent to receive WM_KILLFOCUS and rendered
     * as inactive. */
    if (!p->outsideMonitorActive) {
        p->outsideMonitorActive = TRUE;
        if (p->parent == NULL) {
            installMouseHookLLIfNeeded();
        } else {
            installMouseHookIfNeeded();
        }
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    if (p->outsideMonitorActive) {
        p->outsideMonitorActive = FALSE;
        if (p->parent == NULL) {
            uninstallMouseHookLLIfLast();
        } else {
            uninstallMouseHookIfLast();
        }
    }
    if (p->outsideListener) {
        (*env)->DeleteGlobalRef(env, p->outsideListener);
        p->outsideListener = NULL;
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    /* Safety net if nativeUninstallOutsideClickMonitor was not called
     * before release (e.g., layer torn down without flushing). */
    if (p->outsideMonitorActive) {
        p->outsideMonitorActive = FALSE;
        if (p->parent == NULL) {
            uninstallMouseHookLLIfLast();
        } else {
            uninstallMouseHookIfLast();
        }
    }
    chainRemove(p);
    nucleus_tao_overlay_gl_destroy(&p->gl);
    if (IsWindow(p->gl.hwnd)) {
        SetWindowLongPtrW(p->gl.hwnd, GWLP_USERDATA, 0);
        DestroyWindow(p->gl.hwnd);
    }
    if (p->eventCb) { (*env)->DeleteGlobalRef(env, p->eventCb); p->eventCb = NULL; }
    if (p->outsideListener) { (*env)->DeleteGlobalRef(env, p->outsideListener); p->outsideListener = NULL; }
    HeapFree(GetProcessHeap(), 0, p);
}

/* Maps a TaoCursorIcon constant (NativeTaoBridge.kt) to a system cursor for
 * the panel's client area (consumed by WM_SETCURSOR above). */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetPanelCursor(
    JNIEnv *env, jclass clazz, jlong panel, jint kind)
{
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    LPCWSTR id;
    switch (kind) {
        case 1:  id = (LPCWSTR)IDC_IBEAM;       break; /* TEXT */
        case 2:  id = (LPCWSTR)IDC_HAND;        break; /* HAND */
        case 3:  id = (LPCWSTR)IDC_CROSS;       break; /* CROSSHAIR */
        case 4:  id = (LPCWSTR)IDC_WAIT;        break; /* WAIT */
        case 5:  id = (LPCWSTR)IDC_SIZEALL;     break; /* MOVE */
        case 6:  id = (LPCWSTR)IDC_NO;          break; /* NOT_ALLOWED */
        case 7:  id = (LPCWSTR)IDC_HELP;        break; /* HELP */
        case 8:  id = (LPCWSTR)IDC_APPSTARTING; break; /* PROGRESS */
        case 9:  id = (LPCWSTR)IDC_SIZEWE;      break; /* EW_RESIZE */
        case 10: id = (LPCWSTR)IDC_SIZENS;      break; /* NS_RESIZE */
        case 11: id = (LPCWSTR)IDC_SIZENESW;    break; /* NESW_RESIZE */
        case 12: id = (LPCWSTR)IDC_SIZENWSE;    break; /* NWSE_RESIZE */
        default: id = (LPCWSTR)IDC_ARROW;       break; /* DEFAULT */
    }
    p->cursor = LoadCursorW(NULL, id);
    /* Refresh immediately when the pointer is already over the panel. */
    POINT pt;
    if (GetCursorPos(&pt) && IsWindow(p->gl.hwnd)
        && WindowFromPoint(pt) == p->gl.hwnd) {
        SetCursor(p->cursor);
    }
}

/* System timer resolution, refcounted. Without timeBeginPeriod(1) the JVM's
 * scheduled executors wake on the default ~15.6 ms quantum, so a 16.7 ms
 * frame pace degenerates to ~30 fps with visible stutter. Enabled only
 * while an animated panel is visible (power cost when idle). */
static LONG sHighResTimerRefcount = 0;

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetHighResTimer(
    JNIEnv *env, jclass clazz, jboolean enable)
{
    (void)env; (void)clazz;
    if (enable) {
        if (InterlockedIncrement(&sHighResTimerRefcount) == 1) timeBeginPeriod(1);
    } else {
        if (InterlockedDecrement(&sHighResTimerRefcount) == 0) timeEndPeriod(1);
    }
}

/* Shows/hides the panel without releasing it. SW_SHOWNOACTIVATE keeps the
 * non-activating contract. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeWindows_nativeSetPanelVisible(
    JNIEnv *env, jclass clazz, jlong panel, jboolean visible)
{
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !IsWindow(p->gl.hwnd)) return;
    ShowWindow(p->gl.hwnd, visible ? SW_SHOWNOACTIVATE : SW_HIDE);
    /* Re-assert the topmost slot on show: focus round-trips through other
     * applications can leave the panel below newer topmost windows. */
    if (visible && p->parent == NULL) {
        SetWindowPos(p->gl.hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                     SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        /* Focusable tray-style popups take keyboard focus on show, so the
         * user can type without clicking inside first (same behavior as the
         * Windows clock/network flyouts). */
        if (p->focusable) takeKeyboardFocus(p->gl.hwnd);
    }
}

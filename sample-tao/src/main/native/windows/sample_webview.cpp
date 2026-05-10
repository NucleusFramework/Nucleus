/**
 * Direct WebView2 embedding for the sample-tao "WebView" tab demo, using
 * `CoreWebView2CompositionController` + DirectComposition for proper
 * rounded-corner clipping. `SetWindowRgn` does not work on WebView2 because
 * its content is rendered via DComp, bypassing the GDI region pipeline.
 *
 * Replaces the earlier wry-based Rust crate. Same JNI surface as the macOS
 * (.m) and Linux (.c) counterparts, plus `nativeSetCornerRadius`.
 *
 * Threading: every JNI entry point must run on the Tao main thread
 * (= the thread that created the parent HWND). WebView2's controller is
 * STA — off-thread access deadlocks.
 *
 * Async init: WebView2's Create*Async APIs are callback-driven. We block
 * the calling thread on a private event while pumping messages so the
 * COM callbacks can dispatch back to us. Standard pattern used by
 * WebView2 samples.
 */

#include <jni.h>
#include <windows.h>
#include <windowsx.h>
#include <unknwn.h>
#include <wrl.h>
#include <dcomp.h>
#include <WebView2.h>
#include <string>
#include <unordered_map>
#include <mutex>
#include <atomic>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "dcomp.lib")
#pragma comment(lib, "runtimeobject.lib")

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

/* ============================================================ */
/*  Dynamic loading of WebView2Loader.dll                       */
/* ============================================================ */

typedef HRESULT(STDMETHODCALLTYPE *PFN_CreateCoreWebView2EnvironmentWithOptions)(
    PCWSTR browserExecutableFolder,
    PCWSTR userDataFolder,
    ICoreWebView2EnvironmentOptions *environmentOptions,
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *environmentCreatedHandler);

static PFN_CreateCoreWebView2EnvironmentWithOptions s_pCreateEnv = nullptr;

static bool ensureLoaderLoaded() {
    if (s_pCreateEnv) return true;
    /* The loader DLL is shipped next to sample_tao_webview.dll in the same
     * cache directory by NativeLibraryLoader. We extend the DLL search path
     * to include our own directory, then LoadLibrary by name. */
    HMODULE self = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                       reinterpret_cast<LPCWSTR>(&ensureLoaderLoaded), &self);
    if (self) {
        wchar_t selfPath[MAX_PATH];
        if (GetModuleFileNameW(self, selfPath, MAX_PATH) > 0) {
            /* Strip filename, keep directory. */
            for (int i = (int)wcslen(selfPath) - 1; i >= 0; --i) {
                if (selfPath[i] == L'\\' || selfPath[i] == L'/') { selfPath[i] = 0; break; }
            }
            SetDllDirectoryW(selfPath);
        }
    }
    HMODULE loader = LoadLibraryW(L"WebView2Loader.dll");
    if (!loader) return false;
    s_pCreateEnv = reinterpret_cast<PFN_CreateCoreWebView2EnvironmentWithOptions>(
        GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions"));
    return s_pCreateEnv != nullptr;
}

/* ============================================================ */
/*  WebView state                                               */
/* ============================================================ */

struct WebViewState {
    HWND parent = nullptr;
    /* Chained subclass on [parent] for WM_MOUSE* forwarding. CompositionController
     * has no HWND of its own — the host is responsible for delivering input via
     * SendMouseInput/SendPointerInput. */
    WNDPROC originalParentProc = nullptr;
    bool subclassInstalled = false;
    ComPtr<ICoreWebView2Environment> env;
    ComPtr<ICoreWebView2CompositionController> compController;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webview;

    /* DComp tree:
     *   target -> rootVisual (clip applied here) -> webviewVisual (from CC). */
    ComPtr<IDCompositionDevice> dcompDevice;
    ComPtr<IDCompositionTarget> dcompTarget;
    ComPtr<IDCompositionVisual> rootVisual;

    int xPx = 0;
    int yPx = 0;
    int widthPx = 800;
    int heightPx = 600;
    float cornerRadiusPx = 0.0f;

    /* Navigation state — populated by event handlers. */
    std::atomic<bool> isLoading{false};
    std::atomic<bool> canGoBack{false};
    std::atomic<bool> canGoForward{false};
    std::wstring lastSource;
    std::mutex sourceMutex;

    /* Latest cursor requested by the WebView (e.g. I-beam over text, Hand
     * over links). System cursor handle — don't DestroyCursor. Updated in
     * the CursorChanged callback, applied in WM_SETCURSOR. */
    std::atomic<HCURSOR> currentCursor{nullptr};

    /* Event tokens for unhooking on release. */
    EventRegistrationToken navigationStartingToken{};
    EventRegistrationToken navigationCompletedToken{};
    EventRegistrationToken sourceChangedToken{};
    EventRegistrationToken historyChangedToken{};
    EventRegistrationToken cursorChangedToken{};
};

static std::mutex g_handlesMutex;
static std::unordered_map<jlong, WebViewState *> g_handles;
static std::atomic<jlong> g_nextHandle{1};

/* ============================================================ */
/*  Helpers                                                     */
/* ============================================================ */

/**
 * Pumps the message loop until [done] becomes true. WebView2's Create*
 * APIs are async-callback-driven and need a running message pump on the
 * calling thread to deliver the completion. Used to make `nativeCreate`
 * synchronous from JNI's perspective.
 */
static void pumpUntilDone(const std::atomic<bool> &done) {
    while (!done.load(std::memory_order_acquire)) {
        MSG msg;
        if (GetMessageW(&msg, nullptr, 0, 0) <= 0) break;
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

/**
 * Recreates [s.rootVisual]'s clip to a rounded-rect of the current size +
 * radius. DComp's `IDCompositionRectangleClip` doesn't directly support
 * per-corner radius for a rect — `SetTopLeftRadiusX` etc. are on
 * `IDCompositionRectangleClip` on Windows 8.1+, and we apply uniform
 * corners here. Caller must `Commit()` after.
 */
static void applyRoundedClip(WebViewState &s) {
    if (!s.dcompDevice || !s.rootVisual) return;
    if (s.cornerRadiusPx <= 0.0f) {
        s.rootVisual->SetClip(static_cast<IDCompositionClip *>(nullptr));
        return;
    }
    ComPtr<IDCompositionRectangleClip> clip;
    if (FAILED(s.dcompDevice->CreateRectangleClip(&clip))) return;
    float w = static_cast<float>(s.widthPx);
    float h = static_cast<float>(s.heightPx);
    float r = s.cornerRadiusPx;
    float cap = (w < h ? w : h) * 0.5f;
    if (r > cap) r = cap;
    clip->SetLeft(0.0f); clip->SetTop(0.0f);
    clip->SetRight(w);   clip->SetBottom(h);
    clip->SetTopLeftRadiusX(r);     clip->SetTopLeftRadiusY(r);
    clip->SetTopRightRadiusX(r);    clip->SetTopRightRadiusY(r);
    clip->SetBottomLeftRadiusX(r);  clip->SetBottomLeftRadiusY(r);
    clip->SetBottomRightRadiusX(r); clip->SetBottomRightRadiusY(r);
    s.rootVisual->SetClip(clip.Get());
}

/**
 * Applies position + size. With CompositionController, the controller has
 * no notion of "where in the parent it lives" — that's the host's job via
 * the DComp tree. So:
 *  - `put_Bounds` carries only the **size** (origin pinned at 0,0). It
 *    defines the WebView's logical content rect; SendMouseInput coords
 *    are interpreted relative to this rect's origin (i.e. WebView-local).
 *  - The root visual is offset to (xPx, yPx) so DComp draws the WebView
 *    where it should appear inside the parent's client area.
 *  - Callers of SendMouseInput must subtract (xPx, yPx) from the parent-
 *    client click coords to get WebView-local coords.
 * Caller must `Commit()` after.
 */
static void applyBounds(WebViewState &s) {
    if (s.controller) {
        RECT bounds = {0, 0, s.widthPx, s.heightPx};
        s.controller->put_Bounds(bounds);
    }
    if (s.rootVisual) {
        s.rootVisual->SetOffsetX(static_cast<float>(s.xPx));
        s.rootVisual->SetOffsetY(static_cast<float>(s.yPx));
    }
}

static std::wstring jstringToWide(JNIEnv *env, jstring s) {
    if (!s) return {};
    const jchar *chars = env->GetStringChars(s, nullptr);
    jsize len = env->GetStringLength(s);
    std::wstring out(reinterpret_cast<const wchar_t *>(chars),
                     reinterpret_cast<const wchar_t *>(chars) + len);
    env->ReleaseStringChars(s, chars);
    return out;
}

static jstring wideToJstring(JNIEnv *env, const std::wstring &s) {
    return env->NewString(reinterpret_cast<const jchar *>(s.c_str()),
                          static_cast<jsize>(s.size()));
}

/* ============================================================ */
/*  Input forwarding (parent HWND subclass)                     */
/* ============================================================ */

/**
 * Per-WebView state pointer attached to the parent HWND via SetProp so the
 * chained subclass procedure can find it. One WebView per parent in the
 * sample — if multiple were ever needed, this would become a list head.
 */
static const wchar_t *kSampleWvProp = L"NucleusSampleWebViewState";

static bool insideWebView(WebViewState *s, int xClient, int yClient) {
    return xClient >= s->xPx && xClient < s->xPx + s->widthPx &&
           yClient >= s->yPx && yClient < s->yPx + s->heightPx;
}

static LRESULT CALLBACK parentSubclassProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    auto *s = static_cast<WebViewState *>(GetPropW(hwnd, kSampleWvProp));
    auto callPrev = [&]() -> LRESULT {
        return s && s->originalParentProc
            ? CallWindowProcW(s->originalParentProc, hwnd, msg, w, l)
            : DefWindowProcW(hwnd, msg, w, l);
    };
    if (!s || !s->compController) return callPrev();

    switch (msg) {
        case WM_MOUSEMOVE:
        case WM_LBUTTONDOWN: case WM_LBUTTONUP: case WM_LBUTTONDBLCLK:
        case WM_RBUTTONDOWN: case WM_RBUTTONUP: case WM_RBUTTONDBLCLK:
        case WM_MBUTTONDOWN: case WM_MBUTTONUP: case WM_MBUTTONDBLCLK:
        case WM_XBUTTONDOWN: case WM_XBUTTONUP: case WM_XBUTTONDBLCLK: {
            int x = GET_X_LPARAM(l);
            int y = GET_Y_LPARAM(l);
            if (!insideWebView(s, x, y)) break;
            /* SendMouseInput coords are interpreted against the controller's
             * `put_Bounds` rect (origin = 0,0, size = w×h in our model).
             * Subtract the WebView's screen-area top-left to get coords
             * relative to that rect. */
            POINT pt = {x - s->xPx, y - s->yPx};
            UINT32 mouseData = 0;
            if (msg == WM_XBUTTONDOWN || msg == WM_XBUTTONUP || msg == WM_XBUTTONDBLCLK) {
                /* WPARAM HIWORD encodes XBUTTON1/XBUTTON2; SendMouseInput's
                 * mouseData mirrors that for X-button events. */
                mouseData = GET_XBUTTON_WPARAM(w);
            }
            s->compController->SendMouseInput(
                static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(msg),
                static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(GET_KEYSTATE_WPARAM(w)),
                mouseData, pt);
            if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN ||
                msg == WM_MBUTTONDOWN || msg == WM_XBUTTONDOWN) {
                if (s->controller) {
                    s->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
                }
            }
            return 0;
        }
        case WM_MOUSEWHEEL:
        case WM_MOUSEHWHEEL: {
            /* Wheel messages arrive in screen coords (per Win32 docs).
             * Convert to parent client first, then to WebView-local. */
            POINT pt = {GET_X_LPARAM(l), GET_Y_LPARAM(l)};
            ScreenToClient(hwnd, &pt);
            if (!insideWebView(s, pt.x, pt.y)) break;
            POINT local = {pt.x - s->xPx, pt.y - s->yPx};
            s->compController->SendMouseInput(
                static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(msg),
                static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(GET_KEYSTATE_WPARAM(w)),
                static_cast<UINT32>(GET_WHEEL_DELTA_WPARAM(w)), local);
            return 0;
        }
        case WM_MOUSELEAVE: {
            POINT pt = {-1, -1};
            s->compController->SendMouseInput(
                COREWEBVIEW2_MOUSE_EVENT_KIND_LEAVE,
                static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(0), 0, pt);
            break;  /* let the original WndProc see it too */
        }
        case WM_SETCURSOR: {
            /* lParam packs:
             *   LOWORD = hit-test code (HTCLIENT, HTLEFT, ...)
             *   HIWORD = mouse message id
             * Only override inside the client area where the pointer is
             * actually on the WebView surface. The current cursor pos is
             * not in lParam — we sample GetCursorPos to test bounds. */
            if (LOWORD(l) != HTCLIENT) break;
            HCURSOR cur = s->currentCursor.load(std::memory_order_acquire);
            if (!cur) break;
            POINT pt;
            if (!GetCursorPos(&pt)) break;
            ScreenToClient(hwnd, &pt);
            if (!insideWebView(s, pt.x, pt.y)) break;
            SetCursor(cur);
            return TRUE;
        }
    }
    return callPrev();
}

static void installParentSubclass(WebViewState *s) {
    if (!s || s->subclassInstalled || !IsWindow(s->parent)) return;
    SetPropW(s->parent, kSampleWvProp, static_cast<HANDLE>(s));
    LONG_PTR prev = SetWindowLongPtrW(s->parent, GWLP_WNDPROC,
                                      reinterpret_cast<LONG_PTR>(parentSubclassProc));
    s->originalParentProc = reinterpret_cast<WNDPROC>(prev);
    s->subclassInstalled = true;
}

static void uninstallParentSubclass(WebViewState *s) {
    if (!s || !s->subclassInstalled) return;
    if (IsWindow(s->parent)) {
        /* Best-effort restore: only revert if the current proc is still ours.
         * If another subclass installed itself on top after us, leave it alone
         * — uninstalling out of order would break their chain. We just clear
         * our prop so our subclass becomes a transparent passthrough. */
        WNDPROC current = reinterpret_cast<WNDPROC>(
            GetWindowLongPtrW(s->parent, GWLP_WNDPROC));
        if (current == parentSubclassProc) {
            SetWindowLongPtrW(s->parent, GWLP_WNDPROC,
                              reinterpret_cast<LONG_PTR>(s->originalParentProc));
        }
        RemovePropW(s->parent, kSampleWvProp);
    }
    s->subclassInstalled = false;
}

/* ============================================================ */
/*  Event hookup                                                */
/* ============================================================ */

static void hookEvents(WebViewState *s) {
    using namespace Microsoft::WRL;
    auto *raw = s; /* pointer captured by handlers — lifetime managed by g_handles */

    s->webview->add_NavigationStarting(
        Callback<ICoreWebView2NavigationStartingEventHandler>(
            [raw](ICoreWebView2 *, ICoreWebView2NavigationStartingEventArgs *) -> HRESULT {
                raw->isLoading.store(true, std::memory_order_release);
                return S_OK;
            }).Get(),
        &s->navigationStartingToken);

    s->webview->add_NavigationCompleted(
        Callback<ICoreWebView2NavigationCompletedEventHandler>(
            [raw](ICoreWebView2 *, ICoreWebView2NavigationCompletedEventArgs *) -> HRESULT {
                raw->isLoading.store(false, std::memory_order_release);
                return S_OK;
            }).Get(),
        &s->navigationCompletedToken);

    s->webview->add_SourceChanged(
        Callback<ICoreWebView2SourceChangedEventHandler>(
            [raw](ICoreWebView2 *wv, ICoreWebView2SourceChangedEventArgs *) -> HRESULT {
                LPWSTR src = nullptr;
                if (SUCCEEDED(wv->get_Source(&src)) && src) {
                    std::lock_guard<std::mutex> lock(raw->sourceMutex);
                    raw->lastSource.assign(src);
                    CoTaskMemFree(src);
                }
                return S_OK;
            }).Get(),
        &s->sourceChangedToken);

    s->webview->add_HistoryChanged(
        Callback<ICoreWebView2HistoryChangedEventHandler>(
            [raw](ICoreWebView2 *wv, IUnknown *) -> HRESULT {
                BOOL b = FALSE;
                if (SUCCEEDED(wv->get_CanGoBack(&b))) raw->canGoBack.store(b == TRUE);
                if (SUCCEEDED(wv->get_CanGoForward(&b))) raw->canGoForward.store(b == TRUE);
                return S_OK;
            }).Get(),
        &s->historyChangedToken);

    s->compController->add_CursorChanged(
        Callback<ICoreWebView2CursorChangedEventHandler>(
            [raw](ICoreWebView2CompositionController *cc, IUnknown *) -> HRESULT {
                HCURSOR cursor = nullptr;
                if (SUCCEEDED(cc->get_Cursor(&cursor))) {
                    raw->currentCursor.store(cursor, std::memory_order_release);
                    /* Force WM_SETCURSOR to re-fire if the pointer is on us
                     * right now — without this, the new cursor only takes
                     * effect on the next mouse-move. */
                    POINT pt;
                    if (GetCursorPos(&pt) && IsWindow(raw->parent)) {
                        ScreenToClient(raw->parent, &pt);
                        if (insideWebView(raw, pt.x, pt.y)) SetCursor(cursor);
                    }
                }
                return S_OK;
            }).Get(),
        &s->cursorChangedToken);
}

/* ============================================================ */
/*  Create — async, blocks calling thread until done            */
/* ============================================================ */

static WebViewState *createWebView(HWND parent, const std::wstring &initialUrl) {
    if (!ensureLoaderLoaded()) return nullptr;

    /* AWT initializes COM as STA on the EDT; Tao's main thread is separate.
     * CoInitializeEx is idempotent — safe to call. RPC_E_CHANGED_MODE means
     * COM is already inited with a different threading model, which is fine
     * for our purposes (we just need it inited). */
    HRESULT coInitHr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    (void)coInitHr;

    auto *s = new WebViewState();
    s->parent = parent;

    /* Step 1: create the WebView2 environment. */
    std::atomic<bool> envDone{false};
    HRESULT envResult = E_FAIL;

    HRESULT hr = s_pCreateEnv(
        nullptr, nullptr, nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [&](HRESULT result, ICoreWebView2Environment *env) -> HRESULT {
                envResult = result;
                if (SUCCEEDED(result) && env) s->env = env;
                envDone.store(true, std::memory_order_release);
                return S_OK;
            }).Get());
    if (FAILED(hr)) { delete s; return nullptr; }
    pumpUntilDone(envDone);
    if (FAILED(envResult) || !s->env) { delete s; return nullptr; }

    /* Step 2: create the CompositionController bound to the parent HWND.
     * The CC owns input routing (pointer events go through it via SendMouseInput
     * / SendPointerInput) but does NOT auto-host a Win32 child HWND — its
     * visual must be added to a DComp tree we own. */
    std::atomic<bool> ccDone{false};
    HRESULT ccResult = E_FAIL;

    ComPtr<ICoreWebView2Environment3> env3;
    if (FAILED(s->env.As(&env3)) || !env3) { delete s; return nullptr; }

    hr = env3->CreateCoreWebView2CompositionController(
        parent,
        Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
            [&](HRESULT result, ICoreWebView2CompositionController *cc) -> HRESULT {
                ccResult = result;
                if (SUCCEEDED(result) && cc) s->compController = cc;
                ccDone.store(true, std::memory_order_release);
                return S_OK;
            }).Get());
    if (FAILED(hr)) { delete s; return nullptr; }
    pumpUntilDone(ccDone);
    if (FAILED(ccResult) || !s->compController) { delete s; return nullptr; }

    /* Step 3: get the regular Controller (for put_Bounds, get_IsVisible, etc.)
     * and the underlying ICoreWebView2 (for navigation). */
    if (FAILED(s->compController.As(&s->controller)) || !s->controller) {
        delete s; return nullptr;
    }
    if (FAILED(s->controller->get_CoreWebView2(&s->webview)) || !s->webview) {
        delete s; return nullptr;
    }

    /* Step 4: build a DComp tree, attach the WebView's root visual to it. */
    if (FAILED(DCompositionCreateDevice(nullptr, IID_PPV_ARGS(&s->dcompDevice)))) {
        delete s; return nullptr;
    }
    if (FAILED(s->dcompDevice->CreateTargetForHwnd(parent, TRUE, &s->dcompTarget))) {
        delete s; return nullptr;
    }
    if (FAILED(s->dcompDevice->CreateVisual(&s->rootVisual))) { delete s; return nullptr; }
    s->dcompTarget->SetRoot(s->rootVisual.Get());

    /* CC's RootVisualTarget accepts an IUnknown — usually a DComp visual. */
    ComPtr<IUnknown> webviewVisualUnk;
    if (FAILED(s->compController->put_RootVisualTarget(s->rootVisual.Get()))) {
        delete s; return nullptr;
    }

    /* Step 5: bounds + initial commit. */
    applyBounds(*s);
    s->dcompDevice->Commit();

    /* Step 6: hook nav-state events (CanGoBack, IsLoading, SourceChanged...). */
    hookEvents(s);

    /* Step 7: subclass the parent HWND so we can forward Win32 mouse messages
     * via SendMouseInput. CompositionController has no HWND of its own and
     * receives no input automatically — this is by design (it lets the host
     * decide hit-test routing). */
    installParentSubclass(s);

    /* Step 8: kick off the initial navigation. */
    if (!initialUrl.empty()) {
        s->webview->Navigate(initialUrl.c_str());
        std::lock_guard<std::mutex> lock(s->sourceMutex);
        s->lastSource = initialUrl;
    }

    /* Make the controller visible. */
    s->controller->put_IsVisible(TRUE);
    return s;
}

static void releaseWebView(WebViewState *s) {
    if (!s) return;
    uninstallParentSubclass(s);
    if (s->webview) {
        s->webview->remove_NavigationStarting(s->navigationStartingToken);
        s->webview->remove_NavigationCompleted(s->navigationCompletedToken);
        s->webview->remove_SourceChanged(s->sourceChangedToken);
        s->webview->remove_HistoryChanged(s->historyChangedToken);
    }
    if (s->compController) {
        s->compController->remove_CursorChanged(s->cursorChangedToken);
    }
    if (s->controller) s->controller->Close();
    /* DComp resources cleaned up by ComPtr. */
    delete s;
}

static WebViewState *lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(g_handlesMutex);
    auto it = g_handles.find(handle);
    return it == g_handles.end() ? nullptr : it->second;
}

}  /* anonymous namespace */

/* ============================================================ */
/*  JNI exports                                                 */
/* ============================================================ */

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCreate(
    JNIEnv *env, jclass, jlong parentHwnd, jstring initialUrl) {
    if (parentHwnd == 0) return 0;
    HWND parent = reinterpret_cast<HWND>(static_cast<uintptr_t>(parentHwnd));
    if (!IsWindow(parent)) return 0;

    std::wstring url = jstringToWide(env, initialUrl);
    WebViewState *s = createWebView(parent, url);
    if (!s) return 0;

    jlong handle = g_nextHandle.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_handlesMutex);
        g_handles[handle] = s;
    }
    return handle;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeRelease(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_handlesMutex);
        auto it = g_handles.find(handle);
        if (it == g_handles.end()) return;
        s = it->second;
        g_handles.erase(it);
    }
    releaseWebView(s);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeLoadUrl(
    JNIEnv *env, jclass, jlong handle, jstring url) {
    WebViewState *s = lookup(handle);
    if (!s || !s->webview) return;
    std::wstring w = jstringToWide(env, url);
    s->webview->Navigate(w.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeGoBack(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    if (s && s->webview) s->webview->GoBack();
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeGoForward(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    if (s && s->webview) s->webview->GoForward();
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeReload(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    if (s && s->webview) s->webview->Reload();
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCanGoBack(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    return (s && s->canGoBack.load(std::memory_order_acquire)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCanGoForward(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    return (s && s->canGoForward.load(std::memory_order_acquire)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeCurrentUrl(
    JNIEnv *env, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    if (!s) return nullptr;
    std::wstring copy;
    {
        std::lock_guard<std::mutex> lock(s->sourceMutex);
        copy = s->lastSource;
    }
    return wideToJstring(env, copy);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeIsLoading(
    JNIEnv *, jclass, jlong handle) {
    WebViewState *s = lookup(handle);
    return (s && s->isLoading.load(std::memory_order_acquire)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeSetBounds(
    JNIEnv *, jclass, jlong handle, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    WebViewState *s = lookup(handle);
    if (!s) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    s->xPx = xPx; s->yPx = yPx;
    s->widthPx = widthPx; s->heightPx = heightPx;
    applyBounds(*s);
    /* Re-apply rounded clip — its rect depends on size. */
    applyRoundedClip(*s);
    if (s->dcompDevice) s->dcompDevice->Commit();
    /* Tell WebView2 the parent window may have moved on screen so it can
     * reposition popups (autocomplete, video PIP) and recompute IME anchors. */
    if (s->controller) s->controller->NotifyParentWindowPositionChanged();
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_sampletao_SampleWebViewWindowsBridge_nativeSetCornerRadius(
    JNIEnv *, jclass, jlong handle, jfloat radiusPx) {
    WebViewState *s = lookup(handle);
    if (!s) return;
    if (radiusPx < 0) radiusPx = 0;
    s->cornerRadiusPx = radiusPx;
    applyRoundedClip(*s);
    if (s->dcompDevice) s->dcompDevice->Commit();
}

}  /* extern "C" */

/* DllMain — keep the runtime happy under /MD or default CRT. */
BOOL APIENTRY DllMain(HMODULE, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_DETACH) {
        /* WebView2 + DComp leak gracefully on process exit. We don't drain
         * g_handles here — JNI nativeRelease should have cleared them. */
    }
    return TRUE;
}

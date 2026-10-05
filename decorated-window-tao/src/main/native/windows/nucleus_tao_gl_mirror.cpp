/**
 * DirectComposition mirror of a Tao window's frames (Windows).
 *
 * Why: the window presents through ANGLE's blt-model swapchain on its render
 * child (nucleus_tao_gl.c), and a blt present reaches DWM through the GDI
 * redirection surface, clipped to the window's visible region — which ends at
 * the desktop's edge. A frame presented while the window is partly off-screen
 * never reaches DWM for that part: the window shows a white (never painted) or
 * stale band when it is dragged into view, and the taskbar thumbnail and
 * Alt+Tab, which DWM draws from the same copy, show it even if it never is.
 *
 * Why not replace the blt present: no flip-model or DirectComposition present
 * can be tied to a window geometry change — DWM takes the frame and the new
 * geometry on whichever compositor tick each one catches, so a resized window
 * trembles around its content (measured: ResizeSyncHeadfulCases; Chromium,
 * Windows Terminal, Zed and WinUI 3 all live with it, while Flutter — same
 * ANGLE stack — and Druid keep the blt present inside WM_SIZE for exactly this
 * reason). The blt present lands with the geometry; nothing else does.
 *
 * So both, but only while it matters: every frame still goes through the blt
 * swapchain, and while the window overhangs the desktop (the only state in
 * which a blt present loses pixels — nucleus_tao_gl.c's swapWithMirror
 * creates the mirror then and releases it once a frame of the window fully
 * on-screen has been swapped) it is also
 * copied into a texture shown by a non-topmost DirectComposition visual on the
 * render child. DWM composes a window as its HDC/Present content, then its
 * non-topmost DComp tree, then its child windows (IDCompositionDevice::
 * CreateTargetForHwnd), so the visual covers the blt content — whole, the
 * off-screen part included — and stays under NativeView siblings. While the
 * window's size changes the visual is taken down *before* the new geometry is
 * applied (the deco subclass reports WM_WINDOWPOSCHANGING) — the blt content
 * under it is the same frame, so nothing shows — and the resize is exactly the
 * blt one. Once the size has been stable for a while the visual comes back
 * with the latest frame, copied from the texture without involving the JVM.
 *
 * Opaque windows only: the visual would draw a translucent frame over the same
 * frame in the blt content.
 *
 * Threading: everything runs on the Tao event-loop thread (the WM_TIMER that
 * resumes the visual is delivered there too). Compiled as C++ for dcomp.h;
 * /NODEFAULTLIB clean (no CRT, no exceptions, no new/delete).
 */

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <dcomp.h>

#include "nucleus_tao_gl_mirror.h"

typedef HRESULT (WINAPI *PFN_DCompositionCreateDevice)(IDXGIDevice *, REFIID, void **);
typedef HRESULT (WINAPI *PFN_DwmFlush)(void);

static PFN_DwmFlush resolveDwmFlush() {
    static PFN_DwmFlush fn = NULL;
    static BOOL resolved = FALSE;
    if (!resolved) {
        resolved = TRUE;
        HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
        if (dwm) fn = (PFN_DwmFlush)GetProcAddress(dwm, "DwmFlush");
    }
    return fn;
}

static PFN_DCompositionCreateDevice resolveDComp() {
    static PFN_DCompositionCreateDevice fn = NULL;
    static BOOL resolved = FALSE;
    if (!resolved) {
        resolved = TRUE;
        HMODULE dcomp = LoadLibraryW(L"dcomp.dll");
        if (dcomp) fn = (PFN_DCompositionCreateDevice)GetProcAddress(dcomp, "DCompositionCreateDevice");
    }
    return fn;
}

struct NucleusTaoMirror {
    HWND hwnd;                     /* render child: target + resume timer */
    ID3D11Device        *device;   /* ANGLE's — AddRef'd */
    ID3D11DeviceContext *imCtx;
    IDCompositionDevice *dcomp;
    IDCompositionTarget *target;
    IDCompositionVisual *visual;
    IDXGISwapChain1     *chain;    /* NULL until the first shown frame */
    int chainW, chainH;
    ID3D11Texture2D     *texture;  /* the latest frame, written through GL */
    int texW, texH;
    BOOL shown;                    /* visual is the target's root */
    BOOL suspended;                /* the window's size is changing */
    BOOL failed;                   /* a DirectComposition / DXGI step failed: give up for good */
};

#define MIRROR_FORMAT DXGI_FORMAT_R8G8B8A8_UNORM

static void releaseChain(NucleusTaoMirror *m) {
    if (m->chain) { m->chain->Release(); m->chain = NULL; }
    m->chainW = m->chainH = 0;
}

static void hide(NucleusTaoMirror *m) {
    if (!m->shown) return;
    m->target->SetRoot(NULL);
    m->dcomp->Commit();
    m->shown = FALSE;
}

/* Copies the texture into the chain (created or resized to match) and presents. */
static BOOL presentTexture(NucleusTaoMirror *m) {
    if (!m->texture) return FALSE;
    if (!m->chain || m->chainW != m->texW || m->chainH != m->texH) {
        if (m->chain) {
            /* The visual still references the chain: resizing it in place is fine
             * while it is not shown, and the content comes back with SetContent. */
            if (FAILED(m->chain->ResizeBuffers(2, (UINT)m->texW, (UINT)m->texH, MIRROR_FORMAT, 0))) {
                releaseChain(m);
            }
        }
        if (!m->chain) {
            IDXGIDevice *dxgi = NULL;
            IDXGIAdapter *adapter = NULL;
            IDXGIFactory2 *factory = NULL;
            BOOL ok = FALSE;
            if (SUCCEEDED(m->device->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgi)) &&
                SUCCEEDED(dxgi->GetAdapter(&adapter)) &&
                SUCCEEDED(adapter->GetParent(__uuidof(IDXGIFactory2), (void **)&factory))) {
                DXGI_SWAP_CHAIN_DESC1 desc = {};
                desc.Width = (UINT)m->texW;
                desc.Height = (UINT)m->texH;
                desc.Format = MIRROR_FORMAT;
                desc.SampleDesc.Count = 1;
                desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
                desc.BufferCount = 2;
                desc.Scaling = DXGI_SCALING_STRETCH; /* the only one composition chains take */
                desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
                desc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
                ok = SUCCEEDED(factory->CreateSwapChainForComposition(m->device, &desc, NULL, &m->chain));
            }
            if (factory) factory->Release();
            if (adapter) adapter->Release();
            if (dxgi) dxgi->Release();
            if (!ok) { m->chain = NULL; m->failed = TRUE; return FALSE; }
            if (FAILED(m->visual->SetContent(m->chain))) { releaseChain(m); m->failed = TRUE; return FALSE; }
        }
        m->chainW = m->texW;
        m->chainH = m->texH;
    }
    ID3D11Texture2D *back = NULL;
    if (FAILED(m->chain->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **)&back))) {
        m->failed = TRUE;
        return FALSE;
    }
    m->imCtx->CopyResource(back, m->texture);
    back->Release();
    /* Interval 0: pacing is the blt swap's, presented just before. */
    m->chain->Present(0, 0);
    return TRUE;
}

static void show(NucleusTaoMirror *m) {
    if (m->shown) return;
    if (!presentTexture(m)) return;
    m->target->SetRoot(m->visual);
    m->dcomp->Commit();
    m->shown = TRUE;
}

/* Client size of the render child — what the texture must match to be shown. */
static BOOL clientSizeMatches(NucleusTaoMirror *m) {
    RECT rc;
    if (!GetClientRect(m->hwnd, &rc)) return FALSE;
    return (rc.right - rc.left) == m->texW && (rc.bottom - rc.top) == m->texH;
}

extern "C" BOOL nucleus_tao_mirror_available(void) {
    return resolveDComp() != NULL;
}

extern "C" NucleusTaoMirror *nucleus_tao_mirror_create(HWND surfaceHwnd, ID3D11Device *device) {
    PFN_DCompositionCreateDevice create = resolveDComp();
    if (!create || !surfaceHwnd || !device) return NULL;
    NucleusTaoMirror *m = (NucleusTaoMirror *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusTaoMirror));
    if (!m) return NULL;
    m->hwnd = surfaceHwnd;
    m->device = device;
    device->AddRef();
    device->GetImmediateContext(&m->imCtx);
    IDXGIDevice *dxgi = NULL;
    BOOL ok = SUCCEEDED(device->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgi)) &&
        SUCCEEDED(create(dxgi, __uuidof(IDCompositionDevice), (void **)&m->dcomp)) &&
        /* topmost = FALSE: over the window's own (blt) content, under its child windows. */
        SUCCEEDED(m->dcomp->CreateTargetForHwnd(surfaceHwnd, FALSE, &m->target)) &&
        SUCCEEDED(m->dcomp->CreateVisual(&m->visual));
    if (dxgi) dxgi->Release();
    if (!ok) {
        nucleus_tao_mirror_destroy(m);
        return NULL;
    }
    /* Born suspended: the frame that creates it may be the first of a resize
     * (a window that started overhanging the desktop with nothing to redraw
     * gets its first frame from the resize), and a visual shown mid-resize
     * races the geometry like any DComp present. Shown by the settle timer,
     * like after any size change. */
    m->suspended = TRUE;
    SetTimer(m->hwnd, NUCLEUS_TAO_MIRROR_TIMER_ID, NUCLEUS_TAO_MIRROR_SETTLE_MS, NULL);
    return m;
}

extern "C" void nucleus_tao_mirror_destroy(NucleusTaoMirror *m) {
    if (!m) return;
    KillTimer(m->hwnd, NUCLEUS_TAO_MIRROR_TIMER_ID);
    if (m->target) { m->target->SetRoot(NULL); }
    if (m->visual) { m->visual->SetContent(NULL); m->visual->Release(); }
    if (m->target) m->target->Release();
    if (m->dcomp) { m->dcomp->Commit(); m->dcomp->Release(); }
    releaseChain(m);
    if (m->texture) m->texture->Release();
    if (m->imCtx) m->imCtx->Release();
    if (m->device) m->device->Release();
    HeapFree(GetProcessHeap(), 0, m);
}

extern "C" ID3D11Texture2D *nucleus_tao_mirror_texture(NucleusTaoMirror *m, int w, int h) {
    if (!m || w < 1 || h < 1) return NULL;
    if (m->texture && m->texW == w && m->texH == h) return m->texture;
    if (m->texture) { m->texture->Release(); m->texture = NULL; }
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = (UINT)w;
    desc.Height = (UINT)h;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = MIRROR_FORMAT;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(m->device->CreateTexture2D(&desc, NULL, &m->texture))) {
        m->texture = NULL;
        return NULL;
    }
    m->texW = w;
    m->texH = h;
    return m->texture;
}

extern "C" void nucleus_tao_mirror_frame(NucleusTaoMirror *m) {
    if (!m || !m->texture || m->failed) return;
    if (m->suspended) return;
    if (!clientSizeMatches(m)) {
        /* A size change nobody announced (one that skipped the deco hook):
         * never show a frame of another size, wait for the size to settle. */
        nucleus_tao_mirror_suspend(m);
        return;
    }
    if (m->shown) {
        presentTexture(m);
    } else {
        show(m);
    }
}

extern "C" BOOL nucleus_tao_mirror_failed(NucleusTaoMirror *m) {
    return m && m->failed;
}

extern "C" void nucleus_tao_mirror_suspend(NucleusTaoMirror *m) {
    if (!m) return;
    if (m->shown) {
        hide(m);
        /* The commit is asynchronous: wait until DWM has taken the visual
         * down, or the geometry change that follows can reach it first and
         * show the old frame in the new bounds for a composition. Once per
         * size burst (the visual is already down for the rest of it). */
        m->dcomp->WaitForCommitCompletion();
        /* Processed is not shown: let one composition without the visual go
         * out before the new geometry can join one. */
        PFN_DwmFlush flush = resolveDwmFlush();
        if (flush) flush();
    }
    m->suspended = TRUE;
    /* (Re)armed by every size change: fires once the size has held still. */
    SetTimer(m->hwnd, NUCLEUS_TAO_MIRROR_TIMER_ID, NUCLEUS_TAO_MIRROR_SETTLE_MS, NULL);
}

extern "C" void nucleus_tao_mirror_timer(NucleusTaoMirror *m) {
    if (!m) return;
    /* The texture always holds the window's latest frame (every frame is
     * copied in, suspended or not), so once it has the render child's size it
     * is what the window shows — whether a frame arrived since the size change
     * or not: a restore to the same size, or a window with nothing to redraw,
     * brings none. */
    if (m->failed) {
        KillTimer(m->hwnd, NUCLEUS_TAO_MIRROR_TIMER_ID);
        return;
    }
    if (!clientSizeMatches(m)) return; /* keep waiting */
    KillTimer(m->hwnd, NUCLEUS_TAO_MIRROR_TIMER_ID);
    m->suspended = FALSE;
    show(m);
}

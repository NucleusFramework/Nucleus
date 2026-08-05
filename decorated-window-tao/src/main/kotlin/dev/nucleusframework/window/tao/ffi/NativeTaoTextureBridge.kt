package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge for external GPU texture import (TextureView) on Windows.
 * The native code lives in `nucleus_tao_texture.c`, compiled into
 * `nucleus_tao_gl.dll` — loading is delegated to [NativeTaoGlBridge]
 * (JNI method resolution searches every library loaded by the class
 * loader, so no second `load` is needed).
 *
 * Import/update/destroy must run on the Tao event-loop thread, like
 * every other entry point touching the process EGL context. The test
 * producer owns its own D3D11 device and is safe from any single
 * producer thread.
 */
@Suppress("TooManyFunctions")
internal object NativeTaoTextureBridge {
    val isLoaded: Boolean get() = NativeTaoGlBridge.isLoaded

    /**
     * Imports a legacy DXGI shared handle (`IDXGIResource::GetSharedHandle`)
     * as a GL ES texture in the host window's ANGLE context: the shared
     * D3D11 resource is opened on ANGLE's own device and bound to a pbuffer
     * (`EGL_D3D_TEXTURE_ANGLE`), whose colour buffer is then bound onto a
     * fresh texture id via `eglBindTexImage`.
     *
     * When the producer texture carries an `IDXGIKeyedMutex`
     * (`D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX`), the pbuffer wraps a private
     * staging texture instead and [nativeUpdateFrame] copies under the mutex
     * — tear-free at the cost of one GPU copy per frame. Without a mutex the
     * shared texture is sampled directly (true zero copy).
     *
     * NT handles (`D3D11_RESOURCE_MISC_SHARED_NTHANDLE`) are not supported
     * by ANGLE's share-handle path. Returns an opaque handle, or `<= 0` on
     * failure — negative values encode the failing stage for diagnostics
     * (`-1` EGL entry points missing, `-2` no host EGL context,
     * `-0x2000X` device/OpenSharedResource failure, `-0x3/4/5xxxx`
     * pbuffer/make-current/bind failure with the EGL error in the low bits).
     */
    @JvmStatic
    external fun nativeImportD3D11SharedHandle(
        hostHwnd: Long,
        sharedHandle: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** GL texture id backing the import — fed to Skia's `BackendTexture.makeGL`. */
    @JvmStatic
    external fun nativeGlTextureId(handle: Long): Int

    /** Whether the import runs the keyed-mutex staging path (tear-free). */
    @JvmStatic
    external fun nativeIsSynchronized(handle: Long): Boolean

    /**
     * Keyed-mutex mode: pulls the latest producer frame into the staging
     * texture (`AcquireSync(0)` → `CopyResource` → `ReleaseSync(0)`).
     * Direct mode: no-op. Returns false when the producer held the mutex
     * past the timeout (frame skipped, previous content stays visible).
     * Event-loop thread only — the copy runs on ANGLE's immediate context.
     */
    @JvmStatic
    external fun nativeUpdateFrame(handle: Long): Boolean

    /**
     * Releases the pbuffer binding. Pass [deleteTexture] = true only when
     * Skia never adopted the texture id (`Image.adoptTextureFrom` transfers
     * ownership — Skia deletes the texture with the Image).
     */
    @JvmStatic
    external fun nativeDestroy(
        handle: Long,
        deleteTexture: Boolean,
    )

    /**
     * Snapshots the EGL binding current on the calling thread, so
     * [nativeRestoreBinding] can put it back after another surface's EGL
     * surface was bound over it. Returns false when a snapshot is already
     * outstanding — the caller must then not rebind, or it would lose the
     * outer binding.
     */
    @JvmStatic
    external fun nativeSaveCurrentBinding(): Boolean

    /**
     * Restores the binding [nativeSaveCurrentBinding] took, or unbinds the
     * thread when nothing was current then — whatever the caller bound in
     * between is its own surface, and leaving it current would hand the next
     * unrelated GL work a foreign draw target. Returns false only when no
     * snapshot was outstanding.
     */
    @JvmStatic
    external fun nativeRestoreBinding(): Boolean

    // ---- D3D11 test producer (demos / smoke tests) -------------------

    /**
     * Creates a standalone D3D11 device (hardware, WARP fallback) plus a
     * shared `R8G8B8A8_UNORM` texture of the given size — with a DXGI keyed
     * mutex when [useKeyedMutex] is set. Returns an opaque producer handle,
     * or 0 when D3D11 is unavailable.
     */
    @JvmStatic
    external fun nativeTestProducerCreate(
        widthPx: Int,
        heightPx: Int,
        useKeyedMutex: Boolean,
    ): Long

    /** Legacy DXGI shared handle of the producer's texture. */
    @JvmStatic
    external fun nativeTestProducerSharedHandle(producer: Long): Long

    /**
     * Clears the producer texture to [argb] (premultiplied on the C side)
     * and flushes; bracketed by `AcquireSync(0)`/`ReleaseSync(0)` when the
     * producer was created with a keyed mutex.
     */
    @JvmStatic
    external fun nativeTestProducerFill(
        producer: Long,
        argb: Int,
    )

    /**
     * Draws an animated test pattern ([argbBg] background + two moving
     * white bars driven by [tick]) — gives contentScale/filterQuality
     * demos some structure and makes tearing observable. Same keyed-mutex
     * bracketing as [nativeTestProducerFill].
     */
    @JvmStatic
    external fun nativeTestProducerDrawPattern(
        producer: Long,
        tick: Int,
        argbBg: Int,
    )

    @JvmStatic
    external fun nativeTestProducerDestroy(producer: Long)
}

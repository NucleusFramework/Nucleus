package dev.nucleusframework.window.tao.ffi

/**
 * JNI bridge for external GPU texture import (TextureView) on Linux.
 * The native code lives in `nucleus_tao_texture_linux.c`, compiled into
 * `libnucleus_tao_egl.so` — loading is delegated to [NativeTaoEglBridge]
 * (JNI method resolution searches every library loaded by the class loader,
 * so no second `load` is needed). Same arrangement as `nucleus_tao_texture.c`
 * inside `nucleus_tao_gl.dll` on Windows.
 *
 * Import and destroy must run with the target surface's EGL context current on
 * the calling thread: they create/delete GL objects owned by the Skia
 * `DirectContext` of that context. On Linux that is the natural state —
 * composition and the draw pass both run inside `ComposeScene.render()`,
 * between the host's `nativeMakeCurrent` and `nativeReleaseCurrent` — and
 * [nativeIsAttachmentCurrent] lets the caller verify it.
 *
 * There is no per-frame native call: the imported texture aliases the
 * producer's DMA-BUF, so a producer's writes are visible to the next Skia draw
 * that samples it (true zero copy, like the Windows `MISC_SHARED` path).
 *
 * The test producer owns a private GBM device + EGL context and is safe from
 * any single producer thread (it binds its context per call).
 */
@Suppress("TooManyFunctions")
internal object NativeTaoLinuxTextureBridge {
    val isLoaded: Boolean get() = NativeTaoEglBridge.isLoaded

    /**
     * Imports one plane of a DMA-BUF as a `GL_TEXTURE_2D` in the EGL context
     * current on this thread: `eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT)` on the
     * current `EGLDisplay`, then `glEGLImageTargetTexture2DOES`. The texture
     * aliases the producer's memory — no copy, and no per-frame work.
     *
     * [fd] is only read here; EGL takes its own reference to the buffer, so the
     * caller stays its owner and may close it right after. [fourcc] must be a
     * single-plane 32-bit RGB DRM format (`AR24`, `XR24`, `AB24`, `XB24`, …) —
     * the driver interprets it, so GL sampling always yields RGBA whatever the
     * memory order. [modifier] is a DRM format modifier, or
     * `DRM_FORMAT_MOD_INVALID` (`0x00FFFFFFFFFFFFFF`) to let the driver assume
     * an implicit layout.
     *
     * Returns an opaque handle, or `<= 0` on failure: `-1` bad arguments or
     * unsupported FourCC, `-2` no EGL context current, `-3` driver lacks
     * `EGL_EXT_image_dma_buf_import`, `-4` explicit modifier without
     * `EGL_EXT_image_dma_buf_import_modifiers`, `-5` entry points missing,
     * `-0x6xxxx` `eglCreateImageKHR` failed, `-0x7xxxx`
     * `glEGLImageTargetTexture2DOES` failed (low 16 bits = the EGL/GL error).
     */
    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeImportDmaBuf(
        fd: Int,
        fourcc: Int,
        widthPx: Int,
        heightPx: Int,
        stride: Int,
        offset: Int,
        modifier: Long,
    ): Long

    /**
     * Imports a producer-owned `EGLImageKHR` created on the same `EGLDisplay`
     * as the current context. The producer keeps ownership of the image (it is
     * not destroyed by [nativeDestroy]); only the GL texture belongs to the
     * import. Same return contract as [nativeImportDmaBuf], minus the DMA-BUF
     * specific stages.
     */
    @JvmStatic
    external fun nativeImportEglImage(
        eglImage: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** GL texture id backing the import — fed to Skia's `BackendTexture.makeGL`. */
    @JvmStatic
    external fun nativeGlTextureId(handle: Long): Int

    /**
     * Releases the import (and the EGLImage when it created one). Pass
     * [deleteTexture] = true only when Skia never adopted the texture id
     * (`Image.adoptTextureFrom` transfers ownership — Skia deletes the texture
     * with the Image). The importing EGL context must be current whenever a GL
     * delete is requested.
     */
    @JvmStatic
    external fun nativeDestroy(
        handle: Long,
        deleteTexture: Boolean,
    )

    /**
     * Whether the EGL context of [attachment] (a `NativeTaoEglBridge` handle)
     * is current on the calling thread — the precondition of every import and
     * of Skia's GL deletes.
     */
    @JvmStatic
    external fun nativeIsAttachmentCurrent(attachment: Long): Boolean

    /** Whether the currently bound EGL display advertises DMA-BUF import. */
    @JvmStatic
    external fun nativeIsDmaBufImportSupported(): Boolean

    /**
     * Snapshots the EGL binding current on this thread, so [nativeRestoreBinding]
     * can put it back after another surface's context was bound over it. Returns
     * false when a snapshot is already outstanding on this thread — the caller
     * must then not rebind, or it would lose the outer binding.
     */
    @JvmStatic
    external fun nativeSaveCurrentBinding(): Boolean

    /**
     * Restores the binding [nativeSaveCurrentBinding] took. Returns false when
     * nothing was current then; the caller unbinds via
     * [NativeTaoEglBridge.nativeReleaseCurrent] in that case (`eglMakeCurrent`
     * needs a display even to unbind).
     */
    @JvmStatic
    external fun nativeRestoreBinding(): Boolean

    // ---- GBM/EGL test producer (demos / smoke tests) ------------------

    /**
     * Creates a private GBM device (first usable `/dev/dri/renderD*`), a
     * scanout-free render buffer of the given size and [fourcc], plus its own
     * EGL display + surfaceless context with the buffer bound as an FBO colour
     * attachment. Returns an opaque producer handle, or 0 when GBM/EGL or a
     * render node is unavailable.
     */
    @JvmStatic
    external fun nativeTestProducerCreate(
        widthPx: Int,
        heightPx: Int,
        fourcc: Int,
    ): Long

    /** DMA-BUF fd of the producer's buffer — borrowed, valid until destroy. */
    @JvmStatic
    external fun nativeTestProducerFd(producer: Long): Int

    /** Row pitch of the producer's buffer, in bytes. */
    @JvmStatic
    external fun nativeTestProducerStride(producer: Long): Int

    /** DRM format modifier the driver picked for the producer's buffer. */
    @JvmStatic
    external fun nativeTestProducerModifier(producer: Long): Long

    /**
     * Clears the producer buffer to [argb] (premultiplied on the native side)
     * and waits for the GPU (`glFinish`), so the frame is fully written before
     * the caller signals it.
     */
    @JvmStatic
    external fun nativeTestProducerFill(
        producer: Long,
        argb: Int,
    )

    /**
     * Draws an animated test pattern ([argbBg] background + two moving white
     * bars driven by [tick]) with scissored clears — the same shape the Windows
     * and macOS producers draw. Same finish-before-return contract as
     * [nativeTestProducerFill].
     */
    @JvmStatic
    external fun nativeTestProducerDrawPattern(
        producer: Long,
        tick: Int,
        argbBg: Int,
    )

    @JvmStatic
    external fun nativeTestProducerDestroy(producer: Long)

    // ---- Headless consumer context (smoke tests) ----------------------

    /**
     * Creates a GBM-backed EGL context and makes it current on the calling
     * thread — a stand-in for a window attachment, so the import chain can be
     * exercised with no window and no display server. Returns 0 when nothing
     * usable is available.
     */
    @JvmStatic
    external fun nativeTestContextCreate(): Long

    @JvmStatic
    external fun nativeTestContextDestroy(handle: Long)
}

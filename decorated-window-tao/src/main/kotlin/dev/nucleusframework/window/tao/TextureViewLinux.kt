package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin
import java.util.logging.Logger

/** `GL_TEXTURE_2D` / `GR_GL_RGBA8` — Skia's GL backend constants. */
private const val GL_TEXTURE_2D = 0x0DE1
private const val GR_GL_RGBA8 = 0x8058

private val logger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.texture")

/** Radix for the staged import-failure codes, which read as `stage | driver error`. */
private const val HEX = 16

/**
 * Linux implementation of [TextureView]: the producer's DMA-BUF is wrapped as an
 * `EGLImage` on the window's own `EGLDisplay`, bound onto a `GL_TEXTURE_2D` in
 * its EGL context and adopted by Skia, which samples it while compositing the
 * Compose scene. See [nucleusDmaBufTextureSource] for the synchronization
 * contract.
 *
 * The import aliases the producer's memory, so — unlike macOS (one GPU copy per
 * frame) and the Windows keyed-mutex path — there is no per-frame work at all:
 * reading [TextureViewController.frameStamp] here is what makes the frame
 * signal invalidate this draw pass, and the very next draw samples the
 * producer's newest pixels.
 */
@Composable
internal fun LinuxTextureView(
    source: TextureViewSource,
    modifier: Modifier,
    controller: TextureViewController?,
    filterQuality: FilterQuality,
    contentScale: ContentScale,
    alignment: Alignment,
) {
    val host = LocalTaoGlTextureHost.current
    if (Platform.Current != Platform.Linux || host == null || !NativeTaoLinuxTextureBridge.isLoaded) {
        Box(modifier)
        return
    }

    // RememberObserver lease, like the Windows and macOS paths: the registry ref
    // is released on onForgotten AND onAbandoned, so a composition that computes
    // this remember block but is never applied cannot leak the native import (a
    // DisposableEffect would never run in that case).
    val imported =
        remember(source, host) {
            GlTextureImportLease(host, source)
        }.imported
    if (imported == null) {
        Box(modifier)
        return
    }

    val srcRect =
        remember(imported) {
            Rect(0f, 0f, imported.widthPx.toFloat(), imported.heightPx.toFloat())
        }
    val sampling = remember(filterQuality) { samplingFor(filterQuality) }
    Box(
        modifier.drawBehind {
            // Snapshot read of the frame stamp: markFrameAvailable() invalidates
            // exactly this draw pass, nothing recomposes. The read is the whole
            // per-frame cost — the texture is the producer's buffer, so no copy
            // or native call is needed to see the new content.
            controller?.frameStamp?.longValue
            drawExternalTexture(imported.image, srcRect, contentScale, alignment, sampling)
        },
    )
}

/**
 * Composition-lifetime holder of one registry reference — the Linux twin of the
 * Windows `TextureImportLease`. Releasing on both `onForgotten` and
 * `onAbandoned` keeps an abandoned composition from leaking the import.
 */
private class GlTextureImportLease(
    host: TaoGlTextureHost,
    source: TextureViewSource,
) : RememberObserver {
    val imported: LinuxImportedTexture? = GlTextureImportRegistry.acquire(host, source)

    private fun release() {
        imported?.let(GlTextureImportRegistry::release)
    }

    override fun onRemembered() {
        // The reference was already taken in the constructor.
    }

    override fun onForgotten() {
        release()
    }

    override fun onAbandoned() {
        release()
    }
}

/**
 * Pairs the native EGLImage binding with the Skia image that adopted the GL
 * texture. Skia owns the texture id after adoption (deleted with the image); the
 * native side only tears down the EGLImage.
 */
private class LinuxImportedTexture(
    private val handle: Long,
    private val host: TaoGlTextureHost,
    val image: Image,
    val widthPx: Int,
    val heightPx: Int,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        // Skia deletes the adopted GL texture from inside image.close(), so it
        // has to see the EGL context that owns it. Disposal reaches us from
        // Compose (inside a render pass, context already current) but also from
        // surface teardown, where nothing is bound — hence the explicit bind.
        //
        // A null result means the context could not be bound. Skipping the Skia
        // free is then the safe choice — a GL delete with no current context
        // crashes inside the driver — and it is not a leak either: the only way
        // to get here is a surface that already dropped its attachment, whose
        // `DirectContext` was closed with it, and closing a Skia context
        // abandons its GPU resources so the image's eventual unref issues no GL.
        // Worth a line in the log all the same: it means teardown ran in an
        // order this class does not expect.
        if (host.withContextCurrent { image.close() } == null) {
            logger.fine { "TextureView: EGL context gone at teardown, Skia image freed with its context" }
        }
        // eglDestroyImageKHR needs no current context.
        NativeTaoLinuxTextureBridge.nativeDestroy(handle, deleteTexture = false)
    }
}

/**
 * Shares GPU imports between [TextureView]s: N composables showing the same
 * source in the same surface use one EGLImage / GL texture / Skia image — the
 * moral equivalent of Flutter's texture registry. Keyed by the Skia context too,
 * since each Linux surface (window scene, popup window, tray panel) owns its
 * own. Event-loop thread only.
 */
private object GlTextureImportRegistry {
    private data class Key(
        val context: DirectContext,
        val source: TextureViewSource,
    )

    private class Entry(
        val imported: LinuxImportedTexture,
    ) {
        var refCount: Int = 1
    }

    private val entries = HashMap<Key, Entry>()
    private val keys = HashMap<LinuxImportedTexture, Key>()

    fun acquire(
        host: TaoGlTextureHost,
        source: TextureViewSource,
    ): LinuxImportedTexture? {
        val key = Key(host.directContext, source)
        entries[key]?.let { entry ->
            entry.refCount++
            return entry.imported
        }
        val imported = importTexture(host, source) ?: return null
        entries[key] = Entry(imported)
        keys[imported] = key
        return imported
    }

    fun release(imported: LinuxImportedTexture) {
        val key = keys[imported] ?: return
        val entry = entries[key] ?: return
        entry.refCount--
        if (entry.refCount <= 0) {
            entries.remove(key)
            keys.remove(imported)
            imported.close()
        }
    }

    fun closeAllFor(context: DirectContext) {
        val stale = entries.keys.filter { it.context == context }
        for (key in stale) {
            val entry = entries.remove(key) ?: continue
            keys.remove(entry.imported)
            entry.imported.close()
        }
    }
}

/**
 * Drops every import made on [context] — called by a surface right before it
 * closes its `DirectContext` (window hide on Wayland rebuilds the whole EGL
 * attachment, popup/panel teardown destroys it for good). Without this the
 * imports would outlive their context and their Skia images would be freed
 * against a dead one. Leases releasing later find the import already closed.
 */
internal fun releaseGlTextureImports(context: DirectContext) {
    GlTextureImportRegistry.closeAllFor(context)
}

private fun importTexture(
    host: TaoGlTextureHost,
    source: TextureViewSource,
): LinuxImportedTexture? {
    val widthPx: Int
    val heightPx: Int
    when (source) {
        is DmaBufTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        is EglImageTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        else -> return null
    }
    if (widthPx < 1 || heightPx < 1) return null

    // Both the GL texture and the Skia image belong to this surface's EGL
    // context, so the whole import runs with it current.
    return host.withContextCurrent {
        val handle =
            when (source) {
                is DmaBufTextureSource ->
                    NativeTaoLinuxTextureBridge.nativeImportDmaBuf(
                        source.fd,
                        source.fourcc,
                        widthPx,
                        heightPx,
                        source.stride,
                        source.offset,
                        source.modifier,
                    )
                is EglImageTextureSource ->
                    NativeTaoLinuxTextureBridge.nativeImportEglImage(
                        source.eglImage,
                        widthPx,
                        heightPx,
                    )
            }
        if (handle <= 0L) {
            // The import can fail for reasons the caller cannot see from Kotlin
            // (driver without EGL_EXT_image_dma_buf_import, a modifier the GPU
            // can't read, a FourCC/stride that doesn't describe the buffer), and
            // the composable then just renders an empty Box. Say why once.
            logger.warning { "TextureView: external texture import failed (stage 0x${(-handle).toString(HEX)})" }
            return@withContextCurrent null
        }
        val texId = NativeTaoLinuxTextureBridge.nativeGlTextureId(handle)
        // RGBA8 whatever the buffer's FourCC: the driver interprets the DRM
        // format when creating the EGLImage, so sampling the texture already
        // yields (R, G, B, A). X-variants (no alpha channel) sample as opaque.
        val image =
            runCatching {
                Image.adoptTextureFrom(
                    host.directContext,
                    BackendTexture.makeGL(
                        widthPx,
                        heightPx,
                        false,
                        texId,
                        GL_TEXTURE_2D,
                        GR_GL_RGBA8,
                    ),
                    SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888,
                )
            }.getOrNull()
        if (image == null) {
            // Skia never adopted the texture — the native side must delete it.
            NativeTaoLinuxTextureBridge.nativeDestroy(handle, deleteTexture = true)
            return@withContextCurrent null
        }
        LinuxImportedTexture(handle, host, image, widthPx, heightPx)
    }
}

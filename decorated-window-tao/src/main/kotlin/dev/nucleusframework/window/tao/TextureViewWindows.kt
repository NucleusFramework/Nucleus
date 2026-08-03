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
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge
import dev.nucleusframework.window.tao.popup.LocalTaoPopupHostWindows
import dev.nucleusframework.window.tao.popup.TaoPopupHostWindows
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin

/** `GL_TEXTURE_2D` / `GR_GL_RGBA8` — Skia's GL backend constants. */
private const val GL_TEXTURE_2D = 0x0DE1
private const val GR_GL_RGBA8 = 0x8058

/**
 * Windows implementation of [TextureView]: the producer's D3D11 texture is
 * imported as a GL ES texture in the window's ANGLE context and adopted by
 * Skia, which samples it while compositing the Compose scene. See
 * [nucleusD3D11SharedTextureSource] for the synchronization modes.
 */
@Composable
internal fun WindowsTextureView(
    source: D3D11SharedTextureSource,
    modifier: Modifier,
    controller: TextureViewController?,
    filterQuality: FilterQuality,
    contentScale: ContentScale,
    alignment: Alignment,
) {
    val popupHost = LocalTaoPopupHostWindows.current
    if (Platform.Current != Platform.Windows || popupHost == null || !NativeTaoTextureBridge.isLoaded) {
        Box(modifier)
        return
    }

    // The lease is a RememberObserver: the registry ref is released on
    // onForgotten AND onAbandoned, so a composition that computes this
    // remember block but is never applied can't leak the native import
    // (a DisposableEffect would never run in that case).
    val imported =
        remember(source, popupHost) {
            TextureImportLease(popupHost, source)
        }.imported
    if (imported == null) {
        Box(modifier)
        return
    }

    val srcRect =
        remember(source) {
            Rect(0f, 0f, source.widthPx.toFloat(), source.heightPx.toFloat())
        }
    val sampling = remember(filterQuality) { samplingFor(filterQuality) }
    Box(
        modifier.drawBehind {
            // Snapshot read of the frame stamp: markFrameAvailable()
            // invalidates exactly this draw pass, nothing recomposes.
            val stamp = controller?.frameStamp?.longValue ?: 0L
            if (imported.isSynchronized && imported.needsCopy(controller, stamp)) {
                // Draw runs on the event-loop thread during the scene
                // render: the staging copy is enqueued on ANGLE's device
                // queue ahead of Skia's sampling flush, so this frame
                // already composites the copied content. The stamp is
                // only consumed when the copy happened — a false return
                // means the producer held the mutex past the timeout,
                // and the next redraw must retry or the last frame
                // would stay stale forever.
                if (NativeTaoTextureBridge.nativeUpdateFrame(imported.handle)) {
                    imported.markCopied(controller, stamp)
                }
            }
            drawExternalTexture(imported.image, srcRect, contentScale, alignment, sampling)
        },
    )
}

/**
 * Composition-lifetime holder of one registry reference. Implements
 * [RememberObserver] so the reference is released both when the
 * composable leaves the composition (onForgotten) and when the
 * composition is abandoned before being applied (onAbandoned) — the
 * case a DisposableEffect can't cover.
 */
private class TextureImportLease(
    popupHost: TaoPopupHostWindows,
    source: D3D11SharedTextureSource,
) : RememberObserver {
    val imported: ImportedExternalTexture? = TextureImportRegistry.acquire(popupHost, source)

    private fun release() {
        imported?.let(TextureImportRegistry::release)
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
 * Pairs the native pbuffer binding with the Skia image that adopted the
 * GL texture. Skia owns the texture id after adoption (deleted with the
 * image); the native side only tears down the pbuffer.
 */
private class ImportedExternalTexture(
    val handle: Long,
    val image: Image,
) {
    /** Keyed-mutex staging mode — tear-free copies via [NativeTaoTextureBridge.nativeUpdateFrame]. */
    val isSynchronized: Boolean = NativeTaoTextureBridge.nativeIsSynchronized(handle)

    /**
     * Newest stamp already staged, per controller feeding this import (keys
     * compare by identity). One import is shared by every view on the same
     * source, but each view reads *its own* controller's stamp — a single slot
     * would make views with different controllers (or one without) re-copy on
     * every draw pass. Main thread only.
     */
    private val copied = HashMap<TextureViewController?, Long>()

    fun needsCopy(
        controller: TextureViewController?,
        stamp: Long,
    ): Boolean = copied[controller] != stamp

    fun markCopied(
        controller: TextureViewController?,
        stamp: Long,
    ) {
        copied[controller] = stamp
    }

    fun close() {
        image.close()
        NativeTaoTextureBridge.nativeDestroy(handle, deleteTexture = false)
    }
}

/**
 * Shares GPU imports between [TextureView]s: N composables showing the
 * same source in the same window use one pbuffer/GL texture/Skia image
 * (and, in keyed-mutex mode, one staging copy per frame) — the moral
 * equivalent of Flutter's texture registry. Main thread only.
 */
private object TextureImportRegistry {
    private data class Key(
        val hwnd: Long,
        val sharedHandle: Long,
        val widthPx: Int,
        val heightPx: Int,
    )

    private class Entry(
        val imported: ImportedExternalTexture,
    ) {
        var refCount: Int = 1
    }

    private val entries = HashMap<Key, Entry>()
    private val keys = HashMap<ImportedExternalTexture, Key>()

    fun acquire(
        popupHost: TaoPopupHostWindows,
        source: D3D11SharedTextureSource,
    ): ImportedExternalTexture? {
        val key = Key(popupHost.parentHwnd, source.sharedHandle, source.widthPx, source.heightPx)
        entries[key]?.let { entry ->
            entry.refCount++
            return entry.imported
        }
        val imported = importTexture(popupHost, source) ?: return null
        entries[key] = Entry(imported)
        keys[imported] = key
        return imported
    }

    fun release(imported: ImportedExternalTexture) {
        val key = keys[imported] ?: return
        val entry = entries[key] ?: return
        entry.refCount--
        if (entry.refCount <= 0) {
            entries.remove(key)
            keys.remove(imported)
            imported.close()
        }
    }
}

private fun importTexture(
    popupHost: TaoPopupHostWindows,
    source: D3D11SharedTextureSource,
): ImportedExternalTexture? {
    val handle =
        NativeTaoTextureBridge.nativeImportD3D11SharedHandle(
            popupHost.parentHwnd,
            source.sharedHandle,
            source.widthPx,
            source.heightPx,
        )
    if (handle <= 0L) return null
    val texId = NativeTaoTextureBridge.nativeGlTextureId(handle)
    val image =
        runCatching {
            Image.adoptTextureFrom(
                popupHost.hostDirectContext,
                BackendTexture.makeGL(
                    source.widthPx,
                    source.heightPx,
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
        NativeTaoTextureBridge.nativeDestroy(handle, deleteTexture = true)
        return null
    }
    return ImportedExternalTexture(handle, image)
}

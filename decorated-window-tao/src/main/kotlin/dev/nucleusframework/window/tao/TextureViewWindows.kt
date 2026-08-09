package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge
import dev.nucleusframework.window.tao.scene.LocalTaoWindowsTextureHost
import dev.nucleusframework.window.tao.scene.TaoWindowsTextureHost
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
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
    val host = LocalTaoWindowsTextureHost.current
    if (Platform.Current != Platform.Windows || host == null || !NativeTaoTextureBridge.isLoaded) {
        Box(modifier)
        return
    }

    val imported =
        remember(source, host) {
            TextureImportLease(windowsTextureImports, host, source)
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
                } else {
                    // Nothing else will invalidate: `markFrameAvailable` for this
                    // stamp already fired, and the producer may now go idle. Ask
                    // for another frame so the retry actually happens, otherwise a
                    // single contended copy freezes the view on the previous frame
                    // (or, right after the import, on an uninitialized texture).
                    host.requestRedraw()
                }
            }
            drawExternalTexture(imported.image, srcRect, contentScale, alignment, sampling)
        },
    )
}

/**
 * Pairs the native pbuffer binding with the Skia image that adopted the
 * GL texture. Skia owns the texture id after adoption (deleted with the
 * image); the native side only tears down the pbuffer.
 */
private class ImportedExternalTexture(
    val handle: Long,
    val image: Image,
    private val host: TaoWindowsTextureHost,
) {
    /** Keyed-mutex staging mode — tear-free copies via [NativeTaoTextureBridge.nativeUpdateFrame]. */
    val isSynchronized: Boolean = NativeTaoTextureBridge.nativeIsSynchronized(handle)

    /** One staging copy per producer frame per controller — see [FrameStampGate]. */
    private val copied = FrameStampGate()

    fun needsCopy(
        controller: TextureViewController?,
        stamp: Long,
    ): Boolean = copied.isPending(controller, stamp)

    fun markCopied(
        controller: TextureViewController?,
        stamp: Long,
    ) {
        copied.markConsumed(controller, stamp)
    }

    fun close() {
        image.close()
        NativeTaoTextureBridge.nativeDestroy(handle, deleteTexture = false)
        // The destroy released the pbuffer binding on this surface's EGL
        // context; teardown runs inside a host frame too, so resync Skia's
        // state cache before that frame flushes.
        host.markGlStateDirtied()
    }
}

/**
 * The Windows import ledger. Refcounted and keyed by Skia context + source; see
 * [TextureImportRegistry] for why, and for the scaffolding the three backends
 * share.
 */
private val windowsTextureImports =
    TextureImportRegistry<TaoWindowsTextureHost, ImportedExternalTexture>(
        contextOf = { it.directContext },
        importTexture = { host, source -> (source as? D3D11SharedTextureSource)?.let { importTexture(host, it) } },
        closeImport = { it.close() },
    )

/**
 * Drops every import made on [context] — called by a surface right before it
 * closes its `DirectContext` (tray-panel teardown). Without this the imports
 * would outlive their context and their Skia images would be freed against a
 * dead one. Leases releasing later find the import already closed.
 */
internal fun releaseWindowsTextureImports(context: DirectContext) {
    windowsTextureImports.closeAllFor(context)
}

/**
 * Whether [context] currently composites at least one `TextureView`. The
 * Windows host keeps VSync on through the OS modal resize/move loop in that
 * case — see `TaoComposeSceneHostWindows.onResizeLoopChanged` (#484).
 */
internal fun hasWindowsTextureImports(context: DirectContext): Boolean = windowsTextureImports.hasImportsFor(context)

private fun importTexture(
    host: TaoWindowsTextureHost,
    source: D3D11SharedTextureSource,
): ImportedExternalTexture? {
    val handle =
        NativeTaoTextureBridge.nativeImportD3D11SharedHandle(
            host.hostHwnd,
            source.sharedHandle,
            source.widthPx,
            source.heightPx,
        )
    if (handle <= 0L) return null
    val texId = NativeTaoTextureBridge.nativeGlTextureId(handle)
    val image =
        runCatching {
            Image.adoptTextureFrom(
                host.directContext,
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
        host.markGlStateDirtied()
        return null
    }
    host.markGlStateDirtied()
    return ImportedExternalTexture(handle, image, host)
}

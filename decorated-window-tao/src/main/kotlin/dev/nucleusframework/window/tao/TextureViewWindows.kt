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

    // The lease is a RememberObserver: the registry ref is released on
    // onForgotten AND onAbandoned, so a composition that computes this
    // remember block but is never applied can't leak the native import
    // (a DisposableEffect would never run in that case).
    val imported =
        remember(source, host) {
            TextureImportLease(host, source)
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
 * Composition-lifetime holder of one registry reference. Implements
 * [RememberObserver] so the reference is released both when the
 * composable leaves the composition (onForgotten) and when the
 * composition is abandoned before being applied (onAbandoned) — the
 * case a DisposableEffect can't cover.
 */
private class TextureImportLease(
    host: TaoWindowsTextureHost,
    source: D3D11SharedTextureSource,
) : RememberObserver {
    val imported: ImportedExternalTexture? = TextureImportRegistry.acquire(host, source)

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
    private val host: TaoWindowsTextureHost,
) {
    /** Keyed-mutex staging mode — tear-free copies via [NativeTaoTextureBridge.nativeUpdateFrame]. */
    val isSynchronized: Boolean = NativeTaoTextureBridge.nativeIsSynchronized(handle)

    /**
     * Newest stamp already staged, per controller feeding this import (keys
     * compare by identity — [TextureViewController] has no `equals`). One import
     * is shared by every view on the same source, but each view reads *its own*
     * controller's stamp — a single slot would make views with different
     * controllers (or one without) re-copy on every draw pass. Main thread only.
     *
     * Weak keys: the import outlives any single view (it is refcounted across
     * all of them), so a strongly-keyed map would pin the controller of every
     * view that ever drew through this import. A screen that creates one
     * controller per item over a shared source would grow it without bound.
     * Losing an entry early only costs one redundant copy.
     */
    private val copied = java.util.WeakHashMap<TextureViewController?, Long>()

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
        // The destroy released the pbuffer binding on this surface's EGL
        // context; teardown runs inside a host frame too, so resync Skia's
        // state cache before that frame flushes.
        host.markGlStateDirtied()
    }
}

/**
 * Shares GPU imports between [TextureView]s: N composables showing the
 * same source in the same surface use one pbuffer/GL texture/Skia image
 * (and, in keyed-mutex mode, one staging copy per frame) — the moral
 * equivalent of Flutter's texture registry.
 *
 * Keyed by the Skia context, not the host HWND: every Windows surface shares one
 * ANGLE `EGLContext` but *not* one `DirectContext` — a standalone tray panel
 * builds its own and has no HWND of its own to key on, so an hwnd-keyed registry
 * would hand it a window's image and Skia would silently drop the draw.
 * Main thread only.
 */
private object TextureImportRegistry {
    private data class Key(
        val context: DirectContext,
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
        host: TaoWindowsTextureHost,
        source: D3D11SharedTextureSource,
    ): ImportedExternalTexture? {
        val key = Key(host.directContext, source.sharedHandle, source.widthPx, source.heightPx)
        entries[key]?.let { entry ->
            entry.refCount++
            return entry.imported
        }
        val imported = importTexture(host, source) ?: return null
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
 * closes its `DirectContext` (tray-panel teardown). Without this the imports
 * would outlive their context and their Skia images would be freed against a
 * dead one. Leases releasing later find the import already closed.
 */
internal fun releaseWindowsTextureImports(context: DirectContext) {
    TextureImportRegistry.closeAllFor(context)
}

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

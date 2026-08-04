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
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsTextureBridge
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * macOS implementation of [TextureView]. The producer's `IOSurface` (or
 * `id<MTLTexture>`) is mapped as an `id<MTLTexture>` on the window's own Metal
 * device and wrapped in a Skia [Surface]; each producer frame is then pulled
 * into an immutable GPU [Image] with `makeImageSnapshot()` and composited into
 * the Compose scene.
 *
 * The snapshot is why macOS needs one GPU-GPU copy per frame: skiko exposes
 * `BackendRenderTarget.makeMetal` but no Metal `BackendTexture`, so Skia can
 * only *wrap* the import as a render target, and an image of a wrapped render
 * target is always a copy. The copy is recorded on the window's Skia context
 * and executed inside the same flush as the draw that samples it, so the
 * composited frame is always the one the producer had published when the frame
 * was drawn — the equivalent of the Windows keyed-mutex staging path.
 *
 * Threading follows the macOS record/replay split: the composable's draw pass
 * runs on the main thread and hops to the render thread that owns the Skia
 * `DirectContext` (idle at that point, see [TaoMetalTextureHost]) for the
 * snapshot. The hop happens once per new producer frame per import, no matter
 * how many [TextureView]s share it.
 */
@Composable
internal fun MacTextureView(
    source: TextureViewSource,
    modifier: Modifier,
    controller: TextureViewController?,
    filterQuality: FilterQuality,
    contentScale: ContentScale,
    alignment: Alignment,
) {
    val host = LocalTaoMetalTextureHost.current
    if (Platform.Current != Platform.MacOS || host == null || !NativeTaoMacOsTextureBridge.isLoaded) {
        Box(modifier)
        return
    }

    // RememberObserver lease, like the Windows path: the registry ref is
    // released on onForgotten AND onAbandoned, so a composition that computes
    // this remember block but is never applied cannot leak the native import
    // (a DisposableEffect would never run in that case).
    val imported =
        remember(source, host) {
            MetalTextureImportLease(host, source)
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
            // Snapshot read of the frame stamp: markFrameAvailable()
            // invalidates exactly this draw pass, nothing recomposes.
            val stamp = controller?.frameStamp?.longValue ?: 0L
            val image = imported.snapshot(controller, stamp) ?: return@drawBehind
            drawExternalTexture(image, srcRect, contentScale, alignment, sampling)
        },
    )
}

/**
 * Composition-lifetime holder of one registry reference — the macOS twin of
 * the Windows `TextureImportLease`. Releasing on both `onForgotten` and
 * `onAbandoned` keeps an abandoned composition from leaking the import.
 */
private class MetalTextureImportLease(
    host: TaoMetalTextureHost,
    source: TextureViewSource,
) : RememberObserver {
    val imported: MacImportedTexture? = MetalTextureImportRegistry.acquire(host, source)

    private fun release() {
        imported?.let(MetalTextureImportRegistry::release)
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
 * One imported external texture: the native `id<MTLTexture>` mapping plus the
 * Skia objects wrapping it, and the current frame's snapshot. Everything Skia
 * touches lives on [host]'s render thread; the fields are only read/written
 * from the main thread (draw pass, composition, disposal), which the blocking
 * [TaoMetalTextureHost.runOnRenderThread] hops keep ordered.
 */
private class MacImportedTexture(
    val handle: Long,
    val host: TaoMetalTextureHost,
    private val renderTarget: BackendRenderTarget,
    private val surface: Surface,
    val widthPx: Int,
    val heightPx: Int,
) {
    private var image: Image? = null

    /**
     * Newest stamp already pulled, per controller feeding this import (keys
     * compare by identity — [TextureViewController] has no `equals`). One import
     * is shared by every view on the same source, but each view reads *its own*
     * controller's stamp, so a single "last stamp" slot would either re-snapshot
     * once per view or let views with different controllers invalidate each
     * other on every draw pass. Main thread only; at most one entry per
     * controller (`null` = views without one, which need a single pull).
     *
     * Weak keys: the import outlives any single view (it is refcounted across
     * all of them), so a strongly-keyed map would pin the controller of every
     * view that ever drew through this import. A screen that creates one
     * controller per item over a shared source would grow it without bound.
     * Losing an entry early only costs one redundant snapshot.
     */
    private val consumed = java.util.WeakHashMap<TextureViewController?, Long>()

    /**
     * Current GPU snapshot of the producer surface, re-pulled once per signalled
     * frame however many views share this import. The previous image is closed
     * only once the new one exists — pictures recorded from earlier frames keep
     * their own Skia reference, so an in-flight replay is unaffected, and a
     * failed snapshot leaves the last good frame on screen instead of blanking
     * the view.
     */
    fun snapshot(
        controller: TextureViewController?,
        stamp: Long,
    ): Image? {
        val current = image
        if (current != null && consumed[controller] == stamp) return current
        // Recorded even when the snapshot below fails: a broken GPU state must
        // not turn every subsequent draw pass into a blocking render-thread hop.
        // The next producer frame re-arms the retry.
        consumed[controller] = stamp
        val fresh =
            host.runOnRenderThread {
                runCatching {
                    // The producer writes the wrapped texture behind Skia's
                    // back, so Skia still believes the surface is unchanged and
                    // would hand back its cached snapshot (the first frame,
                    // frozen forever). This is the API for exactly that case:
                    // it drops the cached image and bumps the generation id.
                    // RETAIN — the producer's pixels must survive, we only
                    // invalidate Skia's bookkeeping.
                    surface.notifyContentWillChange(ContentChangeMode.RETAIN)
                    surface.makeImageSnapshot()
                }.getOrNull()?.also { current?.close() }
            }
        if (fresh != null) image = fresh
        return image
    }

    fun close() {
        // Skia teardown must happen on the context's thread, before the native
        // texture goes. The hop only fails once the render thread is gone — i.e.
        // after the host closed its DirectContext, which already freed these
        // objects — so swallowing that is safe, but the native import (and the
        // IOSurface reference it holds) must be released either way.
        runCatching {
            host.runOnRenderThread {
                image?.close()
                surface.close()
                renderTarget.close()
            }
        }
        image = null
        consumed.clear()
        NativeTaoMacOsTextureBridge.nativeDestroy(handle)
    }
}

/**
 * Shares GPU imports between [TextureView]s: N composables showing the same
 * source in the same surface use one `MTLTexture` mapping, one Skia surface and
 * one snapshot per frame — the moral equivalent of Flutter's texture registry.
 * Keyed by the Skia context too, since each macOS surface (window, popup panel,
 * `NativeView` overlay) owns its own. Main thread only.
 */
private object MetalTextureImportRegistry {
    private data class Key(
        val context: DirectContext,
        val source: TextureViewSource,
    )

    private class Entry(
        val imported: MacImportedTexture,
    ) {
        var refCount: Int = 1
    }

    private val entries = HashMap<Key, Entry>()
    private val keys = HashMap<MacImportedTexture, Key>()

    fun acquire(
        host: TaoMetalTextureHost,
        source: TextureViewSource,
    ): MacImportedTexture? {
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

    fun release(imported: MacImportedTexture) {
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
    host: TaoMetalTextureHost,
    source: TextureViewSource,
): MacImportedTexture? {
    val widthPx: Int
    val heightPx: Int
    when (source) {
        is IOSurfaceTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        is MetalTextureSource -> {
            widthPx = source.widthPx
            heightPx = source.heightPx
        }
        else -> return null
    }
    if (widthPx < 1 || heightPx < 1 || host.metalDevicePtr == 0L) return null

    // The whole import runs on the render thread: the texture must be created
    // on the device Skia renders with, and the Skia wrappers are context-bound.
    return host.runOnRenderThread {
        val handle =
            when (source) {
                is IOSurfaceTextureSource ->
                    NativeTaoMacOsTextureBridge.nativeImportIOSurface(
                        host.metalDevicePtr,
                        source.ioSurface,
                        widthPx,
                        heightPx,
                    )
                is MetalTextureSource ->
                    NativeTaoMacOsTextureBridge.nativeImportMetalTexture(
                        host.metalDevicePtr,
                        source.metalTexture,
                        widthPx,
                        heightPx,
                    )
            }
        if (handle <= 0L) return@runOnRenderThread null
        val texturePtr = NativeTaoMacOsTextureBridge.nativeTexturePtr(handle)
        if (texturePtr == 0L) {
            NativeTaoMacOsTextureBridge.nativeDestroy(handle)
            return@runOnRenderThread null
        }
        val colorFormat =
            if (NativeTaoMacOsTextureBridge.nativePixelFormat(handle) == NativeTaoMacOsTextureBridge.FORMAT_RGBA8) {
                SurfaceColorFormat.RGBA_8888
            } else {
                SurfaceColorFormat.BGRA_8888
            }
        val renderTarget = BackendRenderTarget.makeMetal(widthPx, heightPx, texturePtr)
        val surface =
            runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = host.directContext,
                    rt = renderTarget,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = colorFormat,
                    colorSpace = ColorSpace.sRGB,
                )
            }.getOrNull()
        if (surface == null) {
            renderTarget.close()
            NativeTaoMacOsTextureBridge.nativeDestroy(handle)
            return@runOnRenderThread null
        }
        MacImportedTexture(handle, host, renderTarget, surface, widthPx, heightPx)
    }
}

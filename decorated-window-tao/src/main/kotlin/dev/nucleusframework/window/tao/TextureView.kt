package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge
import dev.nucleusframework.window.tao.popup.LocalTaoPopupHostWindows
import dev.nucleusframework.window.tao.popup.TaoPopupHostWindows
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceOrigin
import kotlin.math.roundToInt

/** `GL_TEXTURE_2D` / `GR_GL_RGBA8` — Skia's GL backend constants. */
private const val GL_TEXTURE_2D = 0x0DE1
private const val GR_GL_RGBA8 = 0x8058

/**
 * Handle to an external GPU texture composited by [TextureView].
 * Obtain one from a platform-specific factory such as
 * [nucleusD3D11SharedTextureSource].
 */
public sealed interface TextureViewSource

/**
 * Windows source: a D3D11 texture shared through a **legacy** DXGI
 * shared handle (`IDXGIResource::GetSharedHandle`). NT handles
 * (`D3D11_RESOURCE_MISC_SHARED_NTHANDLE`) are not supported by ANGLE's
 * import path.
 *
 * Synchronization is picked automatically from the producer texture:
 *
 *  - `D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX` — **recommended**. Each
 *    [TextureViewController.markFrameAvailable] pulls the frame into a
 *    private staging texture under `AcquireSync(0)`/`ReleaseSync(0)`;
 *    rendering is tear-free at the cost of one GPU-GPU copy per frame.
 *    The producer must bracket its writes the same way (acquire key 0,
 *    write, release key 0).
 *  - plain `D3D11_RESOURCE_MISC_SHARED` — true zero copy, Skia samples
 *    the producer texture live. The producer must `Flush()` after each
 *    frame; a racing redraw may sample a partially written frame.
 *
 * The texture must be `R8G8B8A8_UNORM` (or a compatible RGBA8 format)
 * with premultiplied alpha; [widthPx]/[heightPx] must match the D3D
 * texture size exactly.
 */
public fun nucleusD3D11SharedTextureSource(
    sharedHandle: Long,
    widthPx: Int,
    heightPx: Int,
): TextureViewSource = D3D11SharedTextureSource(sharedHandle, widthPx, heightPx)

internal data class D3D11SharedTextureSource(
    val sharedHandle: Long,
    val widthPx: Int,
    val heightPx: Int,
) : TextureViewSource

/**
 * Frame-availability signal for [TextureView] — the counterpart of
 * Flutter's `markTextureFrameAvailable`. The producer calls
 * [markFrameAvailable] after publishing a frame; only the **draw pass**
 * of the attached [TextureView]s is invalidated (no recomposition, no
 * layout), so a 60 fps producer costs composition nothing.
 *
 * [markFrameAvailable] is safe to call from any thread.
 */
public class TextureViewController {
    private var stampCounter = 0L
    internal val frameStamp = mutableStateOf(0L)

    /** Signals that the producer published a new frame. Any thread. */
    public fun markFrameAvailable() {
        // Synchronized so concurrent producers still yield distinct,
        // monotonic stamps (a lost increment could suppress a redraw).
        synchronized(this) {
            stampCounter += 1
            frameStamp.value = stampCounter
        }
    }
}

/** Remembers a [TextureViewController] for the current composition. */
@Composable
public fun rememberTextureViewController(): TextureViewController = remember { TextureViewController() }

/**
 * Composites an externally produced GPU texture inside the Compose
 * scene — the "passive GPU pixels" counterpart of [NativeView]
 * (discussion #338), equivalent in spirit to Flutter's `Texture`
 * widget. Unlike [NativeView], the pixels take part in normal
 * composition: z-order, clipping, `Modifier.graphicsLayer` transforms
 * and scrolling all apply, and no CPU frame copy ever happens (the
 * producer's D3D11 texture is sampled by Skia through ANGLE's
 * shared-resource import).
 *
 * Frame updates flow through [controller]: the producer renders, then
 * calls [TextureViewController.markFrameAvailable] (any thread) — only
 * the draw pass re-executes, never recomposition. Several [TextureView]s
 * sharing one [source] also share a single GPU import.
 *
 * Input is deliberately not handled — the composable is a plain drawing
 * surface; interactive native widgets remain [NativeView] territory.
 *
 * **Windows (Tao backend) only for now.** On other platforms — or when
 * [source] is null or the import fails (ANGLE unavailable, bad handle)
 * — it renders as an empty `Box(modifier)`.
 *
 * @param source producer texture handle, see [nucleusD3D11SharedTextureSource].
 * @param controller frame-availability signal; omit for static content.
 * @param filterQuality sampling filter, like `Image`'s parameter
 *   ([FilterQuality.None] = nearest, [FilterQuality.High] = cubic).
 * @param contentScale how the texture maps to the composable's bounds
 *   (content outside the bounds is clipped).
 * @param alignment placement of the scaled texture inside the bounds.
 */
@Composable
public fun TextureView(
    source: TextureViewSource?,
    modifier: Modifier = Modifier,
    controller: TextureViewController? = null,
    filterQuality: FilterQuality = FilterQuality.Low,
    contentScale: ContentScale = ContentScale.FillBounds,
    alignment: Alignment = Alignment.Center,
) {
    val popupHost = LocalTaoPopupHostWindows.current
    val d3dSource = source as? D3D11SharedTextureSource
    if (Platform.Current != Platform.Windows ||
        popupHost == null ||
        d3dSource == null ||
        !NativeTaoTextureBridge.isLoaded
    ) {
        Box(modifier)
        return
    }

    // The lease is a RememberObserver: the registry ref is released on
    // onForgotten AND onAbandoned, so a composition that computes this
    // remember block but is never applied can't leak the native import
    // (a DisposableEffect would never run in that case).
    val imported =
        remember(d3dSource, popupHost) {
            TextureImportLease(popupHost, d3dSource)
        }.imported
    if (imported == null) {
        Box(modifier)
        return
    }

    val srcRect =
        remember(d3dSource) {
            Rect(0f, 0f, d3dSource.widthPx.toFloat(), d3dSource.heightPx.toFloat())
        }
    val sampling =
        remember(filterQuality) {
            when (filterQuality) {
                FilterQuality.None -> FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
                FilterQuality.Low -> FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
                FilterQuality.Medium -> FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
                else -> SamplingMode.MITCHELL
            }
        }
    Box(
        modifier.drawBehind {
            // Snapshot read of the frame stamp: markFrameAvailable()
            // invalidates exactly this draw pass, nothing recomposes.
            val stamp = controller?.frameStamp?.value ?: 0L
            if (imported.isSynchronized && imported.lastCopiedStamp != stamp) {
                // Draw runs on the event-loop thread during the scene
                // render: the staging copy is enqueued on ANGLE's device
                // queue ahead of Skia's sampling flush, so this frame
                // already composites the copied content. The stamp is
                // only consumed when the copy happened — a false return
                // means the producer held the mutex past the timeout,
                // and the next redraw must retry or the last frame
                // would stay stale forever.
                if (NativeTaoTextureBridge.nativeUpdateFrame(imported.handle)) {
                    imported.lastCopiedStamp = stamp
                }
            }

            val srcSize = Size(d3dSource.widthPx.toFloat(), d3dSource.heightPx.toFloat())
            val scaleFactor = contentScale.computeScaleFactor(srcSize, size)
            val scaledW = srcSize.width * scaleFactor.scaleX
            val scaledH = srcSize.height * scaleFactor.scaleY
            val offset =
                alignment.align(
                    IntSize(scaledW.roundToInt(), scaledH.roundToInt()),
                    IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    layoutDirection,
                )
            clipRect {
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawImageRect(
                        imported.image,
                        srcRect,
                        Rect.makeXYWH(offset.x.toFloat(), offset.y.toFloat(), scaledW, scaledH),
                        sampling,
                        null,
                        true,
                    )
                }
            }
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

    /** Last controller stamp whose frame was copied. Main thread only. */
    var lastCopiedStamp: Long = -1L

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

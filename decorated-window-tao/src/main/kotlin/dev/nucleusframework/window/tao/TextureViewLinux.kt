package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

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
 *
 * A planar YUV source ([nucleusYuvDmaBufTextureSource]) works the same way, with
 * one import per plane and the conversion folded into the draw that samples them.
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

    val imported =
        remember(source, host) {
            TextureImportLease(glTextureImports, host, source)
        }.imported
    if (imported == null) {
        Box(modifier)
        return
    }

    val sampling = remember(filterQuality) { samplingFor(filterQuality) }
    Box(
        modifier.drawBehind {
            // Snapshot read of the frame stamp: markFrameAvailable() invalidates
            // exactly this draw pass, nothing recomposes. The read is the whole
            // per-frame cost — the texture is the producer's buffer, so no copy
            // or native call is needed to see the new content.
            val stamp = controller?.frameStamp?.longValue ?: 0L
            imported.onDrawPass(controller, stamp)
            val dst = externalTextureDstRect(imported.srcRect, contentScale, alignment)
            clipRect {
                drawIntoCanvas { canvas -> imported.draw(canvas.skiaCanvas, dst, sampling) }
            }
        },
    )
}

/**
 * One plane of an import: the native EGLImage binding plus the Skia image that
 * adopted its GL texture. Skia owns the texture id after adoption (deleted with
 * the image); the native side only tears down the EGLImage.
 */
internal class ImportedPlane(
    val handle: Long,
    val image: Image,
)

/**
 * Pairs the native EGLImage bindings of a source with the Skia images that sample
 * them: one plane for a packed RGB buffer, three for a planar YUV one, in which
 * case [painter] combines them and converts as the scene is drawn.
 */
internal class LinuxImportedTexture(
    private val host: TaoGlTextureHost,
    private val planes: List<ImportedPlane>,
    val widthPx: Int,
    val heightPx: Int,
    private val painter: ShaderYuvPainter?,
) {
    val srcRect: Rect = Rect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

    /**
     * Producer frames already dealt with, per controller — the same gate the
     * backends with per-frame work use, for the same reason: an acquire fence must
     * be waited on once per producer frame, not once per draw of it.
     */
    private val frames = FrameStampGate()

    private var closed = false

    /**
     * Per-frame work of the draw pass, before anything samples the import: makes
     * this surface's GPU wait for the producer's acquire fence when the newest
     * frame carries one. Gated on the frame stamp, so N views sharing a source
     * wait once per producer frame rather than once per draw.
     *
     * The wait is issued into the current EGL context here, i.e. ahead of every
     * draw command Skia will submit for this frame — earlier than strictly needed,
     * and still free, because the wait costs the CPU nothing.
     *
     * A producer on the default contract (finish, then signal) pays one volatile
     * read for all of this.
     */
    fun onDrawPass(
        controller: TextureViewController?,
        stamp: Long,
    ) {
        if (controller == null || !controller.hasAcquireFence) return
        if (!frames.isPending(controller, stamp)) return
        frames.markConsumed(controller, stamp)
        controller.withAcquireFence { fd -> NativeTaoLinuxTextureBridge.nativeWaitFence(fd) }
    }

    fun draw(
        canvas: Canvas,
        dst: Rect,
        sampling: SamplingMode,
    ) {
        val painter = this.painter
        if (painter == null) {
            canvas.drawImageRect(planes[0].image, srcRect, dst, sampling, null, true)
            return
        }
        if (dst.width <= 0f || dst.height <= 0f) return
        painter.draw(canvas, srcRect, dst, sampling)
    }

    fun close() {
        if (closed) return
        closed = true
        // Skia deletes the adopted GL textures from inside image.close(), so it
        // has to see the EGL context that owns them. Disposal reaches us from
        // Compose (inside a render pass, context already current) but also from
        // surface teardown, where nothing is bound — hence the explicit bind.
        //
        // A null result means the context could not be bound. Skipping the Skia
        // frees is then the safe choice — a GL delete with no current context
        // crashes inside the driver — and it is not a leak either: the only way
        // to get here is a surface that already dropped its attachment, whose
        // `DirectContext` was closed with it, and closing a Skia context
        // abandons its GPU resources so the images' eventual unref issues no GL.
        // Worth a line in the log all the same: it means teardown ran in an
        // order this class does not expect.
        val freed =
            host.withContextCurrent {
                painter?.close()
                planes.forEach { it.image.close() }
            }
        if (freed == null) {
            textureLogger.fine { "TextureView: EGL context gone at teardown, Skia images freed with their context" }
        }
        // eglDestroyImageKHR needs no current context.
        planes.forEach { NativeTaoLinuxTextureBridge.nativeDestroy(it.handle, deleteTexture = false) }
    }
}

/**
 * The planar paint and everything it borrows. Skia refcounts a shader handed to a
 * paint, and a runtime shader refcounts its children, but the handles are ours to
 * release — so they are kept together and closed together.
 */
internal class YuvPaint(
    val paint: Paint,
    private val shader: Shader,
    private val children: List<Shader>,
) {
    fun close() {
        paint.close()
        shader.close()
        children.forEach(Shader::close)
    }
}

/**
 * The Linux import ledger. Refcounted and keyed by Skia context + source; see
 * [TextureImportRegistry] for why, and for the scaffolding the three backends
 * share.
 */
private val glTextureImports =
    TextureImportRegistry<TaoGlTextureHost, LinuxImportedTexture>(
        contextOf = { it.directContext },
        importTexture = ::importLinuxTexture,
        closeImport = { it.close() },
    )

/**
 * Drops every import made on [context] — called by a surface right before it
 * closes its `DirectContext` (window hide on Wayland rebuilds the whole EGL
 * attachment, popup/panel teardown destroys it for good). Without this the
 * imports would outlive their context and their Skia images would be freed
 * against a dead one. Leases releasing later find the import already closed.
 */
internal fun releaseGlTextureImports(context: DirectContext) {
    glTextureImports.closeAllFor(context)
}

/**
 * Whether the scene drawing with [context] composites at least one live
 * `TextureView` import — the Linux counterpart of [hasWindowsTextureImports],
 * consulted by the resize-burst pacing decision (#484).
 */
internal fun hasGlTextureImports(context: DirectContext): Boolean = glTextureImports.hasImportsFor(context)

/** Whether an acquire fence descriptor can be owned (and closed) on this platform. */
internal fun canOwnAcquireFence(): Boolean = Platform.Current == Platform.Linux && NativeTaoLinuxTextureBridge.isLoaded

/** Closes a fence descriptor a [TextureViewController] took ownership of. */
internal fun closeAcquireFenceFd(fenceFd: Int) {
    if (canOwnAcquireFence()) NativeTaoLinuxTextureBridge.nativeCloseFenceFd(fenceFd)
}

internal fun importLinuxTexture(
    host: TaoGlTextureHost,
    source: TextureViewSource,
): LinuxImportedTexture? =
    when (source) {
        is DmaBufTextureSource -> importPacked(host, source)
        is EglImageTextureSource -> importEglImage(host, source)
        is YuvDmaBufTextureSource -> importPlanar(host, source)
        else -> null
    }

private fun importPacked(
    host: TaoGlTextureHost,
    source: DmaBufTextureSource,
): LinuxImportedTexture? {
    if (source.widthPx < 1 || source.heightPx < 1) return null
    // Both the GL textures and the Skia images belong to this surface's EGL
    // context, so the whole import runs with it current.
    return host.withContextCurrent {
        val plane = importPlane(host, packedPlaneSpec(source)) ?: return@withContextCurrent null
        LinuxImportedTexture(host, listOf(plane), source.widthPx, source.heightPx, painter = null)
    }
}

private fun importEglImage(
    host: TaoGlTextureHost,
    source: EglImageTextureSource,
): LinuxImportedTexture? {
    if (source.widthPx < 1 || source.heightPx < 1) return null
    return host.withContextCurrent {
        val handle =
            NativeTaoLinuxTextureBridge.nativeImportEglImage(
                source.eglImage,
                source.widthPx,
                source.heightPx,
            )
        if (handle <= 0L) {
            logImportFailure(handle)
            return@withContextCurrent null
        }
        val plane =
            adoptPackedPlane(host, handle, source.widthPx, source.heightPx)
                ?: return@withContextCurrent null
        LinuxImportedTexture(host, listOf(plane), source.widthPx, source.heightPx, painter = null)
    }
}

private fun importPlanar(
    host: TaoGlTextureHost,
    source: YuvDmaBufTextureSource,
): LinuxImportedTexture? {
    if (source.widthPx < 1 || source.heightPx < 1) return null
    return host.withContextCurrent {
        val luma = importPlane(host, lumaSpec(source)) ?: return@withContextCurrent null
        val chroma = importPlanes(host, chromaSpecs(source))
        if (chroma == null) {
            closePlanes(listOf(luma))
            return@withContextCurrent null
        }
        val painter = painterFor(source, luma.image, chroma.map { it.image })
        LinuxImportedTexture(host, listOf(luma) + chroma, source.widthPx, source.heightPx, painter)
    }
}

/** The shader that turns this source's three planes into pixels. */
private fun painterFor(
    source: YuvDmaBufTextureSource,
    luma: Image,
    chroma: List<Image>,
): ShaderYuvPainter =
    ShaderYuvPainter(
        YuvConversion.of(source.colorSpace),
        listOf(luma) + chroma,
        ((source.widthPx + 1) / 2).toFloat() / source.widthPx,
        ((source.heightPx + 1) / 2).toFloat() / source.heightPx,
    )

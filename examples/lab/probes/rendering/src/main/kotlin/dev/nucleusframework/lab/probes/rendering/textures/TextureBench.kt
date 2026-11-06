package dev.nucleusframework.lab.probes.rendering.textures

import dev.nucleusframework.window.tao.D3D11TestTextureProducer
import dev.nucleusframework.window.tao.DmaBufTestTextureProducer
import dev.nucleusframework.window.tao.MetalTestTextureProducer
import dev.nucleusframework.window.tao.NucleusDrmFormat
import dev.nucleusframework.window.tao.NucleusYuvFormat
import dev.nucleusframework.window.tao.TextureViewSource

const val TEXTURE_WIDTH = 128
const val TEXTURE_HEIGHT = 96

/**
 * One of the bundled external producers, platform-agnostic: a D3D11 shared handle on
 * Windows, a Metal `IOSurface` on macOS, a DMA-BUF `EGLImage` on Linux. Each draws an
 * animated pattern on its own device; frames reach the scene through `markFrameAvailable`.
 */
class ExternalProducer(
    val source: TextureViewSource,
    val kind: String,
    val syncMode: String,
    private val pattern: (tick: Int, backgroundArgb: Int) -> Unit,
    private val fencedPattern: ((tick: Int, backgroundArgb: Int) -> Int)? = null,
    private val closeProducer: () -> Unit,
) : AutoCloseable {
    fun draw(
        tick: Int,
        backgroundArgb: Int,
    ) = pattern(tick, backgroundArgb)

    /** Linux planar only: draws and returns an acquire-fence fd the compositor waits on. */
    fun drawFenced(
        tick: Int,
        backgroundArgb: Int,
    ): Int? = fencedPattern?.invoke(tick, backgroundArgb)

    override fun close() = closeProducer()
}

/**
 * The producers the texture probe compares, created once and owned by its ViewModel so a
 * revisit finds them running. Every factory returns null off its platform.
 */
class TextureBench : AutoCloseable {
    /** Windows: keyed mutex (tear-free staging); elsewhere the primary import. */
    val primary: ExternalProducer? = create(synchronized = true)

    /** Windows: no mutex (true zero copy); Linux: the mirrored ABGR byte order. */
    val secondary: ExternalProducer? = create(synchronized = false)

    /** Linux: a planar I420 buffer, what a hardware decoder hands out, with an acquire fence. */
    val planar: ExternalProducer? =
        DmaBufTestTextureProducer.createYuv(TEXTURE_WIDTH, TEXTURE_HEIGHT)?.let {
            ExternalProducer(
                it.source,
                "DMA-BUF I420",
                "planar, acquire fence",
                it::drawTestPattern,
                it::drawTestPatternFenced,
                it::close,
            )
        }

    /** Linux: the same layout with chroma planes swapped (YV12): must look identical. */
    val swappedPlanar: ExternalProducer? =
        DmaBufTestTextureProducer.createYuv(TEXTURE_WIDTH, TEXTURE_HEIGHT, NucleusYuvFormat.YV12)?.let {
            ExternalProducer(
                it.source,
                "DMA-BUF YV12",
                "planar, chroma swapped",
                it::drawTestPattern,
                closeProducer = it::close,
            )
        }

    val all: List<ExternalProducer> get() = listOfNotNull(primary, secondary, planar, swappedPlanar)

    override fun close() = all.forEach(ExternalProducer::close)

    private fun create(synchronized: Boolean): ExternalProducer? {
        D3D11TestTextureProducer.create(TEXTURE_WIDTH, TEXTURE_HEIGHT, useKeyedMutex = synchronized)?.let {
            val mode = if (synchronized) "keyed mutex (tear-free staging)" else "no mutex (zero copy, producer flushes)"
            return ExternalProducer(
                it.source,
                "D3D11 shared handle",
                mode,
                it::drawTestPattern,
                closeProducer = it::close,
            )
        }
        MetalTestTextureProducer.create(TEXTURE_WIDTH, TEXTURE_HEIGHT)?.let {
            val mode = if (synchronized) "IOSurface import (one GPU copy per frame)" else "second MTLDevice + IOSurface"
            return ExternalProducer(it.source, "Metal IOSurface", mode, it::drawTestPattern, closeProducer = it::close)
        }
        // The mirrored byte order proves the DRM FourCC, not the app, tells the driver how to read.
        val fourcc = if (synchronized) NucleusDrmFormat.ARGB8888 else NucleusDrmFormat.ABGR8888
        DmaBufTestTextureProducer.create(TEXTURE_WIDTH, TEXTURE_HEIGHT, fourcc)?.let {
            val mode = "EGLImage import, ${if (synchronized) "ARGB8888" else "ABGR8888"} (zero copy)"
            return ExternalProducer(it.source, "DMA-BUF", mode, it::drawTestPattern, closeProducer = it::close)
        }
        return null
    }
}

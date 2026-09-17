package dev.nucleusframework.window.tao.clipboard

import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxClipboardBridge
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.imageio.ImageIO

/** `image/png` as a stream — one of the two flavors AWT itself exposes for a clipboard image. */
internal val PngStreamFlavor: DataFlavor = DataFlavor("image/png; class=java.io.InputStream", "PNG image")

/** Freedesktop file list as raw text, next to [DataFlavor.javaFileListFlavor]. */
internal val UriListFlavor: DataFlavor = DataFlavor("text/uri-list; class=java.lang.String", "File list")

/**
 * A [Transferable] over the GTK selection, exposing the same flavors AWT would
 * for the same content — text, image, file list — so application code that
 * reads a clip entry cannot tell which clipboard served it.
 *
 * Text is passed in already fetched (it is small and every paste needs it).
 * The image and the file list are fetched **on demand** through [fetchPng] /
 * [fetchUriList], because pasting text while a screenshot sits on the
 * clipboard should not drag megabytes through JNI. A null fetcher means the
 * selection does not advertise that format at all, so the flavor is not
 * exposed either.
 *
 * The fetchers block; [dev.nucleusframework.window.tao.clipboard.TaoLinuxClipboard]
 * is what routes them to the GTK thread. That is unavoidable: [getTransferData]
 * is a blocking AWT API called from arbitrary threads.
 */
internal class GtkClipboardTransferable(
    private val text: String?,
    private val fetchPng: (() -> ByteArray?)?,
    private val fetchUriList: (() -> String?)?,
) : Transferable {
    private val png: ByteArray? by lazy { fetchPng?.invoke() }
    private val files: List<File> by lazy { parseUriList(fetchUriList?.invoke()) }

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        buildList {
            if (text != null) add(DataFlavor.stringFlavor)
            if (fetchPng != null) {
                add(DataFlavor.imageFlavor)
                add(PngStreamFlavor)
            }
            if (fetchUriList != null) {
                add(DataFlavor.javaFileListFlavor)
                add(UriListFlavor)
            }
        }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        transferDataFlavors.any { it.isMimeTypeEqual(flavor) && it.representationClass == flavor.representationClass }

    override fun getTransferData(flavor: DataFlavor): Any =
        when {
            flavor.equals(DataFlavor.stringFlavor) -> text ?: unsupported(flavor)
            flavor.equals(DataFlavor.imageFlavor) -> decodeImage() ?: unsupported(flavor)
            flavor.isMimeTypeEqual(PngStreamFlavor) -> ByteArrayInputStream(png ?: unsupported(flavor))
            flavor.equals(DataFlavor.javaFileListFlavor) -> files.ifEmpty { unsupported(flavor) }
            flavor.isMimeTypeEqual(UriListFlavor) -> files.joinToString("\r\n") { it.toURI().toString() }
            else -> unsupported(flavor)
        }

    private fun decodeImage(): Image? = png?.let { ImageIO.read(ByteArrayInputStream(it)) }

    private fun unsupported(flavor: DataFlavor): Nothing = throw UnsupportedFlavorException(flavor)
}

/** Newline- or CRLF-separated `file://` URIs to local files, skipping comments and anything remote. */
internal fun parseUriList(uriList: String?): List<File> =
    uriList
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.mapNotNull { line -> runCatching { URI(line) }.getOrNull() }
        ?.filter { it.scheme == "file" }
        ?.mapNotNull { uri -> runCatching { File(uri) }.getOrNull() }
        ?.toList()
        .orEmpty()

/**
 * `text/plain` content of the transferable, or null when it carries none.
 * Swallows the `IOException` / `UnsupportedFlavorException` pair AWT throws
 * when the owning process died between the flavor check and the read — which
 * applies to every accessor in this file.
 */
internal fun Transferable.plainTextOrNull(): String? = read(DataFlavor.stringFlavor) as? String

/** PNG bytes for whatever image the transferable carries, re-encoding if needed. */
internal fun Transferable.pngBytesOrNull(): ByteArray? {
    (read(PngStreamFlavor) as? InputStream)?.use { return it.readBytes() }
    val image = read(DataFlavor.imageFlavor) as? Image ?: return null
    return image.toPngBytes()
}

/** Newline-separated `file://` URIs for the transferable's file list, or null. */
internal fun Transferable.fileUriListOrNull(): String? {
    val files = read(DataFlavor.javaFileListFlavor) as? List<*> ?: return null
    return files
        .filterIsInstance<File>()
        .joinToString("\n") { it.toURI().toString() }
        .ifEmpty { null }
}

private fun Transferable.read(flavor: DataFlavor): Any? =
    runCatching {
        if (isDataFlavorSupported(flavor)) getTransferData(flavor) else null
    }.getOrNull()

private fun Image.toPngBytes(): ByteArray? {
    val buffered = this as? BufferedImage ?: rasterize() ?: return null
    return runCatching {
        ByteArrayOutputStream()
            .also { out ->
                if (!ImageIO.write(buffered, "png", out)) throw IOException("no PNG writer")
            }.toByteArray()
    }.getOrNull()
}

/** Draws a non-[BufferedImage] (e.g. a `ToolkitImage` handed over by AWT) into one. */
private fun Image.rasterize(): BufferedImage? {
    val width = getWidth(null)
    val height = getHeight(null)
    if (width <= 0 || height <= 0) return null
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return target
}

/**
 * What of a clip entry GTK can take ownership of. Text wins when an entry
 * carries several: a GTK selection holds one payload, and text is the format
 * every other application can read.
 */
internal sealed interface GtkPayload {
    /** Runs on the GTK main thread. */
    fun publish(): Boolean

    class Text(
        val text: String,
    ) : GtkPayload {
        override fun publish(): Boolean = NativeTaoLinuxClipboardBridge.nativeSetTextUtf8(text.toByteArray())
    }

    class Image(
        private val png: ByteArray,
    ) : GtkPayload {
        override fun publish(): Boolean = NativeTaoLinuxClipboardBridge.nativeSetImagePng(png)
    }

    class Files(
        private val uriList: String,
    ) : GtkPayload {
        override fun publish(): Boolean = NativeTaoLinuxClipboardBridge.nativeSetUriListUtf8(uriList.toByteArray())
    }
}

internal fun Transferable.toGtkPayload(): GtkPayload? =
    plainTextOrNull()?.let(GtkPayload::Text)
        ?: pngBytesOrNull()?.let(GtkPayload::Image)
        ?: fileUriListOrNull()?.let(GtkPayload::Files)

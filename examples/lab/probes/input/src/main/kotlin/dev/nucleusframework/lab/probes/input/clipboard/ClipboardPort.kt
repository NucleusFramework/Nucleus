package dev.nucleusframework.lab.probes.input.clipboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import dev.nucleusframework.lab.core.LabPaths
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File

/** What the clipboard holds, read flavor by flavor. */
@Immutable
data class ClipboardContent(
    val mimeTypes: List<String>,
    val text: String?,
    val html: String?,
    val files: List<String>,
    val imageSize: String?,
    val errors: List<String>,
) {
    val isEmpty: Boolean get() = mimeTypes.isEmpty()
}

enum class Payload { Text, Html, Image, Files }

/**
 * Port over the Compose [Clipboard] the window provides: on Linux Nucleus routes it through
 * GTK (#582), elsewhere it is the AWT-backed one. It only exists inside a composition, so the
 * probe binds it to the ViewModel while visible.
 */
interface ClipboardPort {
    suspend fun read(): ClipboardContent

    suspend fun write(
        payload: Payload,
        text: String,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
class ComposeClipboardPort(
    private val clipboard: Clipboard,
) : ClipboardPort {
    override suspend fun read(): ClipboardContent {
        val transferable =
            clipboard.getClipEntry()?.asAwtTransferable
                ?: return ClipboardContent(emptyList(), null, null, emptyList(), null, emptyList())
        return readTransferable(transferable)
    }

    override suspend fun write(
        payload: Payload,
        text: String,
    ) {
        val transferable =
            when (payload) {
                Payload.Text -> Flavored(mapOf(DataFlavor.stringFlavor to text))
                Payload.Html ->
                    Flavored(
                        mapOf(
                            DataFlavor("text/html;class=java.lang.String") to "<b>$text</b> <i>(rich)</i>",
                            DataFlavor.stringFlavor to "$text (rich)",
                        ),
                    )
                Payload.Image -> Flavored(mapOf(DataFlavor.imageFlavor to gradientImage()))
                Payload.Files ->
                    Flavored(
                        mapOf(
                            DataFlavor.javaFileListFlavor to
                                listOf(LabPaths.scratchFile("clipboard.txt", text).toFile()),
                        ),
                    )
            }
        clipboard.setClipEntry(ClipEntry(transferable))
    }
}

internal fun readTransferable(transferable: Transferable): ClipboardContent {
    val errors = mutableListOf<String>()
    val flavors = runCatching { transferable.transferDataFlavors.toList() }.getOrDefault(emptyList())

    fun <T> read(
        flavor: DataFlavor,
        map: (Any) -> T,
    ): T? =
        if (flavors.none { it.match(flavor) }) {
            null
        } else {
            runCatching { map(transferable.getTransferData(flavor)) }
                .onFailure { errors += "${flavor.mimeType.substringBefore(';')}: ${it::class.simpleName}" }
                .getOrNull()
        }
    val htmlFlavor =
        flavors.firstOrNull {
            it.mimeType.startsWith("text/html") &&
                it.representationClass == String::class.java
        }
    return ClipboardContent(
        mimeTypes = flavors.map { it.mimeType.substringBefore(';') }.distinct(),
        text = read(DataFlavor.stringFlavor) { it as String },
        html = htmlFlavor?.let { f -> read(f) { it as String } },
        files =
            read(
                DataFlavor.javaFileListFlavor,
            ) { data -> (data as List<*>).map { (it as File).absolutePath } }.orEmpty(),
        imageSize =
            read(DataFlavor.imageFlavor) {
                (it as java.awt.Image).let { img ->
                    "${img.getWidth(null)}×${img.getHeight(null)}"
                }
            },
        errors = errors,
    )
}

/** A transferable serving exactly the given flavors. */
private class Flavored(
    private val data: Map<DataFlavor, Any>,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = data.keys.any { it.match(flavor) }

    override fun getTransferData(flavor: DataFlavor?): Any =
        data.entries.firstOrNull { it.key.match(flavor) }?.value ?: throw UnsupportedFlavorException(flavor)
}

private fun gradientImage(): BufferedImage {
    val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until 64) {
        for (y in 0 until 32) {
            image.setRGB(
                x,
                y,
                (0xFF shl 24) or (x * 4 shl 16) or (y * 8 shl 8) or 0xC0,
            )
        }
    }
    return image
}

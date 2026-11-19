package dev.nucleusframework.window.tao.dnd

import dev.nucleusframework.window.tao.TaoDragAndDropPayload
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.net.URI
import java.net.URL

/**
 * Adapter that exposes a [TaoDragAndDropPayload] to the standard Compose
 * Desktop / AWT data-transfer API.
 *
 * This is what makes `event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)`
 * work transparently on the Tao backend even though no AWT [java.awt.dnd.DropTarget]
 * is actually involved on the I/O path.
 *
 * Supported flavors, each only when the payload carries the matching data.
 * An empty payload (a hover before the platform could read anything) keeps
 * answering an empty file list and an empty string, as this adapter always
 * did, so a `shouldStartDragAndDrop` reading the files unconditionally does
 * not start throwing:
 *  - [DataFlavor.javaFileListFlavor] → `List<File>`
 *  - [URI_LIST_FLAVOR] → the URIs, one per line (RFC 2483 `text/uri-list`)
 *  - [URL_FLAVOR] → the first URI as a `java.net.URL`, as AWT exposed a
 *    browser's `UniformResourceLocator`
 *  - [DataFlavor.stringFlavor] → the text, else the URIs, else the newline-separated
 *    absolute file paths
 *
 * Construction is cheap (no native handles, no I/O). Lives in `java.datatransfer`,
 * not `java.awt` proper — does not initialise the AWT toolkit.
 */
internal class TaoDropTransferable(
    private val payload: TaoDragAndDropPayload,
) : Transferable {
    private val files: List<File> by lazy { payload.files.map(::File) }

    private val hasFiles: Boolean = payload.files.isNotEmpty() || payload.isEmpty

    private val url: URL? by lazy {
        payload.uris.firstNotNullOfOrNull { runCatching { URI(it).toURL() }.getOrNull() }
    }

    private val string: String? =
        payload.text
            ?: payload.uris.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: payload.files.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: "".takeIf { payload.isEmpty }

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        buildList {
            if (hasFiles) add(DataFlavor.javaFileListFlavor)
            if (payload.uris.isNotEmpty()) add(URI_LIST_FLAVOR)
            if (url != null) add(URL_FLAVOR)
            if (string != null) add(DataFlavor.stringFlavor)
        }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
        when (flavor) {
            DataFlavor.javaFileListFlavor -> hasFiles
            URI_LIST_FLAVOR -> payload.uris.isNotEmpty()
            URL_FLAVOR -> url != null
            DataFlavor.stringFlavor -> string != null
            else -> false
        }

    override fun getTransferData(flavor: DataFlavor?): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            URI_LIST_FLAVOR -> payload.uris.joinToString("\r\n")
            URL_FLAVOR -> checkNotNull(url)
            else -> checkNotNull(string)
        }
    }

    companion object {
        /** RFC 2483 URI list, as AWT names it on X11 and macOS. */
        val URI_LIST_FLAVOR: DataFlavor = DataFlavor("text/uri-list; class=java.lang.String")

        /** A single URL, as AWT maps the Windows `UniformResourceLocator` format. */
        val URL_FLAVOR: DataFlavor = DataFlavor("application/x-java-url; class=java.net.URL")
    }
}

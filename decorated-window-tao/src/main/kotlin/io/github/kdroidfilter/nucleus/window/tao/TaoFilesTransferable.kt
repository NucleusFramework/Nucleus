package io.github.kdroidfilter.nucleus.window.tao

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * Adapter that exposes a [TaoDragAndDropPayload] to the standard Compose
 * Desktop / AWT data-transfer API.
 *
 * This is what makes `event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)`
 * work transparently on the Tao backend even though no AWT [java.awt.dnd.DropTarget]
 * is actually involved on the I/O path.
 *
 * Supported flavors:
 *  - [DataFlavor.javaFileListFlavor] → `List<File>`
 *  - [DataFlavor.stringFlavor] → newline-separated absolute paths
 *
 * Construction is cheap (no native handles, no I/O). Lives in `java.datatransfer`,
 * not `java.awt` proper — does not initialise the AWT toolkit.
 */
internal class TaoFilesTransferable(
    private val files: List<File>,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = SUPPORTED_FLAVORS.copyOf()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
        flavor != null && SUPPORTED_FLAVORS.any { it == flavor }

    override fun getTransferData(flavor: DataFlavor?): Any = when {
        flavor == DataFlavor.javaFileListFlavor -> files
        flavor == DataFlavor.stringFlavor -> files.joinToString("\n") { it.absolutePath }
        else -> throw UnsupportedFlavorException(flavor)
    }

    private companion object {
        private val SUPPORTED_FLAVORS = arrayOf(
            DataFlavor.javaFileListFlavor,
            DataFlavor.stringFlavor,
        )
    }
}

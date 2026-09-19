package demo.shim

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

// DragAndDropEvent payload helpers. On the Tao backend (as on standard Compose
// Desktop / the legacy AWT backend) drops surface through the same AWT transfer
// path exercised by tao-demo: DragAndDropEvent.awtTransferable exposes the
// payload via the AWT flavor system.

@OptIn(ExperimentalComposeUiApi::class)
fun DragAndDropEvent.demoReadFilePaths(): List<String> {
    val transferable = runCatching { awtTransferable }.getOrNull() ?: return emptyList()
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    @Suppress("UNCHECKED_CAST")
    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return emptyList()
    return files.map { it.absolutePath }
}

@OptIn(ExperimentalComposeUiApi::class)
fun DragAndDropEvent.demoReadText(): String? {
    val transferable = runCatching { awtTransferable }.getOrNull() ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
}

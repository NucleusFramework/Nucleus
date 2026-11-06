package dev.nucleusframework.window.tao.dnd

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * A drag payload that never leaves the process.
 *
 * The cross-window gestures — docking a satellite, tearing a tab off — ride
 * the platform's drag-and-drop session on native Wayland, where it is the only
 * pointer grab that crosses windows and reports coordinates. What travels is a
 * token, not data: the session's meaning lives in the workspace that started
 * it, and every target is in this process. The native side offers the token
 * under [MIME] to this application only, so a foreign drop target never sees
 * a stray string and a foreign source can never spoof one.
 */
internal object TaoPrivateTransfer {
    /** Must match the Rust `PRIVATE_TARGET` in `dnd.rs`. */
    const val MIME: String = "application/x-nucleus-private"

    /** The AWT flavor the token is carried under, so it fits Compose's `DragAndDropTransferable`. */
    val FLAVOR: DataFlavor = DataFlavor("$MIME; class=java.lang.String")

    /** A transferable offering only [token] under [FLAVOR]. */
    fun transferable(token: String): Transferable = PrivateTransferable(token)

    /** The token a transferable carries under [FLAVOR], or `null` when it carries none. */
    fun tokenOf(transferable: Transferable): String? =
        if (transferable.isDataFlavorSupported(FLAVOR)) {
            runCatching { transferable.getTransferData(FLAVOR) as? String }.getOrNull()
        } else {
            null
        }

    private class PrivateTransferable(
        private val token: String,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == FLAVOR

        override fun getTransferData(flavor: DataFlavor?): Any =
            if (flavor == FLAVOR) token else throw UnsupportedFlavorException(flavor)
    }
}

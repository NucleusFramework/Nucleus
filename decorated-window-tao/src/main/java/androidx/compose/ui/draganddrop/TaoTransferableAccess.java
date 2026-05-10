package androidx.compose.ui.draganddrop;

import java.awt.datatransfer.Transferable;

/**
 * Friend-package accessor for {@link AwtDragAndDropTransferable}, which is
 * declared {@code internal} in the Kotlin module {@code compose-ui-desktop}
 * and therefore unreachable from another Kotlin module — but Java does not
 * honour Kotlin's {@code internal} visibility, so a Java file living in the
 * same package can perform the cast directly.
 *
 * <p>This avoids reflection (which would require GraalVM native-image
 * reachability metadata for the anonymous class returned by Compose's
 * {@code DragAndDropTransferable(java.awt.datatransfer.Transferable)}
 * factory). Static dispatch through this helper compiles cleanly under
 * native-image with zero metadata.
 */
public final class TaoTransferableAccess {
    private TaoTransferableAccess() {
    }

    /**
     * Returns the underlying AWT {@link Transferable} that backs the given
     * Compose {@link DragAndDropTransferable}, or {@code null} if the
     * transferable was not produced by Compose's AWT-bound factory.
     */
    public static Transferable toAwt(DragAndDropTransferable transferable) {
        if (transferable instanceof AwtDragAndDropTransferable) {
            return ((AwtDragAndDropTransferable) transferable).toAwtTransferable();
        }
        return null;
    }
}

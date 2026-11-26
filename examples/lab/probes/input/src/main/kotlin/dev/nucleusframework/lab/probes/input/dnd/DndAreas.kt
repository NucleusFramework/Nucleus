package dev.nucleusframework.lab.probes.input.dnd

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.TargetArea
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** Text, a real temporary file and a URL, each draggable into this window or another app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DragSources(vm: DndViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        Source("Drag text", "text", vm) { StringSelection("hello-from-nucleus-lab") }
        Source("Drag file", "file", vm) {
            val file = LabPaths.scratchFile("drag-source.txt", "Exported by the Nucleus Lab drag source\n")
            SingleFlavor(DataFlavor.javaFileListFlavor, listOf(file.toFile()))
        }
        Source("Drag URL", "url", vm) { StringSelection("https://nucleusframework.dev/") }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Source(
    label: String,
    kind: String,
    vm: DndViewModel,
    payload: () -> Transferable,
) {
    TargetArea(
        Modifier
            .width(140.dp)
            .height(56.dp)
            .dragAndDropSource(
                transferData = {
                    vm.onPhase(DragPhase.Started)
                    DragAndDropTransferData(
                        transferable = DragAndDropTransferable(payload()),
                        supportedActions = listOf(DragAndDropTransferAction.Copy),
                        onTransferCompleted = { action -> vm.onExported(kind, action?.toString() ?: "cancelled") },
                    )
                },
            ),
        label = label,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DropZone(
    vm: DndViewModel,
    hovering: Boolean,
) {
    val target =
        remember(vm) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) = vm.onPhase(DragPhase.Started)

                override fun onEntered(event: DragAndDropEvent) = vm.onPhase(DragPhase.Entered)

                override fun onMoved(event: DragAndDropEvent) = vm.onPhase(DragPhase.Moved)

                override fun onExited(event: DragAndDropEvent) = vm.onPhase(DragPhase.Exited)

                override fun onEnded(event: DragAndDropEvent) = vm.onPhase(DragPhase.Ended)

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    vm.onDrop(runCatching { event.awtTransferable }.getOrNull())
                    return true
                }
            }
        }
    TargetArea(
        Modifier.height(160.dp).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
        highlighted = hovering,
        label = if (hovering) "Release to drop" else "Drop text, files, URLs or images here, from here or another app",
    )
}

/** A transferable offering exactly one flavor. */
private class SingleFlavor(
    private val flavor: DataFlavor,
    private val data: Any,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)

    override fun isDataFlavorSupported(candidate: DataFlavor?): Boolean = candidate == flavor

    override fun getTransferData(candidate: DataFlavor?): Any {
        if (candidate != flavor) throw UnsupportedFlavorException(candidate)
        return data
    }
}

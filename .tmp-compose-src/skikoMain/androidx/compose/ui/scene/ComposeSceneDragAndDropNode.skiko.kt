/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropNode
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DragAndDropOwner
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource

/** Provides API for [PlatformDragAndDropManager] to integrate [ComposeScene] with the platform. */
// TODO: Extract to interface and implement it in [DragAndDropNode]
@InternalComposeUiApi
class ComposeSceneDragAndDropNode internal constructor(
    private val dragAndDropOwner: () -> DragAndDropOwner,
) : DragAndDropTarget {
    private var startedOwner: DragAndDropOwner? = null
    private val currentRootNode: DragAndDropNode
        get() = dragAndDropOwner().rootNode

    private fun ensureStartedOwner(event: DragAndDropEvent): DragAndDropOwner {
        val currentOwner = dragAndDropOwner()
        if (startedOwner != currentOwner) {
            startedOwner?.onEnded(event)
            startedOwner = currentOwner
            startedOwner?.onStarted(event)
        }
        return currentOwner
    }

    /**
     * Indicates whether there is a child that is eligible to receive a drop gesture immediately.
     * This is true if the last move happened over a child that is interested in receiving a drop.
     */
    val hasEligibleDropTarget: Boolean
        get() = currentRootNode.hasEligibleDropTarget

    /**
     * The entry point to register interest in a drag and drop session for receiving data.
     *
     * @return true to indicate interest in the contents of a drag and drop session, false indicates
     *   no interest. If false is returned, this [Modifier] will not receive any [DragAndDropTarget]
     *   events.
     */
    fun acceptDragAndDropTransfer(startEvent: DragAndDropEvent): Boolean {
        startedOwner?.onEnded(startEvent)
        startedOwner = null
        return currentRootNode.acceptDragAndDropTransfer(startEvent)
    }

    /**
     * Initiates a drag-and-drop operation for transferring data.
     *
     * @param offset the offset value representing position of the input pointer.
     * @param isTransferStarted a lambda function that returns true if the drag-and-drop transfer
     *   has started, or false otherwise.
     */
    fun PlatformDragAndDropSource.StartTransferScope.startDragAndDropTransfer(
        offset: Offset,
        isTransferStarted: () -> Boolean
    ): Unit = with(currentRootNode) {
        asDragAndDropStartTransferScope().startDragAndDropTransfer(offset, isTransferStarted)
    }

    override fun onDrop(event: DragAndDropEvent): Boolean = ensureStartedOwner(event).onDrop(event)


    override fun onStarted(event: DragAndDropEvent) {
        ensureStartedOwner(event)
    }
    override fun onEntered(event: DragAndDropEvent) = ensureStartedOwner(event).onEntered(event)

    override fun onMoved(event: DragAndDropEvent) = ensureStartedOwner(event).onMoved(event)

    override fun onExited(event: DragAndDropEvent) = ensureStartedOwner(event).onExited(event)

    override fun onChanged(event: DragAndDropEvent) = ensureStartedOwner(event).onChanged(event)

    override fun onEnded(event: DragAndDropEvent) {
        startedOwner?.onEnded(event)
        startedOwner = null
    }
}

// TODO: Remove after combine [DragAndDropStartTransferScope] and [PlatformDragAndDropSource]
private fun PlatformDragAndDropSource.StartTransferScope.asDragAndDropStartTransferScope(): DragAndDropStartTransferScope =
    object : DragAndDropStartTransferScope {
        override fun startDragAndDropTransfer(
            transferData: DragAndDropTransferData,
            decorationSize: Size,
            drawDragDecoration: DrawScope.() -> Unit
        ): Boolean =
            this@asDragAndDropStartTransferScope.startDragAndDropTransfer(
                transferData = transferData,
                decorationSize = decorationSize,
                drawDragDecoration = drawDragDecoration
            )
    }

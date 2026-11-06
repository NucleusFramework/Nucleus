package dev.nucleusframework.lab.probes.input.pointer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.TargetArea
import dev.nucleusframework.window.tao.TaoPointerIcons

/** Compose's portable icons plus the Tao-native ones (AWT `Cursor` icons do not work on Tao). */
private val cursors: List<Pair<String, PointerIcon>> =
    listOf(
        "Default" to PointerIcon.Default,
        "Hand" to PointerIcon.Hand,
        "Text" to PointerIcon.Text,
        "Crosshair" to PointerIcon.Crosshair,
        "Grab" to TaoPointerIcons.Grab,
        "Grabbing" to TaoPointerIcons.Grabbing,
        "Move" to TaoPointerIcons.Move,
        "NotAllowed" to TaoPointerIcons.NotAllowed,
        "Wait" to TaoPointerIcons.Wait,
        "Progress" to TaoPointerIcons.Progress,
        "Help" to TaoPointerIcons.Help,
        "Resize E-W" to TaoPointerIcons.ResizeEastWest,
        "Resize N-S" to TaoPointerIcons.ResizeNorthSouth,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CursorGrid() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        cursors.forEach { (name, icon) ->
            TargetArea(Modifier.width(112.dp).height(48.dp).pointerHoverIcon(icon), label = name)
        }
    }
}

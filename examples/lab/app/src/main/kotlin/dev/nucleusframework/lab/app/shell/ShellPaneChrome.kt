package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.ChromeIconButton
import dev.nucleusframework.lab.designsystem.LabIcons
import dev.nucleusframework.lab.designsystem.LabPaneHeader
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.tao.DockSplitterScope
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.satelliteDragHandle

/**
 * The header of a shell pane, docked above its content or floating in its window's title
 * bar: the title, the pane's own [actions], then Float / Dock and Hide.
 *
 * Docked, the whole strip is the grip that drags the pane out or to another side; floating,
 * the title bar already is one. Where the compositor places the window (native Wayland) the
 * strip is drawn as a chip, so it reads apart from the caption strip that moves the window.
 */
@Composable
fun SatelliteScope.ShellPaneHeader(
    pane: ShellPane,
    title: String,
    shell: ShellViewModel,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val chip = !isDocked && isCompositorPlaced
    val header =
        @Composable { modifier: Modifier ->
            LabPaneHeader(title, modifier, framed = isDocked) {
                actions()
                if (isDocked) {
                    if (satellite.isFloatable) ChromeIconButton(LabIcons.Window, "Float") { undock() }
                } else if (satellite.dockSides.isNotEmpty()) {
                    ChromeIconButton(LabIcons.Dock, "Dock") { dock() }
                }
                ChromeIconButton(LabIcons.Hide, "Hide") { shell.onIntent(ShellIntent.SetPaneOpen(pane, false)) }
            }
        }
    if (isDocked) {
        header(Modifier.satelliteDragHandle(this))
    } else {
        Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            header(
                if (chip) {
                    Modifier.padding(vertical = 4.dp).clip(LabShapes.block).background(LabTheme.colors.raised)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * Composed only where the compositor owns the window move (native Wayland): the caption
 * strip beside the window controls, which moves the window, as opposed to the header chip,
 * which drags the pane into a dock.
 */
@Composable
fun SatelliteScope.PaneMoveAffordance() {
    Text("✥", style = LabTheme.typography.small, color = LabTheme.colors.textMuted)
}

/** A 1 dp border line carrying a 5 dp invisible grip: IntelliJ's tool window divider. */
@Composable
fun DockSplitterScope.ShellSplitter() {
    val horizontal = orientation == Orientation.Horizontal
    val line = if (horizontal) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp)
    Box(line.background(LabTheme.colors.border), contentAlignment = Alignment.Center) {
        val grip =
            if (horizontal) {
                Modifier.requiredWidth(5.dp).fillMaxHeight()
            } else {
                Modifier.requiredHeight(5.dp).fillMaxWidth()
            }
        Box(grip.dockSplitterHandle())
    }
}

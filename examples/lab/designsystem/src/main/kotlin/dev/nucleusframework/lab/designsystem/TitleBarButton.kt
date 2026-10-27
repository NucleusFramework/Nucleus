package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.TitleBarScope

/**
 * The one icon button of every Lab title bar (shell and session windows), shaped like
 * IntelliJ's toolbar buttons: hover and active backgrounds, a tooltip, and
 * `titleBarClickable` so a click lands even where the title bar's drag region would
 * otherwise swallow it (macOS fullscreen).
 */
@Composable
fun TitleBarScope.TitleBarButton(
    icon: LabIcon,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    ChromeIconBox(icon, description, modifier, active, Modifier.titleBarClickable(onClick))
}

/**
 * The same toolbar-style icon button outside a title bar (a tab strip's trailing slot, a
 * panel header): a normal click instead of the title bar's press-to-click.
 */
@Composable
fun ChromeIconButton(
    icon: LabIcon,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    ChromeIconBox(icon, description, modifier, active, Modifier.clickable(onClick = onClick))
}

@Composable
private fun ChromeIconBox(
    icon: LabIcon,
    description: String,
    modifier: Modifier,
    active: Boolean,
    clickModifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = LabTheme.colors
    val background =
        when {
            active && hovered -> colors.selection.copy(alpha = 0.75f)
            active -> colors.selection
            hovered -> colors.raised
            else -> Color.Transparent
        }
    Tooltip(description, modifier) {
        Box(
            Modifier
                .size(30.dp)
                .clip(LabShapes.small)
                .hoverable(interaction)
                .background(background)
                .then(clickModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description)
        }
    }
}

/** [TitleBarButton] with a probe's own vector. */
@Composable
fun TitleBarScope.TitleBarButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TitleBarButton(remember(icon) { LabIcon.of(icon) }, description, modifier, active, onClick)
}

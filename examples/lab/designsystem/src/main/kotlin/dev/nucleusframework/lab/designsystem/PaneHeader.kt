package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The header strip of a pane (a tool window, a docked or floating panel): IntelliJ's tool
 * window header, the title then [actions] ([ChromeIconButton]s, [Link]s, a [Segmented]).
 *
 * [framed] draws the panel background and a bottom divider, for a header above its content;
 * `false` blends into whatever it sits in (a floating panel's title bar).
 */
@Composable
fun LabPaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().then(if (framed) Modifier.background(LabTheme.colors.panel) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = LabTheme.typography.heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (framed) Divider()
    }
}

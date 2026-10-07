package dev.nucleusframework.lab.probes.input.a11y.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.lab.designsystem.LabTheme

/**
 * The page tabs of the accessibility surface. Their labels are the names the CI probes
 * select with `select_tab()` (AT-SPI "page tab" + "click" action, UIA TabItem), so they
 * are part of the fixture contract.
 */
enum class SurfaceTab(
    val label: String,
) {
    A11y("A11y"),
    Complex("Complex"),
    ;

    companion object {
        fun parse(value: String?): SurfaceTab? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Tab list exposed as a selectable group of `Role.Tab` items, as screen readers expect. */
@Composable
fun SurfaceTabBar(
    selected: SurfaceTab,
    onSelect: (SurfaceTab) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(LabTheme.colors.panel)
                .selectableGroup()
                .semantics {
                    // Asserted by the goldens ('Main navigation tabs', role panel).
                    contentDescription = "Main navigation tabs"
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfaceTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier =
                    Modifier
                        .height(40.dp)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(tab) },
                            role = Role.Tab,
                        ).semantics(mergeDescendants = true) {
                            role = Role.Tab
                            this[SemanticsProperties.Selected] = isSelected
                            contentDescription = tab.label
                        }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = tab.label,
                    style =
                        LabTheme.typography.body.copy(
                            color = if (isSelected) LabTheme.colors.accent else LabTheme.colors.textMuted,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                )
            }
        }
    }
}

/** The surface content for [tab]; shared by the fixture window and the in-Lab probe. */
@Composable
fun SurfaceContent(
    tab: SurfaceTab,
    modifier: Modifier = Modifier,
    onA11yEvent: (String) -> Unit = {},
) {
    when (tab) {
        SurfaceTab.A11y -> A11ySurface(modifier, onEvent = onA11yEvent)
        SurfaceTab.Complex -> ComplexSurface(modifier)
    }
}

package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.format.fmt
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.component.Dropdown as JewelDropdown
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu

private const val SEGMENTED_MAX_OPTIONS = 5
private const val SEGMENTED_MAX_CHARS = 48

/**
 * Single choice among named options: a segmented control for a few short ones, a combo box
 * otherwise.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    val names = options.map(name)
    LabeledRow(label) {
        if (options.size <= SEGMENTED_MAX_OPTIONS && names.sumOf { it.length } <= SEGMENTED_MAX_CHARS) {
            SegmentedControl(
                buttons =
                    options.mapIndexed { index, option ->
                        SegmentedControlButtonData(
                            selected = option == selected,
                            content = { _ -> Text(names[index], style = LabTheme.typography.label) },
                            onSelect = { onSelect(option) },
                        )
                    },
            )
        } else {
            ChoiceDropdown(options, selected, name, Modifier.widthIn(min = 160.dp, max = 360.dp), onSelect)
        }
    }
}

/** An IntelliJ combo box over [options]. */
@Composable
fun <T> ChoiceDropdown(
    options: List<T>,
    selected: T,
    name: (T) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    ListComboBox(
        items = options.map(name),
        selectedIndex = options.indexOf(selected).coerceAtLeast(0),
        onSelectedItemChange = { index -> options.getOrNull(index)?.let(onSelect) },
        modifier = modifier,
        textStyle = LabTheme.typography.body,
    )
}

/** An entry of a [Dropdown] menu; `null` in the list draws a separator. */
@Immutable
data class DropdownItem(
    val text: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** A button showing [text] that opens a menu of [items]. */
@Composable
fun Dropdown(
    text: String,
    items: List<DropdownItem?>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    JewelDropdown(
        modifier = modifier,
        enabled = enabled,
        menuContent = { menuItems(items) },
    ) {
        Text(text, maxLines = 1)
    }
}

/**
 * A menu of [items] anchored below its parent, shown while [expanded]: for a menu the program
 * opens and closes itself ([Dropdown] opens on click).
 */
@Composable
fun PopupMenu(
    expanded: Boolean,
    items: List<DropdownItem?>,
    onDismissRequest: () -> Unit,
) {
    if (!expanded) return
    JewelPopupMenu(
        onDismissRequest = {
            onDismissRequest()
            true
        },
        horizontalAlignment = Alignment.Start,
    ) {
        menuItems(items)
    }
}

private fun MenuScope.menuItems(items: List<DropdownItem?>) {
    items.forEach { item ->
        if (item == null) {
            separator()
        } else {
            selectableItem(selected = item.selected, onClick = item.onClick, enabled = item.enabled) {
                Text(item.text)
            }
        }
    }
}

/** Independent on/off options as chips: several may be on at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToggleChipsRow(
    label: String,
    options: List<Pair<String, Boolean>>,
    enabled: Boolean = true,
    onToggle: (index: Int, checked: Boolean) -> Unit,
) {
    LabeledRow(label) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            options.forEachIndexed { index, (name, checked) ->
                ToggleChip(name, checked, enabled = enabled) { onToggle(index, it) }
            }
        }
    }
}

@Composable
fun LabSlider(
    value: Float,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
    )
}

/** A slider with its label column and value: for Controls sections. Narrow panes use [LabSlider]. */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    format: (Float) -> String = { it.fmt() },
    onValueChange: (Float) -> Unit,
) {
    LabeledRow(label) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f),
        )
        Text(format(value), style = LabTheme.typography.mono, modifier = Modifier.width(64.dp))
    }
}

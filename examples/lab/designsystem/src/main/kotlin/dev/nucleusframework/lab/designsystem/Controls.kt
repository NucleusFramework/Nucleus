package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.ToggleableChip
import org.jetbrains.jewel.ui.component.CheckboxRow as JewelCheckboxRow
import org.jetbrains.jewel.ui.component.RadioButtonRow as JewelRadioButtonRow

/** Wrapping row of actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Actions(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** The action a probe is about (send, start, apply). At most one or two per block. */
@Composable
fun PrimaryAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    DefaultButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = LabTheme.typography.label)
    }
}

/** Every other action. */
@Composable
fun SecondaryAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = LabTheme.typography.label)
    }
}

/** Low-emphasis action inside dense content (clear, copy, details): an IntelliJ link. */
@Composable
fun TertiaryAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Link(text, modifier, enabled = enabled, onClick = onClick)
}

/** `label   control`: the label column every control row shares. */
@Composable
fun LabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Text(label, style = LabTheme.typography.label, modifier = Modifier.width(LabDimens.labelWidth))
        content()
    }
}

/** An on/off setting. Jewel has no switch: an IntelliJ checkbox in the label column's row. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    LabeledRow(label) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            if (checked) "on" else "off",
            style = LabTheme.typography.small,
            color = if (enabled) LabTheme.colors.textMuted else LabTheme.colors.textDisabled,
        )
    }
}

/** A checkbox with its text, for option lists where the label column does not fit. */
@Composable
fun CheckboxRow(
    text: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    JewelCheckboxRow(
        text = text,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = LabTheme.typography.body,
    )
}

/** One radio button of a group, with its text. */
@Composable
fun RadioRow(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    JewelRadioButtonRow(
        text = text,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        textStyle = LabTheme.typography.body,
    )
}

/**
 * An on/off chip. [tone] colours its text while it is on (pass / fail / skip buttons).
 */
@Composable
fun ToggleChip(
    text: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ToggleableChip(checked = checked, onClick = onCheckedChange, modifier = modifier, enabled = enabled) {
        Text(
            text,
            style = LabTheme.typography.label,
            color = if (checked && tone != null) tone.color() else Color.Unspecified,
            maxLines = 1,
        )
    }
}

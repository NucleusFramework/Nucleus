package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.TextArea as JewelTextArea
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

/** A single-line IntelliJ text field over a plain `String` value. */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
) {
    val state = rememberSyncedState(value, onValueChange)
    JewelTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = LabTheme.typography.body,
        placeholder = placeholder?.let { { Text(it, color = LabTheme.colors.textMuted) } },
    )
}

/** A multi-line IntelliJ text area, at least [minLines] high. */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    TextArea(rememberSyncedState(value, onValueChange), modifier, minLines, placeholder, enabled)
}

/**
 * A multi-line IntelliJ text area over a [TextFieldState], for probes that observe more than
 * the text: selection, IME composition.
 */
@Composable
fun TextArea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    JewelTextArea(
        state = state,
        // Bounded: Jewel's TextArea scrolls itself, which an unbounded height would break.
        modifier = modifier.heightIn(min = (minLines * 18 + 12).dp, max = 320.dp),
        enabled = enabled,
        textStyle = LabTheme.typography.body,
        placeholder = placeholder?.let { { Text(it, color = LabTheme.colors.textMuted) } },
    )
}

@Composable
fun TextFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    if (singleLine && minLines <= 1) {
        LabeledRow(label, modifier) { TextField(value, onValueChange, Modifier.weight(1f)) }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = LabTheme.typography.label)
            TextArea(value, onValueChange, Modifier.fillMaxWidth(), minLines = minLines.coerceAtLeast(2))
        }
    }
}

@Composable
fun NumberFieldRow(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
) {
    val text = value?.toString().orEmpty()
    val state = rememberSyncedState(text) { onValueChange(it.toIntOrNull()) }
    LabeledRow(label) {
        JewelTextField(
            state = state,
            modifier = Modifier.weight(1f),
            inputTransformation = DigitsOnly,
            textStyle = LabTheme.typography.mono,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

private val DigitsOnly = InputTransformation { if (!asCharSequence().all(Char::isDigit)) revertAllChanges() }

/**
 * Jewel fields own a [TextFieldState]; the Lab's API is value-based. Edits flow out through
 * [onValueChange], and a new [value] from outside replaces the text.
 */
@Composable
private fun rememberSyncedState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val state = rememberTextFieldState(value)
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text -> if (text != latestValue) latestOnChange(text) }
    }
    return state
}

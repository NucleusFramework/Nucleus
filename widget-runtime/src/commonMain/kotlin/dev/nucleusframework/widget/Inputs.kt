package dev.nucleusframework.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode

// Inputs are bound to a widget state key and read back from the state like any other value. Only
// the inputs every host can draw are here: a switch and a choice. When the host writes their value
// differs (Windows submits it with the next action that runs app code, WidgetKit when clicked).
// Text and number entry exist on Windows alone: `widget-windows` builds them on the Platform*
// composables below.

/**
 * A text field showing and editing [key], for platform modules: apps use the host's own
 * (`dev.nucleusframework.widget.windows.TextField`), since not every host has text entry.
 */
@InternalNucleusWidgetApi
@Composable
public fun PlatformTextField(
    key: WidgetStateKey<String>,
    modifier: WidgetModifier = WidgetModifier,
    label: String? = null,
    placeholder: String? = null,
    isMultiline: Boolean = false,
    maxLength: Int? = null,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    val value = LocalWidgetStateHandle.current[key]
    ComposeNode<WidgetNode.TextInput, WidgetApplier>(
        factory = WidgetNode::TextInput,
        update = {
            set(resolved) { this.modifier = it }
            set(key.storageName) { this.id = it }
            set(value) { this.value = it }
            set(label) { this.label = it }
            set(placeholder) { this.placeholder = it }
            set(isMultiline) { this.isMultiline = it }
            set(maxLength) { this.maxLength = it }
        },
    )
}

/** An on/off switch labelled [title], showing and editing [key]; [defaultValue] while unset. */
@Composable
public fun Switch(
    key: WidgetStateKey<Boolean>,
    title: String,
    modifier: WidgetModifier = WidgetModifier,
    defaultValue: Boolean = false,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    val value = LocalWidgetStateHandle.current[key] ?: defaultValue
    ComposeNode<WidgetNode.Toggle, WidgetApplier>(
        factory = WidgetNode::Toggle,
        update = {
            set(resolved) { this.modifier = it }
            set(key.storageName) { this.id = it }
            set(title) { this.title = it }
            set(value) { this.value = it }
        },
    )
}

/**
 * A choice among [choices], showing and editing [key] (the chosen [Choice.value]): a drop-down,
 * or radio buttons when [isExpanded].
 */
@Composable
public fun ChoiceField(
    key: WidgetStateKey<String>,
    choices: List<Choice>,
    modifier: WidgetModifier = WidgetModifier,
    label: String? = null,
    isExpanded: Boolean = false,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    val value = LocalWidgetStateHandle.current[key]
    ComposeNode<WidgetNode.ChoiceInput, WidgetApplier>(
        factory = WidgetNode::ChoiceInput,
        update = {
            set(resolved) { this.modifier = it }
            set(key.storageName) { this.id = it }
            set(value) { this.value = it }
            set(choices) { this.choices = it }
            set(label) { this.label = it }
            set(isExpanded) { this.isExpanded = it }
        },
    )
}

/**
 * A number field showing and editing [key], for platform modules: apps use the host's own
 * (`dev.nucleusframework.widget.windows.NumberField`), since not every host has number entry.
 */
@InternalNucleusWidgetApi
@Composable
public fun PlatformNumberField(
    key: WidgetStateKey<out Number>,
    modifier: WidgetModifier = WidgetModifier,
    label: String? = null,
    min: Double? = null,
    max: Double? = null,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    val value = LocalWidgetStateHandle.current[key]?.toDouble()
    ComposeNode<WidgetNode.NumberInput, WidgetApplier>(
        factory = WidgetNode::NumberInput,
        update = {
            set(resolved) { this.modifier = it }
            set(key.storageName) { this.id = it }
            set(value) { this.value = it }
            set(label) { this.label = it }
            set(min) { this.min = it }
            set(max) { this.max = it }
        },
    )
}

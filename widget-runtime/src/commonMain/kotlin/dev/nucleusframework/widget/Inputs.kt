package dev.nucleusframework.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode

// Inputs are bound to a widget state key: the host submits their values with the next action
// that runs app code (any Button or clickable but an actionOpenUrl), and the runtime writes them
// into the state before that action runs. Read them back from the state like any other value.

/** A text field showing and editing [key]. */
@Composable
public fun TextField(
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
 * A number field showing and editing [key]: an [intStateKey] keeps whole numbers only (a decimal
 * entry leaves it unset), a [doubleStateKey] any number.
 */
@Composable
public fun NumberField(
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

/**
 * Shows the web page at [url] in place of the widget's other elements, which remain the fallback
 * where the host cannot show web content (on Windows, web widgets are limited to the European
 * Economic Area). Messages to and from the page go through [NucleusWidget.onMessage] and
 * [sendWidgetMessage]; [NucleusWidget.onWebRequest] answers the requests the manifest filters.
 */
@Composable
public fun WebContent(
    url: String,
    modifier: WidgetModifier = WidgetModifier,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.WebContent, WidgetApplier>(
        factory = WidgetNode::WebContent,
        update = {
            set(resolved) { this.modifier = it }
            set(url) { this.url = it }
        },
    )
}

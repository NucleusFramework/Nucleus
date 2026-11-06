@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.windows

import androidx.compose.runtime.Composable
import dev.nucleusframework.widget.Action
import dev.nucleusframework.widget.ActionCallback
import dev.nucleusframework.widget.ActionParameters
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.PlatformNumberField
import dev.nucleusframework.widget.PlatformTextField
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetStateHandle
import dev.nucleusframework.widget.WidgetStateKey
import dev.nucleusframework.widget.actionRunCallback
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.currentState
import dev.nucleusframework.widget.doubleStateKey
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.updateWidgetState

// What only the Windows widgets board offers, so not in widget-runtime's common API: text and
// number entry (Adaptive Card inputs) and the widget's customization UI. Other hosts ignore them.

/**
 * A text field showing and editing [key]. The board submits its value with the next action that
 * runs app code (any Button or clickable but an actionOpenUrl), and the runtime writes it into the
 * state before that action runs.
 */
@Composable
public fun TextField(
    key: WidgetStateKey<String>,
    modifier: WidgetModifier = WidgetModifier,
    label: String? = null,
    placeholder: String? = null,
    isMultiline: Boolean = false,
    maxLength: Int? = null,
): Unit = PlatformTextField(key, modifier, label, placeholder, isMultiline, maxLength)

/**
 * A number field showing and editing [key], submitted like [TextField]: an [intStateKey] keeps
 * whole numbers only (a decimal entry leaves it unset), a [doubleStateKey] any number.
 */
@Composable
public fun NumberField(
    key: WidgetStateKey<out Number>,
    modifier: WidgetModifier = WidgetModifier,
    label: String? = null,
    min: Double? = null,
    max: Double? = null,
): Unit = PlatformNumberField(key, modifier, label, min, max)

/**
 * Whether the user asked for the widget's customization UI (*Customize widget* in its menu,
 * offered when the plugin's `windows { widgets { widget(…) { customizable = true } } }`). Show the
 * settings while it is `true`, and leave with [actionFinishCustomization] or
 * [WidgetStateHandle.finishCustomization]:
 *
 * ```
 * if (isCustomizing()) {
 *     TextField(CityKey, label = "City")
 *     Button("Done", onClick = actionFinishCustomization())
 * } else { … }
 * ```
 */
@Composable
public fun isCustomizing(): Boolean = currentState(CustomizingKey) == true

/** Leaves the widget's customization UI (see [isCustomizing]); input values are kept. */
public fun actionFinishCustomization(): Action = actionRunCallback(FinishCustomization)

/** Leaves the customization UI the board asked for (see [isCustomizing]). */
public fun WidgetStateHandle.finishCustomization() {
    update { it.remove(CustomizingKey) }
}

/** Reserved: whether the board asked for the widget's customization UI; survives a restart. */
internal val CustomizingKey: WidgetStateKey<Boolean> = booleanStateKey("nucleus.customizing")

/** Puts [instance] in customization mode; the host renders it next. */
internal fun requestCustomization(instance: WidgetInstance) {
    WidgetEngine.emit(WidgetEvent.CustomizationRequested(instance.id))
    instance.state[CustomizingKey] = true
}

// A callback, not a lambda: it changes the state the action runs on (updateWidgetState on its own
// widget), whichever render the click came from.
private object FinishCustomization : ActionCallback {
    override suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    ) {
        updateWidgetState(id) { it.remove(CustomizingKey) }
    }
}

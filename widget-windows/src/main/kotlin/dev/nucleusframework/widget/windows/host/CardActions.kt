package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetElement
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetInstance

/**
 * Clicks against a card that may be older than the widget state. Action keys are positions, so
 * each action of a card carries what it showed: a signature of the clicked element
 * ([ACTION_INPUT]) and the value of every input as displayed ([SHOWN_PREFIX] + input id). The
 * click runs only if its key still names the same element, and only the inputs the user changed
 * are written, so an app-side change made after the card was drawn is never reverted.
 */
internal object CardActions {
    const val ACTION_INPUT = "nucleus.action"
    const val SHOWN_PREFIX = "nucleus.shown."
    private const val META_PREFIX = "nucleus."

    /** What became of a click. */
    enum class Outcome {
        /** The action ran. */
        Ran,

        /** The card was outdated: its key now names another element. Edited inputs were kept. */
        Dropped,

        /** The key names no action any more. */
        Stale,
    }

    /** The signature of what each action key of [root] stands for: element kind and label. */
    fun signatures(root: WidgetElement): Map<String, String> = WidgetActionSignatures.of(root)

    /** Every input of [root] by id, valued as the card shows it (and submits it when untouched). */
    fun shownInputs(root: WidgetElement): Map<String, String> = buildMap { collectInputs(root, this) }

    /** The action data stamping the action [key] of a card rendered from [root]. */
    fun stamp(
        key: String,
        signatures: Map<String, String>,
        shown: Map<String, String>,
    ): Map<String, String> =
        buildMap {
            signatures[key]?.let { put(ACTION_INPUT, it) }
            shown.forEach { (id, value) -> put(SHOWN_PREFIX + id, value) }
        }

    /** The inputs the user changed on the card; untouched ones would overwrite newer state. */
    fun editedInputs(inputs: Map<String, String>): Map<String, String> =
        inputs.filter { (id, value) -> !id.startsWith(META_PREFIX) && inputs[SHOWN_PREFIX + id] != value }

    /**
     * Runs the click on action [key] of [instance] with the action data [inputs]: writes the edited
     * inputs, then runs the action unless the card was outdated ([WidgetEvent.ActionDropped]).
     */
    suspend fun run(
        instance: WidgetInstance,
        key: String,
        inputs: Map<String, String>,
    ): Outcome {
        val edited = editedInputs(inputs)
        val clicked = inputs[ACTION_INPUT]
        if (clicked != null) {
            WidgetEngine.applyInputs(instance, edited)
            if (signatures(WidgetEngine.render(instance).root)[key] != clicked) {
                WidgetEngine.emit(WidgetEvent.ActionDropped(instance.id, key))
                return Outcome.Dropped
            }
        }
        return if (WidgetEngine.invokeAction(instance, key, edited)) Outcome.Ran else Outcome.Stale
    }

    private fun collectInputs(
        element: WidgetElement,
        out: MutableMap<String, String>,
    ) {
        when (element) {
            is WidgetElement.TextInput -> out[element.id] = element.value.orEmpty()
            is WidgetElement.Toggle -> out[element.id] = element.value.toString()
            is WidgetElement.ChoiceInput -> out[element.id] = element.value.orEmpty()
            // Written like the card's own value (Json drops ".0" from whole numbers).
            is WidgetElement.NumberInput ->
                out[element.id] =
                    element.value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
            else -> children(element).forEach { collectInputs(it, out) }
        }
    }

    private fun children(element: WidgetElement): List<WidgetElement> =
        when (element) {
            is WidgetElement.Column -> element.children
            is WidgetElement.Row -> element.children
            else -> emptyList()
        }
}

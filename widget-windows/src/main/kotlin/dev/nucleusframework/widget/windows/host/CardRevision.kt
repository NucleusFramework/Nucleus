package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.longStateKey

/**
 * Action keys are positions in the card, so a click is only meaningful against the state the
 * card was drawn from. Every push and save stamps the state with a new revision; the card sends
 * the revision it was drawn at with each action ([AdaptiveCardRenderer.REVISION_INPUT]).
 */
internal object CardRevision {
    // Reserved state key, next to the widget's own values.
    private val Key = longStateKey("nucleus.revision")

    /** Stamps [state] with a new revision and returns it. */
    fun bump(state: MutableWidgetState): Long = ((state[Key] ?: 0L) + 1).also { state[Key] = it }

    /**
     * Whether a click whose action [inputs] came from a card may run against [state]: `false` when
     * the card is older than the state. A card without a revision (drawn before it existed) passes.
     */
    fun isCurrent(
        state: WidgetState,
        inputs: Map<String, String>,
    ): Boolean {
        val clicked = inputs[AdaptiveCardRenderer.REVISION_INPUT]?.toLongOrNull() ?: return true
        return clicked == state[Key]
    }
}

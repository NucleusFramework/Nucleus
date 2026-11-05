@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.TextField
import dev.nucleusframework.widget.WidgetElement
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private val Items = stringStateKey("items")
private val Title = stringStateKey("title")
private val Saved = intStateKey("saved")
private val Removed = stringStateKey("removed")

/** A list whose rows come before the Save button: adding an item moves Save's key. */
internal class ItemsWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            Column {
                for (item in state[Items].orEmpty().split(',').filter(String::isNotEmpty)) {
                    Button(item, onClick = { state.update { it[Removed] = item } })
                }
                TextField(Title)
                Button("Save", onClick = { state.update { it[Saved] = (it[Saved] ?: 0) + 1 } })
            }
        }
    }
}

class CardActionsTest {
    init {
        startWindowsTestWidgets()
    }

    private fun instance(items: String) =
        WidgetInstance(
            WidgetId("i"),
            "Items",
            WidgetSize.Medium,
            WidgetState.decode(null).apply { this[Items] = items },
        )

    /** The card as drawn now: its root, from which a click's action data is built. */
    private suspend fun WidgetInstance.card(): WidgetElement = WidgetEngine.render(this).root

    private fun keyOf(
        card: WidgetElement,
        text: String,
    ): String =
        CardActions
            .signatures(card)
            .entries
            .first { it.value == "button:$text" }
            .key

    /** The action data the board sends for a click on [key] of [card], with [typed] input values. */
    private fun click(
        card: WidgetElement,
        key: String,
        typed: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val shown = CardActions.shownInputs(card)
        return shown + typed + CardActions.stamp(key, CardActions.signatures(card), shown)
    }

    @Test
    fun aClickOnTheCurrentCardRuns() =
        runBlocking {
            val widget = instance("a,b")
            val card = widget.card()
            val outcome = CardActions.run(widget, keyOf(card, "Save"), click(card, keyOf(card, "Save")))
            assertEquals(CardActions.Outcome.Ran, outcome)
            assertEquals(1, widget.state[Saved])
        }

    @Test
    fun aClickWhoseKeyNowNamesAnotherElementIsDroppedButKeepsTyping() =
        runBlocking {
            val widget = instance("a,b")
            val card = widget.card()
            val save = keyOf(card, "Save")
            widget.state[Items] = "a,b,c" // the app adds an item: Save's key now names "c"
            val outcome = CardActions.run(widget, save, click(card, save, mapOf("s:title" to "Typed")))
            assertEquals(CardActions.Outcome.Dropped, outcome)
            assertEquals(null, widget.state[Removed], "the row now at that key did not run")
            assertEquals(null, widget.state[Saved])
            assertEquals("Typed", widget.state[Title], "what the user typed is kept")
        }

    @Test
    fun aStateChangeThatMovesNoActionLetsTheClickRun() =
        runBlocking {
            val widget = instance("a,b")
            val card = widget.card()
            widget.state[Saved] = 5 // the app changed a value, not the layout
            val outcome = CardActions.run(widget, keyOf(card, "Save"), click(card, keyOf(card, "Save")))
            assertEquals(CardActions.Outcome.Ran, outcome)
            assertEquals(6, widget.state[Saved])
        }

    @Test
    fun anUntouchedInputNeverRevertsANewerAppValue() =
        runBlocking {
            val widget = instance("a")
            widget.state[Title] = "Old"
            val card = widget.card()
            widget.state[Title] = "New" // set by the app after the card was drawn
            CardActions.run(widget, keyOf(card, "Save"), click(card, keyOf(card, "Save")))
            assertEquals("New", widget.state[Title])

            CardActions.run(widget, keyOf(card, "Save"), click(card, keyOf(card, "Save"), mapOf("s:title" to "Edited")))
            assertEquals("Edited", widget.state[Title], "an edited input is written")
        }
}

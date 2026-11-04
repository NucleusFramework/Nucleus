package dev.nucleusframework.widget

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val Count = intStateKey("count")
private val Label = stringStateKey("label")

internal class CounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val count = state[Count] ?: 0
            Column(spacing = Spacing.Small) {
                Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
                Text("${LocalSize.current}", style = TextStyle(color = WidgetColor.Subtle))
                Row {
                    Button("+1", onClick = { state.update { it[Count] = count + 1 } })
                    Button("Open", onClick = actionOpenUrl("app://counter"))
                }
            }
        }
    }
}

class WidgetRuntimeTest {
    init {
        startTestWidgets()
    }

    private fun instance(state: MutableWidgetState = WidgetState.decode(null)) =
        WidgetInstance(WidgetId("w1"), "Counter", WidgetSize.Medium, state)

    @Test
    fun rendersTheElementTree() =
        runTest {
            val render = WidgetEngine.render(instance())
            val root = assertIs<WidgetElement.Column>(render.root)
            val column = assertIs<WidgetElement.Column>(root.children.single())
            assertEquals(Spacing.Small, column.spacing)
            assertEquals("0", assertIs<WidgetElement.Text>(column.children[0]).text)
            assertEquals("Medium", assertIs<WidgetElement.Text>(column.children[1]).text)
            val row = assertIs<WidgetElement.Row>(column.children[2])
            val plus = assertIs<WidgetElement.Button>(row.children[0])
            assertEquals(ElementAction.Execute("a0"), plus.action)
            val open = assertIs<WidgetElement.Button>(row.children[1])
            assertEquals(ElementAction.OpenUrl("app://counter"), open.action)
        }

    @Test
    fun clickChangesTheInstanceState() =
        runTest {
            val target = instance()
            assertTrue(WidgetEngine.invokeAction(target, "a0"))
            assertTrue(WidgetEngine.invokeAction(target, "a0"))
            assertEquals(2, target.state[Count])
            val again = assertIs<WidgetElement.Column>(WidgetEngine.render(target).root)
            val column = assertIs<WidgetElement.Column>(again.children.single())
            assertEquals("2", assertIs<WidgetElement.Text>(column.children[0]).text)
        }

    @Test
    fun staleKeyIsRejected() =
        runTest {
            assertFalse(WidgetEngine.invokeAction(instance(), "a9"))
        }

    @Test
    fun stateRoundTripsThroughItsEncoding() {
        val state = WidgetState.decode(null)
        state[Count] = 42
        state[Label] = "quote \" backslash \\ newline \n é"
        val decoded = WidgetState.decode(state.encode())
        assertEquals(42, decoded[Count])
        assertEquals("quote \" backslash \\ newline \n é", decoded[Label])
        assertEquals(WidgetState.Empty, WidgetState.decode("not json"))
    }
}

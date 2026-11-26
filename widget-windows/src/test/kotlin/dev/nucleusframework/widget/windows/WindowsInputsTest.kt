@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.windows

import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.ElementAction
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.WidgetElement
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.windows.host.startWindowsTestWidgets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val City = stringStateKey("city")
private val Celsius = booleanStateKey("celsius")
private val Days = intStateKey("days")

internal class CustomizableWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            if (isCustomizing()) {
                Column {
                    TextField(City, label = "City")
                    Switch(Celsius, title = "Celsius")
                    NumberField(Days, label = "Days", min = 1.0, max = 7.0)
                    Button("Done", onClick = actionFinishCustomization())
                }
            } else {
                Column { Text(state[City] ?: "no city") }
            }
        }
    }
}

class WindowsInputsTest {
    init {
        startWindowsTestWidgets()
    }

    private fun WidgetElement.column() =
        assertIs<WidgetElement.Column>(assertIs<WidgetElement.Column>(this).children.single())

    @Test
    fun customizationShowsTheSettingsUntilFinished() =
        runBlocking {
            val target = WidgetInstance(WidgetId("w1"), "Customizable", WidgetSize.Medium, WidgetState.decode(null))
            requestCustomization(target)
            val settings = WidgetEngine.render(target).root.column()
            assertEquals("s:city", assertIs<WidgetElement.TextInput>(settings.children[0]).id)
            assertIs<WidgetElement.Toggle>(settings.children[1])
            assertEquals(7.0, assertIs<WidgetElement.NumberInput>(settings.children[2]).max)

            // The Done button submits the inputs: they land in the state, then customization ends.
            val done = assertIs<WidgetElement.Button>(settings.children[3]).action as ElementAction.Execute
            assertTrue(
                WidgetEngine.invokeAction(
                    target,
                    done.key,
                    WidgetEngine.parseInputs("""{"s:city":"Lyon","b:celsius":"true","i:days":3}"""),
                ),
            )
            assertEquals("Lyon", target.state[City])
            assertEquals(true, target.state[Celsius])
            assertEquals(3, target.state[Days])
            assertFalse(CustomizingKey in target.state)
            assertEquals(
                "Lyon",
                assertIs<WidgetElement.Text>(
                    WidgetEngine
                        .render(target)
                        .root
                        .column()
                        .children[0],
                ).text,
            )
        }

    @Test
    fun customizationSurvivesInTheReservedKey() {
        // Persisted states written before the move keep working: same storage name.
        val state = WidgetState.decode("""{"b:nucleus.customizing":"true"}""")
        assertEquals(true, state[CustomizingKey])
    }
}

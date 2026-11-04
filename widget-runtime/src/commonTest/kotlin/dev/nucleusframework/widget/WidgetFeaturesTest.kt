package dev.nucleusframework.widget

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val City = stringStateKey("city")
private val Celsius = booleanStateKey("celsius")
private val Days = intStateKey("days")
private val Saved = intStateKey("saved")

internal class SettingsWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            if (isCustomizing()) {
                Column {
                    TextField(City, label = "City")
                    Switch(Celsius, title = "Celsius")
                    NumberField(Days, label = "Days", min = 1.0, max = 7.0)
                    ChoiceField(City, listOf(Choice("Paris", "paris"), Choice("Rome", "rome")))
                    Button("Done", onClick = actionFinishCustomization())
                }
            } else {
                Column {
                    WebContent("https://example.com/widget")
                    Text(state[City] ?: "no city")
                    Button("Save", onClick = { state.update { it[Saved] = (it[Saved] ?: 0) + 1 } })
                }
            }
        }
    }
}

class WidgetFeaturesTest {
    init {
        startTestWidgets()
    }

    private fun instance() = WidgetInstance(WidgetId("s1"), "Settings", WidgetSize.Medium, WidgetState.decode(null))

    private fun WidgetElement.column() =
        assertIs<WidgetElement.Column>(assertIs<WidgetElement.Column>(this).children.single())

    @Test
    fun customizationShowsTheSettingsUntilFinished() =
        runTest {
            val target = instance()
            WidgetEngine.requestCustomization(target)
            val settings = WidgetEngine.render(target).root.column()
            val field = assertIs<WidgetElement.TextInput>(settings.children[0])
            assertEquals("s:city", field.id)
            assertIs<WidgetElement.Toggle>(settings.children[1])
            assertIs<WidgetElement.NumberInput>(settings.children[2])
            assertEquals(
                listOf(
                    "paris",
                    "rome",
                ),
                assertIs<WidgetElement.ChoiceInput>(settings.children[3]).choices.map {
                    it.value
                },
            )

            // The Done button submits the inputs: they land in the state, then customization ends.
            val done = assertIs<WidgetElement.Button>(settings.children[4]).action as ElementAction.Execute
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

            val regular = WidgetEngine.render(target).root.column()
            assertEquals("https://example.com/widget", assertIs<WidgetElement.WebContent>(regular.children[0]).url)
            assertEquals("Lyon", assertIs<WidgetElement.Text>(regular.children[1]).text)
        }

    @Test
    fun inputsSubmittedWithAnyActionAreKept() =
        runTest {
            val target = instance()
            val save =
                WidgetEngine
                    .render(target)
                    .root
                    .column()
                    .children[2] as WidgetElement.Button
            WidgetEngine.invokeAction(target, (save.action as ElementAction.Execute).key, mapOf("s:city" to "Oslo"))
            assertEquals("Oslo", target.state[City])
            assertEquals(1, target.state[Saved])
        }

    @Test
    fun malformedActionDataYieldsNoInput() {
        assertEquals(emptyMap(), WidgetEngine.parseInputs(""))
        assertEquals(emptyMap(), WidgetEngine.parseInputs("""{"nested":{"a":1}}"""))
        assertNull(WidgetEngine.parseInputs("""{"s:x":null}""")["s:x"])
    }
}

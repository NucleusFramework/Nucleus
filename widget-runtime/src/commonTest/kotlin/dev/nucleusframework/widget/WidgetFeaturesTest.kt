package dev.nucleusframework.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val City = stringStateKey("city")
private val Celsius = booleanStateKey("celsius")
private val Saved = intStateKey("saved")

internal class SettingsWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            Column {
                Text(state[City] ?: "no city")
                Switch(Celsius, title = "Celsius")
                ChoiceField(City, listOf(Choice("Paris", "paris"), Choice("Rome", "rome")))
                Button("Save", onClick = { state.update { it[Saved] = (it[Saved] ?: 0) + 1 } })
            }
        }
    }
}

private val Bumped = intStateKey("bumped")

private object Bump : ActionCallback {
    val By = ActionParameters.Key<Int>("by")

    override suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    ) {
        updateWidgetState(id) { it[Bumped] = (it[Bumped] ?: 0) + (parameters[By] ?: 0) }
    }
}

internal class CallbackWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            Button("Bump", onClick = actionRunCallback(Bump, actionParametersOf(Bump.By to 3)))
        }
    }
}

/** Suspends on [gate] before changing its widget's state, like a callback waiting on the network. */
private object SlowBump : ActionCallback {
    var gate = CompletableDeferred<Unit>()

    override suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    ) {
        gate.await()
        updateWidgetState(id) { it[Bumped] = (it[Bumped] ?: 0) + 1 }
    }
}

internal class SlowWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent { Button("Slow", onClick = actionRunCallback(SlowBump)) }
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
    fun inputsLandInTheStateBeforeTheAction() =
        runTest {
            val target = instance()
            val column = WidgetEngine.render(target).root.column()
            assertEquals("b:celsius", assertIs<WidgetElement.Toggle>(column.children[1]).id)
            assertEquals(
                listOf("paris", "rome"),
                assertIs<WidgetElement.ChoiceInput>(column.children[2]).choices.map { it.value },
            )

            val save = assertIs<WidgetElement.Button>(column.children[3]).action as ElementAction.Execute
            assertTrue(
                WidgetEngine.invokeAction(
                    target,
                    save.key,
                    WidgetEngine.parseInputs("""{"s:city":"rome","b:celsius":"true"}"""),
                ),
            )
            assertEquals("rome", target.state[City])
            assertEquals(true, target.state[Celsius])
            assertEquals(1, target.state[Saved])
            assertEquals(
                "rome",
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
    fun inputsSubmittedWithAnyActionAreKept() =
        runTest {
            val target = instance()
            val save =
                WidgetEngine
                    .render(target)
                    .root
                    .column()
                    .children[3] as WidgetElement.Button
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

    @Test
    fun anActionCallbackUpdatesTheStateItRunsOnWithoutAHost() =
        runTest {
            val target = WidgetInstance(WidgetId("c1"), "Callback", WidgetSize.Small, WidgetState.decode(null))
            assertTrue(WidgetEngine.invokeAction(target, "a0"))
            assertEquals(3, target.state[Bumped])
        }

    @Test
    fun aSuspendedCallbackKeepsItsWidgetWhileAnotherActionRuns() =
        runTest {
            SlowBump.gate = CompletableDeferred()
            val slow = WidgetInstance(WidgetId("slow"), "Slow", WidgetSize.Small, WidgetState.decode(null))
            val other = WidgetInstance(WidgetId("c2"), "Callback", WidgetSize.Small, WidgetState.decode(null))
            val pending = async { WidgetEngine.invokeAction(slow, "a0") }
            yield() // the slow callback is now suspended on its gate
            assertTrue(WidgetEngine.invokeAction(other, "a0"))
            SlowBump.gate.complete(Unit)
            assertTrue(pending.await())
            assertEquals(1, slow.state[Bumped], "the slow callback still updates its own widget")
            assertEquals(3, other.state[Bumped])
        }
}

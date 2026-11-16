package dev.nucleusframework.widget.ios

import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Count = intStateKey("count")
private val Done = booleanStateKey("done")
private val Items = stringStateKey("items")

/** A counter whose rows come before its button: adding a row moves the button's key. */
private class TestCounter : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            Column {
                for (item in state[Items].orEmpty().split(',').filter(String::isNotEmpty)) {
                    Row(modifier = WidgetModifier.clickable { }) { Text(item) }
                }
                Text("${state[Count] ?: 0}")
                Switch(Done, title = "Done")
                Button("+1", onClick = { state.update { it[Count] = (it[Count] ?: 0) + 1 } })
            }
        }
    }
}

class IosWidgetHostTest {
    private val host: IosWidgetHost =
        IosWidgetHost.current ?: IosWidgetHost(null, NSTemporaryDirectory() + NSUUID().UUIDString).also {
            start(it) { widget("Counter") { TestCounter() } }
            it.start()
        }

    private fun call(vararg fields: String): String? =
        NucleusWidgetKitEntryPoints.call(fields.joinToString(NucleusWidgetKitEntryPoints.SEPARATOR.toString()))

    private fun stateOf(family: String) = runBlocking { host.loadState(WidgetId("Counter/$family")) }

    @Test
    fun rendersTheTreeTheSwiftRendererReads() {
        val tree = call("render", "Counter", "systemMedium", "0")!!
        assertTrue(tree.startsWith("{\"v\":1,\"root\":{\"t\":\"col\""), tree)
        assertTrue("\"t\":\"toggle\",\"s\":\"Done\"" in tree, tree)
        assertTrue("\"k\":\"a0\",\"sig\":\"button:+1\"" in tree, tree)
    }

    @Test
    fun aClickRunsInKotlinAndSavesTheState() {
        assertEquals("1", call("perform", "Counter", "systemSmall", "a0", "button:+1", "{}"))
        assertEquals(1, stateOf("systemSmall")[Count])
        assertTrue("{\"t\":\"text\",\"s\":\"1\"" in call("render", "Counter", "systemSmall", "0")!!)
        // The gallery preview starts from an empty state.
        assertTrue("{\"t\":\"text\",\"s\":\"0\"" in call("render", "Counter", "systemSmall", "1")!!)
    }

    @Test
    fun aToggleWritesItsValueAndAnOutdatedClickIsDropped() {
        assertEquals("0", call("perform", "Counter", "systemLarge", "", "", "{\"b:done\":\"true\"}"))
        assertEquals(true, stateOf("systemLarge")[Done])
        // A row was added after the render: a0 now names the row, not "+1".
        runBlocking {
            host.saveState(
                WidgetId("Counter/systemLarge"),
                stateOf("systemLarge").apply {
                    this[Items] =
                        "milk"
                },
            )
        }
        assertEquals("0", call("perform", "Counter", "systemLarge", "a0", "button:+1", "{}"))
        assertNull(stateOf("systemLarge")[Count])
    }

    @Test
    fun unknownKindsFamiliesAndRequestsAnswerNothing() {
        assertNull(call("render", "Nope", "systemSmall", "0"))
        assertNull(call("render", "Counter", "../../escaped", "0"))
        assertNull(call("explode"))
        assertEquals(null, IosWidgetHost.split(WidgetId("Counter/systemSmall/../x")))
    }
}

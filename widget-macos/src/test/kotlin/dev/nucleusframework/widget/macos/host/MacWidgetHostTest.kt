@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.macos.host

import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.Image
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.PlatformTextField
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetJson
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.macos.WidgetFamily
import dev.nucleusframework.widget.macos.WidgetInfo
import dev.nucleusframework.widget.stringStateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val Count = intStateKey("count")
private val Done = booleanStateKey("done")
private val Items = stringStateKey("items")
private val Removed = stringStateKey("removed")

/** A counter whose list rows come before its button: adding a row moves the button's key. */
internal class MacCounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            Column(modifier = WidgetModifier.background(WidgetBackground.Emphasis)) {
                for (item in state[Items].orEmpty().split(',').filter(String::isNotEmpty)) {
                    Row(modifier = WidgetModifier.clickable { state.update { it[Removed] = item } }) { Text(item) }
                }
                Text("${state[Count] ?: 0}")
                Switch(Done, title = "Done")
                // What widget-windows' TextField emits: WidgetKit has no text entry.
                PlatformTextField(Items, placeholder = "Draft")
                Button("+1", onClick = { state.update { it[Count] = (it[Count] ?: 0) + 1 } })
                Image(ImageProvider(byteArrayOf(1, 2, 3)), contentDescription = "dot")
            }
        }
    }
}

private class FakeEnvironment(
    override val store: File,
) : MacWidgetHost.Environment {
    override val requestNotification = "test.nucleus-widgets.request"
    override val widgets = mapOf("Counter" to listOf("systemSmall", "systemMedium"))
    override val resourcesDir: File? = null
    var pinnedNow = listOf(WidgetInfo("Counter", WidgetFamily.SystemSmall))
    val reloads = mutableListOf<String>()
    var onRequest: (() -> Unit)? = null

    /** Added to the real clock, to step past the host's delays. */
    var offsetMillis = 0L

    override fun nowMillis(): Long = System.currentTimeMillis() + offsetMillis

    override suspend fun pinned(): List<WidgetInfo> = pinnedNow

    override fun reload(kind: String) {
        synchronized(reloads) { reloads += kind }
    }

    override fun observe(
        name: String,
        onNotification: () -> Unit,
    ): AutoCloseable {
        onRequest = onNotification
        return AutoCloseable { onRequest = null }
    }
}

class MacWidgetHostTest {
    init {
        NucleusWidgets.start { widget("Counter") { MacCounterWidget() } }
    }

    private val dir = Files.createTempDirectory("nucleus-widgets").toFile()
    private val environment = FakeEnvironment(dir)
    private val host = MacWidgetHost { environment }
    private val store = WidgetStore(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Does what the extension does: a request file, the notification, then the wait for the answer. */
    private fun request(vararg fields: Pair<String, String>): File {
        val id = "r${System.nanoTime()}"
        store.requests.mkdirs()
        WidgetStore.writeAtomically(store.requests.resolve("$id.json"), WidgetJson.write(mapOf("id" to id) + fields))
        environment.onRequest!!.invoke()
        val done = store.done.resolve(id)
        runBlocking { withTimeout(5_000) { while (!done.isFile) delay(10) } }
        return store.render("Counter", "systemSmall")
    }

    private fun started() {
        host.start()
        // The startup pass renders every pinned widget.
        runBlocking { withTimeout(5_000) { while (!store.render("Counter", "systemSmall").isFile) delay(10) } }
    }

    private fun state(): WidgetState = runBlocking { host.loadState(WidgetId("Counter/systemSmall")) }

    private fun signatureOf(label: String): Pair<String, String> {
        val tree = store.render("Counter", "systemSmall").readText()
        val match = Regex("\"k\":\"(a\\d+)\",\"sig\":\"(${Regex.escape(label)})\"").find(tree)!!
        return match.groupValues[1] to match.groupValues[2]
    }

    @Test
    fun startupRendersPinnedWidgetsAndGalleryPreviews() {
        started()
        val tree = store.render("Counter", "systemSmall").readText()
        assertTrue(tree.startsWith("{\"v\":1,\"root\":{\"t\":\"col\""), tree)
        // The content's column is the root: its background becomes the widget's.
        assertTrue(tree.endsWith("\"m\":{\"bg\":\"emphasis\"}}}"), tree)
        assertTrue("\"bg\":\"emphasis\"" in tree, tree)
        assertTrue("\"t\":\"toggle\",\"s\":\"Done\",\"on\":false,\"a\":{\"in\":{\"b:done\":\"true\"}}" in tree, tree)
        assertTrue(Regex("\"src\":\"images/data/[0-9a-f]{32}\\.png\"").containsMatchIn(tree), tree)
        assertFalse("Draft" in tree, tree)
        assertTrue(store.preview("Counter", "systemMedium").isFile)
        assertTrue("Counter" in environment.reloads)
    }

    @Test
    fun actionRunsAndPersistsState() {
        started()
        val (key, signature) = signatureOf("button:+1")
        request("type" to "action", "widget" to "Counter/systemSmall", "key" to key, "signature" to signature)
        assertEquals(1, state()[Count])
        assertTrue("{\"t\":\"text\",\"s\":\"1\"" in store.render("Counter", "systemSmall").readText())
        assertTrue(store.state("Counter", "systemSmall").readText().contains("\"i:count\":\"1\""))
    }

    @Test
    fun toggleWritesItsValueWithoutAnAction() {
        started()
        request("type" to "action", "widget" to "Counter/systemSmall", "inputs" to "{\"b:done\":\"true\"}")
        assertEquals(true, state()[Done])
    }

    @Test
    fun outdatedClickIsDroppedWhenItsKeyMoved() {
        started()
        val (key, signature) = signatureOf("button:+1")
        // The app adds a row meanwhile: the key the extension drew now names the row.
        runBlocking { host.loadState(WidgetId("Counter/systemSmall")) }[Items] = "milk"
        val dropped = mutableListOf<WidgetEvent>()
        val job = WidgetEngine.scope.launchCollect(dropped)
        request("type" to "action", "widget" to "Counter/systemSmall", "key" to key, "signature" to signature)
        job.cancel()
        assertEquals(null, state()[Count])
        assertEquals(null, state()[Removed])
        assertTrue(dropped.any { it is WidgetEvent.ActionDropped && it.key == key }, dropped.toString())
    }

    @Test
    fun firstRenderOfANewWidgetReportsItPinned() {
        started()
        val events = mutableListOf<WidgetEvent>()
        val job = WidgetEngine.scope.launchCollect(events)
        request("type" to "render", "widget" to "Counter/systemMedium")
        job.cancel()
        assertTrue(store.render("Counter", "systemMedium").isFile)
        assertTrue(events.any { it is WidgetEvent.Pinned && it.id.value == "Counter/systemMedium" }, events.toString())
    }

    @Test
    fun removedWidgetLosesItsStateAndRender() {
        started()
        request("type" to "action", "widget" to "Counter/systemSmall", "inputs" to "{\"b:done\":\"true\"}")
        environment.pinnedNow = emptyList()
        assertEquals(emptyList(), runBlocking { host.pinnedWidgets() })
        // One empty answer from WidgetKit is not a removal: the state is still there.
        assertTrue(store.state("Counter", "systemSmall").isFile)
        environment.offsetMillis = 31_000
        assertEquals(emptyList(), runBlocking { host.pinnedWidgets() })
        assertFalse(store.render("Counter", "systemSmall").isFile)
        assertFalse(store.state("Counter", "systemSmall").isFile)
        // Not on screen any more: an update brings nothing back.
        runBlocking { host.refresh(WidgetId("Counter/systemSmall")) }
        assertFalse(store.render("Counter", "systemSmall").isFile)
    }

    @Test
    fun widgetIdsFromRequestsCannotLeaveTheStore() {
        started()
        request("type" to "render", "widget" to "Counter/systemSmall/../../../escaped")
        request("type" to "render", "widget" to "../Counter/systemSmall")
        assertFalse(dir.parentFile.resolve("escaped.json").exists())
        assertFalse(dir.walkTopDown().any { "escaped" in it.name })
    }

    @Test
    fun launchMarkerMakesTheProcessHostOnly() {
        dir.mkdirs()
        dir.resolve("launched-by-widget").writeText("now")
        started()
        assertTrue(host.isLaunchedByHost)
        assertFalse(dir.resolve("launched-by-widget").exists())
    }
}

/** Collects the engine's events (replayed ones included) until cancelled. */
private fun CoroutineScope.launchCollect(into: MutableList<WidgetEvent>) =
    launch(Dispatchers.Unconfined) { NucleusWidgets.events.collect { synchronized(into) { into += it } } }

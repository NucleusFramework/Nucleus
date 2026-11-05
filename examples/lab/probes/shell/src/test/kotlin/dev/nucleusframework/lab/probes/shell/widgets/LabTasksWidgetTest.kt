@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.widget.ElementAction
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.WidgetElement
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives LabTasksWidget through the engine the way the widgets board does: render, click, render. */
class LabTasksWidgetTest {
    init {
        NucleusWidgets.start { widget(LAB_TASKS_WIDGET) { LabTasksWidget() } }
    }

    private fun instance(size: WidgetSize) =
        WidgetInstance(WidgetId("t-$size"), LAB_TASKS_WIDGET, size, WidgetState.decode(null))

    private fun WidgetInstance.tasks() = LabTasks.read(state)

    private fun elements(root: WidgetElement): Sequence<WidgetElement> =
        sequence {
            yield(root)
            when (root) {
                is WidgetElement.Column -> root.children.forEach { yieldAll(elements(it)) }
                is WidgetElement.Row -> root.children.forEach { yieldAll(elements(it)) }
                else -> Unit
            }
        }

    private suspend fun WidgetInstance.render() = elements(WidgetEngine.render(this).root).toList()

    private suspend fun WidgetInstance.buttons() = render().filterIsInstance<WidgetElement.Button>()

    /** Clicks the button labelled [text], with [inputs] as the card would submit them. */
    private suspend fun WidgetInstance.click(
        text: String,
        inputs: Map<String, String> = emptyMap(),
    ) {
        val button = buttons().first { it.text == text }
        val key = (button.action as ElementAction.Execute).key
        assertTrue(WidgetEngine.invokeAction(this, key, inputs))
    }

    /** Clicks the clickable row showing [title]: a task, a tab, "Clear completed". */
    private suspend fun WidgetInstance.clickRow(title: String) {
        val row =
            render().filterIsInstance<WidgetElement.Row>().first { row ->
                row.modifier.onClick != null && row.children.any { it is WidgetElement.Text && it.text == title }
            }
        val key = (row.modifier.onClick as ElementAction.Execute).key
        assertTrue(WidgetEngine.invokeAction(this, key))
    }

    private suspend fun WidgetInstance.texts() = render().filterIsInstance<WidgetElement.Text>().map { it.text }

    private suspend fun WidgetInstance.icons() =
        render().filterIsInstance<WidgetElement.Image>().map { it.provider.appResource to it.size }

    @Test
    fun addCheckFilterAndClear() =
        runBlocking {
            val widget = instance(WidgetSize.Large)
            assertTrue("Add your first task below." in widget.texts())
            assertTrue("No tasks" in widget.texts())

            widget.click("Add", mapOf("s:draft" to "  Ship the widget "))
            widget.click("Add", mapOf("s:draft" to "Write the docs"))
            widget.click("Add", mapOf("s:draft" to "   ")) // blank: ignored
            LabTasks.add(widget.state, "Split\r\nline")
            assertEquals(LabTask("Split line", false), widget.tasks().last(), "line breaks never split a task")
            LabTasks.write(widget.state, widget.tasks().dropLast(1))
            assertEquals(listOf(LabTask("Ship the widget", false), LabTask("Write the docs", false)), widget.tasks())
            assertNull(widget.state[LabTasks.DraftKey], "the field is cleared after Add")
            assertTrue("2 of 2 left" in widget.texts())
            assertFalse("Clear completed" in widget.texts(), "nothing to clear yet")

            // A row click goes through actionRunCallback(ToggleTask) and updateWidgetState.
            widget.clickRow("Ship the widget")
            assertEquals(listOf(true, false), widget.tasks().map { it.isDone })
            assertTrue("1 of 2 left" in widget.texts())
            assertEquals(
                listOf("widget/task-done.png" to ImageSize.Icon, "widget/task-open.png" to ImageSize.Icon),
                widget.icons(),
            )

            widget.clickRow("Done")
            assertTrue("Ship the widget" in widget.texts())
            assertFalse("Write the docs" in widget.texts())
            widget.clickRow("To do")
            assertEquals(listOf("widget/task-open.png" to ImageSize.Icon), widget.icons())

            widget.clickRow("All")
            widget.clickRow("Clear completed")
            assertEquals(listOf(LabTask("Write the docs", false)), widget.tasks())
            assertFalse("Clear completed" in widget.texts())
        }

    @Test
    fun smallShowsTheNextTaskAndMarksItDone() =
        runBlocking {
            val widget = instance(WidgetSize.Small)
            LabTasks.add(widget.state, "First")
            LabTasks.add(widget.state, "Second")
            assertTrue("First" in widget.texts())
            assertFalse(widget.buttons().any { it.text == "Add" }, "no field on Small")

            widget.click("Mark done")
            assertTrue("Second" in widget.texts())
            widget.click("Mark done")
            assertTrue("All done" in widget.texts())
            assertFalse(widget.buttons().any { it.text == "Mark done" })
        }

    @Test
    fun mediumCapsTheListAndCustomizationReordersAndHides() =
        runBlocking {
            val widget = instance(WidgetSize.Medium)
            listOf("a", "b", "c", "d", "e", "f").forEach { LabTasks.add(widget.state, it) }
            assertTrue("2 more" in widget.texts())
            assertFalse("All" in widget.texts(), "the tabs are Large only")

            widget.clickRow("a")
            widget.state[LabTasks.OrderKey] = "todoFirst"
            assertEquals(listOf("b", "c", "d", "e"), widget.texts().filter { it.length == 1 })

            widget.state[LabTasks.HideDoneKey] = true
            widget.state[LabTasks.OrderKey] = "added"
            assertFalse("a" in widget.texts())
            assertTrue("1 more" in widget.texts())
        }
}

package dev.nucleusframework.lab.probes.shell

import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.probes.shell.badge.BadgeEvent
import dev.nucleusframework.lab.probes.shell.badge.BadgeReducer
import dev.nucleusframework.lab.probes.shell.badge.BadgeState
import dev.nucleusframework.lab.probes.shell.dock.DockItems
import dev.nucleusframework.lab.probes.shell.dock.DockMenuEvent
import dev.nucleusframework.lab.probes.shell.dock.DockMenuReducer
import dev.nucleusframework.lab.probes.shell.dock.DockMenuState
import dev.nucleusframework.lab.probes.shell.hotkey.Combos
import dev.nucleusframework.lab.probes.shell.hotkey.HotKeyEvent
import dev.nucleusframework.lab.probes.shell.hotkey.HotKeyReducer
import dev.nucleusframework.lab.probes.shell.hotkey.HotKeyState
import dev.nucleusframework.lab.probes.shell.taskbar.TaskbarEvent
import dev.nucleusframework.lab.probes.shell.taskbar.TaskbarReducer
import dev.nucleusframework.lab.probes.shell.taskbar.TaskbarState
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellReducersTest {
    private val delivery = Delivery(0, "x", "main", true)

    @Test
    fun `a failed badge call keeps the last applied value but is logged`() {
        val applied = BadgeReducer.reduce(BadgeState(), BadgeEvent.Called("setCount(5)", "5", CallOutcome.Ok))
        val failed = BadgeReducer.reduce(applied, BadgeEvent.Called("setCount(9)", "9", CallOutcome(false, "boom")))
        assertEquals("5", failed.requested)
        assertEquals(2, failed.calls.size)
        assertEquals(
            false,
            failed.calls
                .last()
                .outcome.ok,
        )
    }

    @Test
    fun `clearing the badge forgets the requested value`() {
        val applied =
            BadgeReducer.reduce(
                BadgeState(requested = "5"),
                BadgeEvent.Called("clear()", null, CallOutcome.Ok),
            )
        assertNull(applied.requested)
    }

    @Test
    fun `focus ends a pending attention request`() {
        val pending =
            TaskbarReducer.reduce(
                TaskbarState(),
                TaskbarEvent.AttentionApplied(TaskbarProgress.AttentionType.CRITICAL, CallOutcome.Ok),
            )
        assertEquals(TaskbarProgress.AttentionType.CRITICAL, pending.attention)
        val blurred = TaskbarReducer.reduce(pending, TaskbarEvent.FocusChanged(false))
        assertEquals(TaskbarProgress.AttentionType.CRITICAL, blurred.attention)
        assertNull(TaskbarReducer.reduce(blurred, TaskbarEvent.FocusChanged(true)).attention)
    }

    @Test
    fun `sweep steps do not flood the call log`() {
        val sweeping = TaskbarState(sweeping = true)
        val stepped =
            (1..50).fold(
                sweeping,
            ) { s, i -> TaskbarReducer.reduce(s, TaskbarEvent.ValueApplied(i / 50.0, CallOutcome.Ok)) }
        assertTrue(stepped.calls.isEmpty())
        assertEquals(1.0, stepped.shownValue)
    }

    @Test
    fun `a refused hotkey is logged but not registered, presses are counted per handle`() {
        val combo = Combos.first()
        val refused =
            HotKeyReducer.reduce(
                HotKeyState(),
                HotKeyEvent.RegisterResult(combo, null, null, CallOutcome(false, "taken")),
            )
        assertTrue(refused.registrations.isEmpty())
        assertEquals(1, refused.calls.size)

        val registered = HotKeyReducer.reduce(refused, HotKeyEvent.RegisterResult(combo, 7, null, CallOutcome.Ok))
        val pressed = (1..3).fold(registered) { s, _ -> HotKeyReducer.reduce(s, HotKeyEvent.Pressed(7, delivery)) }
        assertEquals(3, pressed.registrations.single().presses)
        assertEquals(3, pressed.presses.size)
    }

    @Test
    fun `the dock counter item counts its own clicks only`() {
        val state =
            listOf(DockItems.COUNTER, DockItems.PING, DockItems.COUNTER).fold(DockMenuState()) { s, id ->
                DockMenuReducer.reduce(s, DockMenuEvent.Clicked(id, delivery))
            }
        assertEquals(2, state.counter)
        assertEquals("Counter: 2", DockItems.build(state.counter)[1].title)
    }
}

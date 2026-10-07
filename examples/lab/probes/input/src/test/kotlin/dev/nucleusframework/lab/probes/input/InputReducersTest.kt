package dev.nucleusframework.lab.probes.input

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.lab.probes.input.focus.FocusEvent
import dev.nucleusframework.lab.probes.input.focus.FocusReducer
import dev.nucleusframework.lab.probes.input.focus.FocusState
import dev.nucleusframework.lab.probes.input.focus.isSequentialOrder
import dev.nucleusframework.lab.probes.input.gestures.GesturesEvent
import dev.nucleusframework.lab.probes.input.gestures.GesturesReducer
import dev.nucleusframework.lab.probes.input.gestures.GesturesState
import dev.nucleusframework.lab.probes.input.keyboard.KeyRecord
import dev.nucleusframework.lab.probes.input.keyboard.KeyboardEvent
import dev.nucleusframework.lab.probes.input.keyboard.KeyboardReducer
import dev.nucleusframework.lab.probes.input.keyboard.KeyboardState
import dev.nucleusframework.lab.probes.input.keyboard.TextSnapshot
import dev.nucleusframework.lab.probes.input.pointer.PointerEvent
import dev.nucleusframework.lab.probes.input.pointer.PointerKind
import dev.nucleusframework.lab.probes.input.pointer.PointerReducer
import dev.nucleusframework.lab.probes.input.pointer.PointerSample
import dev.nucleusframework.lab.probes.input.pointer.PointerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputReducersTest {
    private fun pointer(
        kind: PointerKind,
        at: Long,
        x: Float = 10f,
        inside: Boolean = true,
    ) = PointerEvent.Sampled(
        PointerSample(
            kind = kind,
            nowMs = at,
            position = Offset(x, 10f),
            inside = inside,
            buttons = "none",
            button = if (kind == PointerKind.Press) "primary" else null,
            modifiers = "—",
            pointerType = "Mouse",
        ),
    )

    @Test
    fun `fast close presses count as a triple click, a far one resets`() {
        var s = PointerState()
        s = PointerReducer.reduce(s, pointer(PointerKind.Press, 0))
        s = PointerReducer.reduce(s, pointer(PointerKind.Press, 200, x = 12f))
        s = PointerReducer.reduce(s, pointer(PointerKind.Press, 400))
        assertEquals(3, s.lastClickCount)
        s = PointerReducer.reduce(s, pointer(PointerKind.Press, 500, x = 80f))
        assertEquals(1, s.lastClickCount)
        s = PointerReducer.reduce(s, pointer(PointerKind.Press, 2000, x = 80f))
        assertEquals(1, s.lastClickCount)
        assertEquals(3, s.maxClickCount)
        assertEquals(5, s.presses["primary"])
    }

    @Test
    fun `an exit reported well inside the target is phantom`() {
        var s = PointerReducer.reduce(PointerState(), pointer(PointerKind.Enter, 0))
        s = PointerReducer.reduce(s, pointer(PointerKind.Exit, 10, inside = true))
        s = PointerReducer.reduce(s, pointer(PointerKind.Enter, 20))
        s = PointerReducer.reduce(s, pointer(PointerKind.Exit, 30, inside = false))
        assertEquals(2, s.exits)
        assertEquals(1, s.phantomExits)
        assertFalse(s.hovered)
    }

    private fun key(
        down: Boolean,
        code: Long,
    ) = KeyboardEvent.Key(KeyRecord(down, "K$code", code, 0, "—"))

    @Test
    fun `held keys clear on release and repeats are counted`() {
        var s = KeyboardState()
        s = KeyboardReducer.reduce(s, key(true, 1))
        s = KeyboardReducer.reduce(s, key(true, 1))
        s = KeyboardReducer.reduce(s, key(true, 2))
        assertEquals(setOf(1L, 2L), s.held.keys)
        assertEquals(1, s.repeats)
        s = KeyboardReducer.reduce(s, key(false, 1))
        s = KeyboardReducer.reduce(s, key(false, 2))
        assertTrue(s.held.isEmpty())
    }

    @Test
    fun `marked text going away is reported as the committed text`() {
        var s = KeyboardState(text = TextSnapshot("ab", 2..2, null))
        // Dead key: ´ marked at 2, then e commits é.
        s = KeyboardReducer.reduce(s, KeyboardEvent.Text(TextSnapshot("ab´", 3..3, 2 until 3)))
        s = KeyboardReducer.reduce(s, KeyboardEvent.Text(TextSnapshot("abé", 3..3, null)))
        assertEquals(listOf("committed 'é'"), s.commits)
        assertEquals(1, s.compositionUpdates)
    }

    @Test
    fun `focus order must walk neighbours`() {
        assertTrue(isSequentialOrder(listOf("Name", "Email", "Subscribe", "Email")))
        assertFalse(isSequentialOrder(listOf("Name", "Notes")))
    }

    @Test
    fun `focus coming back to another target after reactivation is a restore failure`() {
        var s = FocusReducer.reduce(FocusState(windowFocused = true), FocusEvent.Gained("Notes"))
        s = FocusReducer.reduce(s, FocusEvent.WindowFocus(false))
        s = FocusReducer.reduce(s, FocusEvent.Lost("Notes"))
        s = FocusReducer.reduce(s, FocusEvent.WindowFocus(true))
        s = FocusReducer.reduce(s, FocusEvent.Gained("Name"))
        assertEquals(1, s.restoreFailures)
    }

    @Test
    fun `scale while touch contacts are down is an overlap and bad factors are rejected`() {
        var s = GesturesReducer.reduce(GesturesState(), GesturesEvent.ScaleStarted(0, touchContacts = 0))
        s = GesturesReducer.reduce(s, GesturesEvent.ScaleChanged(2f, touchContacts = 0, applies = true))
        s =
            GesturesReducer.reduce(
                s,
                GesturesEvent.ScaleChanged(Float.POSITIVE_INFINITY, touchContacts = 0, applies = true),
            )
        s = GesturesReducer.reduce(s, GesturesEvent.ScaleChanged(1.5f, touchContacts = 2, applies = true))
        s = GesturesReducer.reduce(s, GesturesEvent.ScaleEnded(100))
        assertEquals(1, s.overlaps)
        assertEquals(1, s.invalidFactors)
        assertEquals(3f, s.transform.scale)
        assertEquals(3f, s.summaries.single().factor)
    }
}

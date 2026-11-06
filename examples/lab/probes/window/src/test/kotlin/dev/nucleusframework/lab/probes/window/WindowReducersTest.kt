package dev.nucleusframework.lab.probes.window

import androidx.compose.ui.unit.IntRect
import dev.nucleusframework.lab.probes.window.dialogs.DialogsEvent
import dev.nucleusframework.lab.probes.window.dialogs.DialogsReducer
import dev.nucleusframework.lab.probes.window.dialogs.DialogsState
import dev.nucleusframework.lab.probes.window.dialogs.Secondary
import dev.nucleusframework.lab.probes.window.overlays.OverlaysEvent
import dev.nucleusframework.lab.probes.window.overlays.OverlaysReducer
import dev.nucleusframework.lab.probes.window.overlays.OverlaysState
import dev.nucleusframework.lab.probes.window.overlays.WatermarkConfig
import dev.nucleusframework.lab.probes.window.popups.within
import dev.nucleusframework.lab.probes.window.theming.StyleReport
import dev.nucleusframework.lab.probes.window.theming.ThemeChoice
import dev.nucleusframework.lab.probes.window.theming.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowReducersTest {
    @Test
    fun `a press is only a click-through leak while click-through is on`() {
        val through = OverlaysReducer.reduce(OverlaysState(), OverlaysEvent.WatermarkPressed)
        assertEquals(1, through.watermarkPressesWhileClickThrough)
        val clickable = OverlaysState(watermark = WatermarkConfig(clickThrough = false))
        val after = OverlaysReducer.reduce(clickable, OverlaysEvent.WatermarkPressed)
        assertEquals(1, after.watermarkPresses)
        assertEquals(0, after.watermarkPressesWhileClickThrough)
    }

    @Test
    fun `owner presses count against modality only while a modal surface is open`() {
        val windowOnly = DialogsReducer.reduce(DialogsState(), DialogsEvent.Shown(Secondary.HostedWindowDefault))
        assertEquals(0, DialogsReducer.reduce(windowOnly, DialogsEvent.OwnerPressed).ownerPressesWhileModal)
        val modal = DialogsReducer.reduce(windowOnly, DialogsEvent.Shown(Secondary.DecoratedDialog))
        assertEquals(1, DialogsReducer.reduce(modal, DialogsEvent.OwnerPressed).ownerPressesWhileModal)
    }

    @Test
    fun `closing the owner drops every open surface`() {
        val open = DialogsReducer.reduce(DialogsState(sessionOpen = true), DialogsEvent.Shown(Secondary.MaterialDialog))
        assertTrue(DialogsReducer.reduce(open, DialogsEvent.SessionChanged(false)).open.isEmpty())
    }

    @Test
    fun `a popup crossing the work area edge is not within it`() {
        val work = IntRect(0, 0, 1920, 1040)
        assertTrue(IntRect(1500, 700, 1900, 1000).within(work))
        assertFalse(IntRect(1500, 900, 1900, 1200).within(work))
    }

    @Test
    fun `theme readback is judged only against an explicit choice`() {
        val dark = StyleReport(true, "", "", "", "", "")
        assertTrue(dark.matches(ThemeChoice.Dark)!!)
        assertFalse(dark.matches(ThemeChoice.Light)!!)
        assertNull(dark.matches(ThemeChoice.System))
    }
}

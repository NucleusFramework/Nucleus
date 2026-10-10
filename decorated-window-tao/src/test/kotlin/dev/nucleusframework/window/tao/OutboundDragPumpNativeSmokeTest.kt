package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the three `nativeStartDrag` JNI signatures against one-sided drift.
 *
 * The outbound-drag pump added a `DragPump` parameter to the macOS, Windows and
 * Linux bridges alike. Dropping or reordering it on one side of the boundary
 * still compiles and still links the library — it only fails when
 * `beginDraggingSession` / `DoDragDrop` / `gtk_drag_begin_with_coordinates` is
 * actually reached, as an `UnsatisfiedLinkError` thrown from inside the user's
 * drag gesture, i.e. on the exact path the pump exists to keep alive. Resolving
 * the symbol here turns that into a build failure instead.
 *
 * A null window handle makes every implementation bail out with
 * `DROP_EFFECT_NONE` before it touches the OS (or GTK), so this is safe from the
 * off-main test worker — `nativeRegister` and a real drag session are not (see
 * [StandalonePanelNativeSmokeTest] for that constraint).
 */
class OutboundDragPumpNativeSmokeTest {
    private object MacOsPump : NativeTaoMacOsDndBridge.DragPump {
        override fun pump() = Unit
    }

    private object WindowsPump : NativeTaoWindowsDndBridge.DragPump {
        override fun pump() = Unit
    }

    private object LinuxPump : NativeTaoLinuxDndBridge.DragPump {
        override fun pump() = Unit
    }

    @Test
    fun macOsStartDragLinksWithThePumpParameter() {
        if (!os().contains("mac")) return

        assertTrue(NativeTaoMacOsDndBridge.isLoaded, "nucleus_tao_dnd failed to load")
        assertEquals(
            NativeTaoMacOsDndBridge.DROP_EFFECT_NONE,
            NativeTaoMacOsDndBridge.nativeStartDrag(
                nsView = 0L,
                files = null,
                text = null,
                allowedEffects = NativeTaoMacOsDndBridge.DROP_EFFECT_COPY,
                pump = MacOsPump,
            ),
        )
    }

    @Test
    fun windowsStartDragLinksWithThePumpParameter() {
        if (!os().contains("win")) return

        assertTrue(NativeTaoWindowsDndBridge.isLoaded, "nucleus_tao_dnd failed to load")
        assertEquals(
            NativeTaoWindowsDndBridge.DROP_EFFECT_NONE,
            NativeTaoWindowsDndBridge.nativeStartDrag(
                hwnd = 0L,
                files = null,
                text = null,
                allowedEffects = NativeTaoWindowsDndBridge.DROP_EFFECT_COPY,
                pump = WindowsPump,
            ),
        )
    }

    @Test
    fun linuxStartDragLinksWithThePumpParameter() {
        if (!os().contains("linux")) return

        assertTrue(NativeTaoLinuxDndBridge.isLoaded, "nucleus_tao failed to load")
        assertEquals(
            NativeTaoLinuxDndBridge.DROP_EFFECT_NONE,
            NativeTaoLinuxDndBridge.nativeStartDrag(
                handle = 0L,
                files = null,
                text = null,
                privateData = null,
                allowedEffects = NativeTaoLinuxDndBridge.DROP_EFFECT_COPY,
                iconArgb = null,
                iconWidth = 0,
                iconHeight = 0,
                iconScale = 1f,
                iconHotX = 0,
                iconHotY = 0,
                pump = LinuxPump,
            ),
        )
    }

    private fun os() = System.getProperty("os.name", "").lowercase()
}

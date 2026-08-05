package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the two `nativeStartDrag` JNI signatures against one-sided drift.
 *
 * The outbound-drag pump added a `DragPump` parameter to both the macOS and the
 * Windows bridge. Dropping or reordering it on one side of the boundary still
 * compiles and still links the library — it only fails when
 * `beginDraggingSession` / `DoDragDrop` is actually reached, as an
 * `UnsatisfiedLinkError` thrown from inside the user's drag gesture, i.e. on the
 * exact path the pump exists to keep alive. Resolving the symbol here turns that
 * into a build failure instead.
 *
 * A null window handle makes both implementations bail out with
 * `DROP_EFFECT_NONE` before they touch the OS, so this is safe from the off-main
 * test worker — `nativeRegister` and a real drag session are not (see
 * [StandalonePanelNativeSmokeTest] for that constraint).
 */
class OutboundDragPumpNativeSmokeTest {
    private object MacOsPump : NativeTaoMacOsDndBridge.DragPump {
        override fun pump() = Unit
    }

    private object WindowsPump : NativeTaoWindowsDndBridge.DragPump {
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

    private fun os() = System.getProperty("os.name", "").lowercase()
}

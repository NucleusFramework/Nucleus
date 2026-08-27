package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoGlBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeWindows
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the native chain behind the standalone popup panel without any
 * Tao event loop: ownerless panel creation, surface attach and Skia
 * DirectContext creation. Windows-only — see [StandalonePanelMacSmokeMain]
 * for the macOS equivalent (AppKit requires the NSPanel be created on the
 * main thread, so it runs as a `main()` via the `smokeStandalonePanelMac`
 * JavaExec task, not as a JUnit test in the off-main test worker).
 */
class StandalonePanelNativeSmokeTest {
    @Test
    fun standalonePanelPipelineInitializes() {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return

        assertTrue(NativeTaoGlBridge.isLoaded, "nucleus_tao_gl failed to load")
        assertTrue(PopupNativeBridgeWindows.isLoaded, "nucleus_tao_windows_native_view failed to load")
        assertTrue(
            NativeTaoGlBridge.nativeEnsureHeadlessContext(),
            "headless EGL context bootstrap failed",
        )

        val panel =
            PopupNativeBridgeWindows.nativeCreatePanel(
                parentHwnd = 0L,
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        assertNotEquals(0L, panel, "ownerless panel creation failed")

        try {
            assertTrue(
                PopupNativeBridgeWindows.nativeMakeCurrent(panel),
                "nativeMakeCurrent failed on standalone panel",
            )
            val intf =
                GLAssembledInterface.createFromNativePointers(
                    0L,
                    NativeTaoGlBridge.nativeEglGetProcFn(),
                )
            val ctx = DirectContext.makeGLWithInterface(intf)
            ctx.close()

            // Ownerless tray panels must accept RegisterDragDrop: TrayApp hosts
            // Compose in this HWND, not a DecoratedWindow, so inbound file drops
            // never reached Modifier.dragAndDropTarget without this path.
            assertTrue(NativeTaoWindowsDndBridge.isLoaded, "nucleus_tao_dnd failed to load")
            val hwnd = PopupNativeBridgeWindows.nativeContentHwnd(panel)
            assertNotEquals(0L, hwnd, "standalone panel HWND is 0")
            val rc = NativeTaoWindowsDndBridge.nativeRegister(hwnd, NoOpInboundDnDCallback())
            assertEquals(0, rc, "RegisterDragDrop on standalone panel failed (rc=$rc)")
            NativeTaoWindowsDndBridge.nativeRevoke(hwnd)
        } finally {
            PopupNativeBridgeWindows.nativeRelease(panel)
        }
    }
}

/**
 * Named class (not a lambda) so [NativeTaoWindowsDndBridge.nativeRegister]'s
 * `GetMethodID` lookup succeeds — same GraalVM JNI constraint as production
 * inbound callbacks.
 */
private class NoOpInboundDnDCallback : NativeTaoWindowsDndBridge.Callback {
    override fun onDragEnter(
        hwnd: Long,
        x: Int,
        y: Int,
        keyState: Int,
        hasFiles: Boolean,
    ): Int = NativeTaoWindowsDndBridge.DROP_EFFECT_NONE

    override fun onDragOver(
        hwnd: Long,
        x: Int,
        y: Int,
        keyState: Int,
        hasFiles: Boolean,
    ): Int = NativeTaoWindowsDndBridge.DROP_EFFECT_NONE

    override fun onDragLeave(hwnd: Long) = Unit

    override fun onDrop(
        hwnd: Long,
        x: Int,
        y: Int,
        keyState: Int,
        files: Array<String>?,
    ): Int = NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
}

package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.WindowsBackdrop
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

/**
 * #631 — `alwaysOnTop` must keep the HWND in the topmost band for the life of
 * the window: through the acrylic backdrop apply/teardown, through size
 * applies, and through explicit toggles. Probes the real `WS_EX_TOPMOST` bit
 * via [NativeTaoWindowsDecoBridge.nativeIsTopmost] instead of Kotlin caches
 * (which is exactly what went stale in the issue).
 */
internal object AlwaysOnTopHeadfulCases {
    private val isWindows: Boolean = Platform.Current == Platform.Windows

    fun all(): List<TaoWindowTestCase> = listOf(alwaysOnTopSticksThroughStyleRewrites())

    @Suppress("LongMethod")
    private fun alwaysOnTopSticksThroughStyleRewrites(): TaoWindowTestCase {
        val acrylic = mutableStateOf(false)
        val windowState =
            WindowState(
                position = WindowPosition.Aligned(Alignment.Center),
                size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            )
        return TaoWindowTestCase(
            name = "#631 alwaysOnTop sticks through acrylic and size rewrites",
            skip = { if (!isWindows) "Windows-only z-order probe" else null },
            windowState = windowState,
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            content = {
                if (acrylic.value) WindowsBackdrop(WindowsBackdropStyle.Acrylic)
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
                check(hwnd != 0L) { "no HWND" }

                fun topmost() = NativeTaoWindowsDecoBridge.nativeIsTopmost(hwnd)

                window.setAlwaysOnTop(true)
                awaitUntil("WS_EX_TOPMOST after setAlwaysOnTop(true)") { topmost() }

                // Acrylic apply rewrites the DWM/frame state (#631 clobber #1).
                acrylic.value = true
                awaitUntil("backdrop active") {
                    NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)
                }
                settle(SETTLE_AFTER_REWRITE_MILLIS)
                check(topmost()) { "WS_EX_TOPMOST dropped by the acrylic apply (#631)" }

                // Size apply goes through tao's SetWindowPos path (#631 clobber #2).
                windowState.size = DpSize(RESIZED_W_DP.dp, RESIZED_H_DP.dp)
                settle(SETTLE_AFTER_REWRITE_MILLIS)
                check(topmost()) { "WS_EX_TOPMOST dropped by the size apply (#631)" }

                // Explicit toggles must be effective both ways (issue steps 2-3).
                window.setAlwaysOnTop(false)
                awaitUntil("WS_EX_TOPMOST cleared after setAlwaysOnTop(false)") { !topmost() }
                window.setAlwaysOnTop(true)
                awaitUntil("WS_EX_TOPMOST restored after re-enable") { topmost() }

                // Backdrop teardown is a style rewrite too.
                acrylic.value = false
                awaitUntil("backdrop gone") {
                    !NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)
                }
                settle(SETTLE_AFTER_REWRITE_MILLIS)
                check(topmost()) { "WS_EX_TOPMOST dropped by the backdrop teardown (#631)" }
            },
        )
    }

    private const val WINDOW_W_DP = 320
    private const val WINDOW_H_DP = 200
    private const val RESIZED_W_DP = 260
    private const val RESIZED_H_DP = 120
    private const val SETTLE_AFTER_REWRITE_MILLIS = 300L
}

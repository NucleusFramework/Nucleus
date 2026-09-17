package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalRequestedAppearanceOverride
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Forces the appearance of this window's native surfaces — glass regions and
 * system materials, traffic lights, native menus and popups.
 *
 * Apps that let the user pick their own theme need this: native surfaces
 * otherwise follow the OS setting, so an app running dark on a light system
 * gets a light sidebar material under dark Compose content (and vice versa).
 * Pass the same value that drives the Compose theme.
 *
 * On macOS this drives `NSWindow.appearance` (Tao backend). On Windows it
 * drives the Compose-drawn chrome — caption-button glyphs and hover overlays —
 * which otherwise follows the window background's luminance, the same signal
 * the DWM material uses. A no-op on Linux. The window reverts to the
 * system-derived appearance when the composable leaves the composition.
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowAppearance(mode: WindowAppearanceMode) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoWindow = (this as TaoDecoratedWindowScope).window
    val chromeOverride = LocalRequestedAppearanceOverride.current
    DisposableEffect(taoWindow, mode) {
        chromeOverride?.value = mode
        val supported = Platform.Current == Platform.MacOS && NativeMetalBridge.isLoaded
        if (supported) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L) {
                NativeMetalBridge.nativeSetWindowAppearance(nsView, mode.nativeValue)
            }
        }
        onDispose {
            // Hand the window back to the derived appearance: a forced one
            // that outlived its call site would keep native menus, popups,
            // the traffic-lights and every glass region dark under light
            // content.
            chromeOverride?.value = WindowAppearanceMode.System
            if (supported && mode != WindowAppearanceMode.System) {
                val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
                if (nsView != 0L) {
                    NativeMetalBridge.nativeSetWindowAppearance(
                        nsView,
                        WindowAppearanceMode.System.nativeValue,
                    )
                }
            }
        }
    }
}

package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Controls the placement direction of window control buttons (close, minimize, maximize)
 * independently of the title bar content direction.
 *
 * - [Auto]: follows `LocalLayoutDirection` from Compose (previous default behavior).
 * - [System]: follows the native OS layout direction via JNI, with a JVM-level fallback.
 * - [SystemNative]: follows the native OS locale detected via JNI, ignoring any
 *   `Locale.setDefault()` override applied at runtime.
 * - [Ltr]: always place buttons as in a left-to-right layout (trailing = right side).
 * - [Rtl]: always place buttons as in a right-to-left layout (trailing = left side).
 */
public enum class ControlButtonsDirection {
    Auto,
    System,
    SystemNative,
    Ltr,
    Rtl,
    ;

    @Composable
    public fun resolve(): LayoutDirection =
        when (this) {
            Auto -> LocalLayoutDirection.current
            System, SystemNative -> nativeSystemLayoutDirection()
            Ltr -> LayoutDirection.Ltr
            Rtl -> LayoutDirection.Rtl
        }
}

/**
 * Returns the layout direction from the native OS via JNI.
 *
 * On macOS, queries `NSApplication.userInterfaceLayoutDirection`.
 * On Windows, queries `GetLocaleInfoEx` with `LOCALE_IREADINGLAYOUT`.
 * On Linux (or if the native library is unavailable), falls back to checking
 * `user.language` system property against known RTL languages.
 */
public fun nativeSystemLayoutDirection(): LayoutDirection =
    if (NativeLayoutDirectionBridge.nativeIsRTL()) LayoutDirection.Rtl else LayoutDirection.Ltr

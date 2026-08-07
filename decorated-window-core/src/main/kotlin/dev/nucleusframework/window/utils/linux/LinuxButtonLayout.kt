package dev.nucleusframework.window.utils.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.window.NativeLayoutDirectionBridge
import java.util.function.Consumer

/**
 * Returns true if the current desktop session is GNOME.
 * Used to decide whether to read GNOME GSettings for button layout.
 */
private fun isGnomeDesktop(): Boolean = LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.Gnome

/**
 * Represents a titlebar button type from the GNOME `button-layout` GSettings key.
 */
public enum class LinuxTitleBarButton {
    CLOSE,
    MINIMIZE,
    MAXIMIZE,
}

/**
 * Parsed representation of the GNOME `org.gnome.desktop.wm.preferences` → `button-layout` value.
 *
 * @property buttons Ordered list of buttons to display (edge-first: first element is closest to the window edge).
 * @property controlsOnRight `true` if buttons are placed on the right side of the titlebar.
 */
public data class LinuxButtonLayout(
    val buttons: List<LinuxTitleBarButton>,
    val controlsOnRight: Boolean,
) {
    val hasClose: Boolean get() = LinuxTitleBarButton.CLOSE in buttons
    val hasMinimize: Boolean get() = LinuxTitleBarButton.MINIMIZE in buttons
    val hasMaximize: Boolean get() = LinuxTitleBarButton.MAXIMIZE in buttons

    public companion object {
        /** Default layout: close on the right (GNOME default). */
        public val Default: LinuxButtonLayout =
            LinuxButtonLayout(
                buttons =
                    listOf(
                        LinuxTitleBarButton.CLOSE,
                        LinuxTitleBarButton.MAXIMIZE,
                        LinuxTitleBarButton.MINIMIZE,
                    ),
                controlsOnRight = true,
            )

        /**
         * Reads the current system button layout (one-shot).
         * Falls back to [Default] if not on GNOME, GSettings is unavailable,
         * or returns no buttons.
         */
        public fun readSystem(): LinuxButtonLayout =
            try {
                if (!isGnomeDesktop()) {
                    return Default
                }
                val raw = NativeLayoutDirectionBridge.nativeGetButtonLayout()
                if (raw != null) parse(raw) else Default
            } catch (_: UnsatisfiedLinkError) {
                Default
            }

        /**
         * Parses a GNOME `button-layout` string like `"appmenu:minimize,maximize,close"`
         * or `"close,minimize,maximize:"`.
         *
         * The colon separates left and right sides. The side containing `close`
         * determines where control buttons are placed.
         */
        internal fun parse(raw: String): LinuxButtonLayout {
            val colonIndex = raw.indexOf(':')
            val (left, right) =
                if (colonIndex >= 0) {
                    raw.substring(0, colonIndex) to raw.substring(colonIndex + 1)
                } else {
                    "" to raw
                }

            val leftButtons = parseButtons(left)
            val rightButtons = parseButtons(right)

            val controlsOnRight =
                LinuxTitleBarButton.CLOSE in rightButtons ||
                    LinuxTitleBarButton.CLOSE !in leftButtons

            return if (controlsOnRight) {
                LinuxButtonLayout(
                    buttons = rightButtons.reversed(),
                    controlsOnRight = true,
                )
            } else {
                LinuxButtonLayout(
                    buttons = leftButtons,
                    controlsOnRight = false,
                )
            }
        }

        private fun parseButtons(side: String): List<LinuxTitleBarButton> =
            side
                .split(',')
                .mapNotNull { token ->
                    when (token.trim()) {
                        "close" -> LinuxTitleBarButton.CLOSE
                        "minimize" -> LinuxTitleBarButton.MINIMIZE
                        "maximize" -> LinuxTitleBarButton.MAXIMIZE
                        else -> null
                    }
                }
    }
}

/**
 * Singleton that starts the GSettings observer and keeps the button layout
 * up to date. Accessed from `WindowControlArea` to render the correct buttons.
 * On non-GNOME desktops, the observer is not started and [Default] layout is used.
 */
internal object LinuxButtonLayoutObserver {
    init {
        if (isGnomeDesktop()) {
            try {
                NativeLayoutDirectionBridge.nativeStartButtonLayoutObserving()
            } catch (_: UnsatisfiedLinkError) {
                // Native lib not available — monitoring won't work, layout stays at initial value
            }
        }
    }

    fun registerListener(listener: Consumer<String>) {
        if (!isGnomeDesktop()) return
        NativeLayoutDirectionBridge.registerButtonLayoutListener(listener)
    }

    fun removeListener(listener: Consumer<String>) {
        NativeLayoutDirectionBridge.removeButtonLayoutListener(listener)
    }
}

/**
 * Composable that returns the current system button layout,
 * updating automatically when the user changes it in GNOME Tweaks.
 */
@Composable
public fun rememberLinuxButtonLayout(): LinuxButtonLayout {
    val layoutState = remember { mutableStateOf(LinuxButtonLayout.readSystem()) }

    DisposableEffect(Unit) {
        val listener =
            Consumer<String> { raw ->
                layoutState.value = LinuxButtonLayout.parse(raw)
            }
        LinuxButtonLayoutObserver.registerListener(listener)
        onDispose {
            LinuxButtonLayoutObserver.removeListener(listener)
        }
    }

    return layoutState.value
}

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import kotlin.internal.LowPriorityInOverloadResolution
import dev.nucleusframework.application.DecoratedWindow as NucleusDecoratedWindowFn

private const val LUMINANCE_THRESHOLD = 0.5f

/** AWT-backed (JBR / JNI) Jewel-styled wrapper for [DecoratedWindow]. */
@Suppress("FunctionNaming", "LongParameterList")
// Low priority: NucleusApplicationScope implements ApplicationScope, so inside
// nucleusApplication both overloads are applicable — the Nucleus one must win.
@LowPriorityInOverloadResolution
@Composable
public fun ApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable AwtDecoratedWindowScope.() -> Unit,
) {
    val colorScheme = JewelTheme.globalColors
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()

    val titleBarIsDark = jewelTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    NucleusDecoratedWindowTheme(
        isDark = titleBarIsDark,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle ?: jewelTitleBarStyle,
    ) {
        DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
        ) {
            ProvideJewelSpellcheckMenu { content() }
        }
    }
}

/**
 * Backend-agnostic Jewel-styled wrapper. Use inside `nucleusApplication { … }`
 * — works on AWT (JBR/JNI) and Tao with the same call site. The Tao
 * `ComposeScene` boundary is handled by re-providing the resolved styles
 * inside the new scene.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    // Fully borderless window (no macOS traffic lights) — for overlay/ghost windows.
    undecorated: Boolean = false,
    // Linux/Tao only: popup overlay of [popupFor] — on Wayland a wl_subsurface
    // of the parent, the only client-positionable window kind under xdg-shell
    // (parent-relative coordinates). For drag ghosts. Ignored elsewhere.
    popupFor: NucleusWindow? = null,
    // Replace Compose-drawn context menus with the OS-looking menu. Tao +
    // macOS (`NSMenu`), or a Compose flyout on Linux (Adwaita) / Windows
    // (Fluent). No-op on AWT.
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (Tao backend; on Linux effective on X11/XWayland only).
    // No-op on AWT.
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()
    val resolvedTitleBarStyle = titleBarStyle ?: jewelTitleBarStyle
    val titleBarIsDark = resolvedTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    NucleusDecoratedWindowTheme(
        isDark = titleBarIsDark,
        windowStyle = windowStyle,
        titleBarStyle = resolvedTitleBarStyle,
    ) {
        NucleusDecoratedWindowFn(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            undecorated = undecorated,
            popupFor = popupFor,
            nativeContextMenu = nativeContextMenu,
            hiddenFromDock = hiddenFromDock,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
        ) {
            ProvideJewelSpellcheckMenu { content() }
        }
    }
}

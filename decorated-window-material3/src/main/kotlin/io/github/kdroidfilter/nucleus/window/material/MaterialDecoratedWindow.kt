package io.github.kdroidfilter.nucleus.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.window.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.tao.ApplicationScope as TaoApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.MacOSStyle
import io.github.kdroidfilter.nucleus.window.tao.DecoratedWindow as TaoDecoratedWindow

/**
 * Material 3 wrapper around the AWT-based `DecoratedWindow` (JBR / JNI
 * backends). Picks Material colors via [rememberMaterialTitleBarStyle] and
 * wraps with [NucleusDecoratedWindowTheme].
 *
 * Use the [TaoApplicationScope] overload below when running on the Tao backend
 * (`taoApplication { … }`).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun ApplicationScope.MaterialDecoratedWindow(
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
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val windowStyle = rememberMaterialWindowStyle(colorScheme)
    val materialTitleBarStyle = rememberMaterialTitleBarStyle(colorScheme)

    NucleusDecoratedWindowTheme(
        isDark = colorScheme.isDark(),
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle ?: materialTitleBarStyle,
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
            content = content,
        )
    }
}

/**
 * Material 3 wrapper around Tao's `DecoratedWindow`. Same styling as the
 * AWT-based overload above; consumes Tao's [TaoApplicationScope] receiver so
 * call-sites only need to swap `application { … }` → `taoApplication { … }`
 * when migrating between backends.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TaoApplicationScope.MaterialDecoratedWindow(
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
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    // Tao opens a fresh ComposeScene per window, so CompositionLocals from the
    // outer scope (MaterialTheme, JewelTheme, …) do NOT propagate. Capture the
    // outer theme values here and re-provide them INSIDE the new scene below.
    val outerColorScheme = MaterialTheme.colorScheme
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColorScheme)
    val resolvedTitleBarStyle = titleBarStyle ?: rememberMaterialTitleBarStyle(outerColorScheme)
    val isDark = outerColorScheme.isDark()

    TaoDecoratedWindow(
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
        macOSStyle = macOSStyle,
    ) {
        MaterialTheme(
            colorScheme = outerColorScheme,
            typography = outerTypography,
            shapes = outerShapes,
        ) {
            NucleusDecoratedWindowTheme(
                isDark = isDark,
                windowStyle = windowStyle,
                titleBarStyle = resolvedTitleBarStyle,
            ) {
                content()
            }
        }
    }
}

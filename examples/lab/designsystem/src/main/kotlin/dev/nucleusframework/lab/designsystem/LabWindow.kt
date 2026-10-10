package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.ControlButtonsDirection
import dev.nucleusframework.window.DecoratedDialogScope
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.TitleBarScope
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.jewel.JewelDecoratedWindow
import dev.nucleusframework.window.jewel.JewelDialogTitleBar
import dev.nucleusframework.window.jewel.JewelTitleBar

/** Side of the title bar where window actions go: away from the OS window controls. */
val TitleBarScope.actionsAlignment: Alignment.Horizontal
    get() = if (Platform.Current == Platform.MacOS) Alignment.End else Alignment.Start

/** Title (and optional subtitle) of every Lab title bar, centred like a native one. */
@Composable
fun TitleBarScope.LabTitle(
    title: String,
    subtitle: String? = null,
) {
    Row(
        Modifier.align(Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Text(title, style = LabTheme.typography.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = LabTheme.typography.small, color = LabTheme.colors.textMuted, maxLines = 1) }
    }
}

/** Window background and light/dark appearance taken from the Lab theme, for any decorated window. */
@Composable
fun DecoratedWindowScope.LabWindowAppearance() {
    WindowBackground(LabTheme.colors.panel)
    WindowAppearance(if (LabTheme.isDark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)
}

/**
 * The Lab's title bar (IntelliJ's): put [LabTitle], [TitleBarButton]s and the like in [content].
 * [controlButtonsDirection] and [layoutPolicy] are the stock title bar's own knobs.
 */
@Composable
fun DecoratedWindowScope.LabTitleBar(
    modifier: Modifier = Modifier,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    JewelTitleBar(
        modifier,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = layoutPolicy,
        content = content,
    )
}

/**
 * The frame of every Lab window that is not itself under test: Lab title bar (title,
 * subtitle, [actions] as [TitleBarButton]s) over a padded surface.
 */
@Composable
fun DecoratedWindowScope.LabWindowFrame(
    title: String,
    subtitle: String? = null,
    scrollable: Boolean = true,
    actions: @Composable TitleBarScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    LabWindowAppearance()
    Column(Modifier.fillMaxSize()) {
        LabTitleBar { _ ->
            LabTitle(title, subtitle)
            Row(
                Modifier.align(actionsAlignment).padding(horizontal = LabDimens.gap),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions()
            }
        }
        Divider()
        LabPane(Modifier.weight(1f), scrollable = scrollable, content = content)
    }
}

/** A padded surface: the body of a session window, a satellite panel, a dialog. */
@Composable
fun LabPane(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    /** `false` for bodies that lay out to the edges themselves (a DockLayout, a tab body). */
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = if (padded) LabDimens.page else 0.dp
    Box(modifier.fillMaxSize().background(LabTheme.colors.background)) {
        if (scrollable) {
            ScrollableColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(padding),
                verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
                content = content,
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
                content = content,
            )
        }
    }
}

/**
 * A decorated window in the Lab's style (Jewel window and title bar styling); the content
 * draws its own title bar ([LabTitleBar]). The shell window and [LabSessionWindow] use it.
 */
@Suppress("LongParameterList")
@Composable
fun NucleusApplicationScope.LabDecoratedWindow(
    title: String,
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(size = DpSize(720.dp, 560.dp)),
    minimumSize: DpSize? = DpSize(420.dp, 320.dp),
    resizable: Boolean = true,
    enabled: Boolean = true,
    alwaysOnTop: Boolean = false,
    focusable: Boolean = true,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    JewelDecoratedWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        title = title,
        resizable = resizable,
        enabled = enabled,
        alwaysOnTop = alwaysOnTop,
        focusable = focusable,
        onKeyEvent = onKeyEvent,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        minimumSize = minimumSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        content = content,
    )
}

/**
 * A window a probe opens through `SessionHost`, in the Lab's own style. Probes whose subject
 * *is* the window's chrome (window.chrome, window.theming, overlays) build their own instead.
 */
@Suppress("LongParameterList")
@Composable
fun NucleusApplicationScope.LabSessionWindow(
    title: String,
    onCloseRequest: () -> Unit,
    subtitle: String? = null,
    state: WindowState = rememberWindowState(size = DpSize(720.dp, 560.dp)),
    minimumSize: DpSize? = DpSize(420.dp, 320.dp),
    resizable: Boolean = true,
    enabled: Boolean = true,
    alwaysOnTop: Boolean = false,
    focusable: Boolean = true,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    scrollable: Boolean = true,
    actions: @Composable TitleBarScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    LabDecoratedWindow(
        title = title,
        onCloseRequest = onCloseRequest,
        state = state,
        minimumSize = minimumSize,
        resizable = resizable,
        enabled = enabled,
        alwaysOnTop = alwaysOnTop,
        focusable = focusable,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        onKeyEvent = onKeyEvent,
    ) {
        LabWindowFrame(title, subtitle, scrollable, actions, content)
    }
}

/** The frame of a Lab dialog: Jewel dialog title bar with the Lab title, over a [LabPane]. */
@Composable
fun DecoratedDialogScope.LabDialogFrame(
    title: String,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        JewelDialogTitleBar { _ -> LabTitle(title) }
        Divider()
        LabPane(Modifier.weight(1f), scrollable = scrollable, content = content)
    }
}

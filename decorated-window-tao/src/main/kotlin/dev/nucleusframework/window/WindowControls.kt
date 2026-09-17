package dev.nucleusframework.window

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.LocalRequestedTitleBarHeight
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.deco.LinuxWindowControl
import dev.nucleusframework.window.tao.deco.WindowsWindowControl
import dev.nucleusframework.window.utils.linux.LinuxTitleBarButton
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout

/**
 * A window control button, identified by the action it performs.
 *
 * [Maximize] and [Restore] are the two faces of the same slot: Nucleus picks
 * the right one from the live window state, so a [WindowControlsRenderer] only
 * has to draw the artwork it is handed.
 */
public enum class WindowControlType {
    /** Iconify the window. */
    Minimize,

    /** Grow the window to fill the work area. */
    Maximize,

    /** Return a maximized window to its floating bounds. */
    Restore,

    /** Close the window (routed through the app's `onCloseRequest`). */
    Close,

    /** Leave native fullscreen — replaces [Maximize] while fullscreen. */
    ExitFullscreen,
}

/**
 * Draws a single window control button for [WindowControls].
 *
 * Only the *drawing* is delegated: which buttons exist, in which order, on
 * which side, and what each one does stays with Nucleus. Implementations
 * should render a button of their own choosing and invoke [onClick] when it is
 * activated; [state] is provided so the artwork can follow the active /
 * inactive / maximized window state.
 */
public fun interface WindowControlsRenderer {
    @Composable
    public fun Control(
        type: WindowControlType,
        state: DecoratedWindowState,
        onClick: () -> Unit,
    )

    public companion object {
        /**
         * The stock look of the host platform: the Fluent caption buttons on
         * Windows and the GNOME/KDE controls on Linux — the very same drawing
         * code [TitleBar] uses.
         *
         * On macOS it draws nothing at all: the traffic-lights are real AppKit
         * buttons owned by the window server. [WindowControls] reserves their
         * footprint instead, so a custom chrome laid out around it lines up
         * with them.
         */
        public val Platform: WindowControlsRenderer = PlatformWindowControlsRenderer
    }
}

/**
 * The system window controls (minimize / maximize-restore / close), detached
 * from [TitleBar] so a design system can place them inside its own chrome —
 * typically the `titleBar` slot of a [WindowScaffold].
 *
 * Nucleus owns the semantics: [direction] decides the button order (and, on
 * Linux, the desktop's own button layout does), the maximize slot follows the
 * live maximized / fullscreen / [TaoWindow.isResizable] state, and close is
 * routed through the app's `onCloseRequest`. Supply a [renderer] to draw the
 * buttons in the design system's own style; the default reproduces the host
 * platform's look exactly.
 *
 * The controls fill the height they are given, so wrap them in a container of
 * the desired bar height and align them to the edge:
 *
 * ```
 * Box(Modifier.fillMaxWidth().height(52.dp)) {
 *     WindowControls(Modifier.align(Alignment.CenterEnd))
 * }
 * ```
 *
 * On macOS this reserves the traffic-light footprint (see
 * [WindowControlsRenderer.Platform]) rather than drawing anything — so a
 * chrome should reserve their space *either* by placing this composable *or*
 * by padding itself with [LocalWindowChromeInsets]'s `controlsInsets`, never
 * both, or the gap is counted twice.
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowControls(
    modifier: Modifier = Modifier,
    direction: ControlButtonsDirection = ControlButtonsDirection.Auto,
    renderer: WindowControlsRenderer = WindowControlsRenderer.Platform,
) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoScope = this as TaoDecoratedWindowScope
    val window = taoScope.window
    val state = taoScope.state

    val controlDir = direction.resolve()

    // macOS draws nothing: AppKit owns the traffic-lights. Reserve their
    // footprint instead, derived from the same title-bar height the native
    // side centres them against — not from [LocalWindowChromeInsets], which is
    // only populated inside a [WindowScaffold] and would silently reserve
    // nothing anywhere else.
    if (Platform.Current == Platform.MacOS && renderer === WindowControlsRenderer.Platform) {
        val barHeight = LocalRequestedTitleBarHeight.current.value.dp
        val insets =
            titleBarPadding(
                measuredHeight = barHeight,
                isFullscreen = state.isFullscreen,
                controlIsRtl = controlDir == LayoutDirection.Rtl,
                linuxControlsOnRight = null,
            )
        val reserved =
            maxOf(
                insets.calculateLeftPadding(LayoutDirection.Ltr),
                insets.calculateRightPadding(LayoutDirection.Ltr),
            )
        Spacer(modifier.fillMaxHeight().width(reserved))
        return
    }

    val actions =
        windowControlActions(
            window = window,
            state = state,
            isFullscreen = state.isFullscreen,
            onExitFullscreen = { window.setFullscreen(false) },
        )

    CompositionLocalProvider(LocalLayoutDirection provides controlDir) {
        // Center vertically like core's [TitleBarLayoutPolicy] does: the
        // Windows caption buttons fill the height themselves, but the
        // GNOME/KDE ones are fixed-size boxes and would otherwise hang from
        // the top of a bar taller than the button.
        Row(modifier = modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            for (action in actions) {
                renderer.Control(action.type, state, action.onClick)
            }
        }
    }
}

/** A resolved control button: what to draw and what it does. */
internal class WindowControlAction(
    val type: WindowControlType,
    val onClick: () -> Unit,
)

/**
 * The three logical slots every desktop title bar exposes. The concrete
 * [WindowControlType] each one resolves to depends on the window state; a slot
 * resolving to `null` is simply not rendered.
 */
internal enum class WindowControlSlot { Minimize, Maximize, Close }

/**
 * Platform button order, in [Row] order (leading → trailing) — unlike
 * [BasicTitleBar], which feeds its measure policy an edge-most-first list. On
 * Linux the desktop environment owns the layout, so the order is read from the
 * same GNOME/KDE settings `WindowControlsLinux` uses; `LinuxButtonLayout.buttons`
 * is edge-most first, which for a right-side layout is the reverse of what the
 * row needs (a left-side layout already has its edge-most button leading).
 */
@Composable
private fun windowControlSlots(): List<WindowControlSlot> =
    if (Platform.Current == Platform.Linux) {
        val layout = rememberLinuxButtonLayout()
        val ordered = if (layout.controlsOnRight) layout.buttons.reversed() else layout.buttons
        ordered.map { button ->
            when (button) {
                LinuxTitleBarButton.CLOSE -> WindowControlSlot.Close
                LinuxTitleBarButton.MAXIMIZE -> WindowControlSlot.Maximize
                LinuxTitleBarButton.MINIMIZE -> WindowControlSlot.Minimize
            }
        }
    } else {
        listOf(WindowControlSlot.Minimize, WindowControlSlot.Maximize, WindowControlSlot.Close)
    }

@Composable
private fun windowControlActions(
    window: TaoWindow,
    state: DecoratedWindowState,
    isFullscreen: Boolean,
    onExitFullscreen: (() -> Unit)?,
): List<WindowControlAction> =
    windowControlSlots().mapNotNull { slot ->
        resolveWindowControl(slot, window, state, isFullscreen, onExitFullscreen)
    }

/**
 * Resolves one slot against the live window state. Mirrors the branch order
 * `WindowControlsWindows` has always used: fullscreen swaps maximize for
 * exit-fullscreen, and the maximize slot disappears entirely on a
 * non-resizable window (`isResizable` is snapshot-backed, so a runtime
 * `setResizable()` recomposes — see #260).
 */
internal fun resolveWindowControl(
    slot: WindowControlSlot,
    window: TaoWindow,
    state: DecoratedWindowState,
    isFullscreen: Boolean,
    onExitFullscreen: (() -> Unit)?,
): WindowControlAction? =
    when (slot) {
        WindowControlSlot.Minimize ->
            WindowControlAction(WindowControlType.Minimize) { window.minimize() }

        WindowControlSlot.Maximize ->
            when {
                isFullscreen && onExitFullscreen != null ->
                    WindowControlAction(WindowControlType.ExitFullscreen, onExitFullscreen)

                !window.isResizable -> null

                state.isMaximized ->
                    WindowControlAction(WindowControlType.Restore) { window.setMaximized(false) }

                else ->
                    WindowControlAction(WindowControlType.Maximize) { window.setMaximized(true) }
            }

        // Fire the user's onCloseRequest (mirrors AWT's WINDOW_CLOSING
        // dispatch). Calling `requestClose()` directly would destroy the
        // window without giving the app a chance to exit the Tao event loop.
        WindowControlSlot.Close ->
            WindowControlAction(WindowControlType.Close) { window.requestUserClose() }
    }

/** Draws each control with the host platform's stock artwork. */
private object PlatformWindowControlsRenderer : WindowControlsRenderer {
    @Composable
    override fun Control(
        type: WindowControlType,
        state: DecoratedWindowState,
        onClick: () -> Unit,
    ) {
        val style = LocalTitleBarStyle.current
        when (Platform.Current) {
            Platform.Linux -> LinuxWindowControl(type, state, style, Modifier, onClick)
            // Nothing to draw: the traffic-lights belong to the window server.
            // [WindowControls] normally short-circuits before reaching a
            // renderer at all, but a custom renderer delegating here to reuse
            // the stock artwork would otherwise get Windows caption buttons
            // painted over the real buttons.
            Platform.MacOS -> Unit
            else -> WindowsWindowControl(type, state, style, onClick)
        }
    }
}

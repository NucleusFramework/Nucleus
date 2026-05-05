package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.DecoratedDialogState

/**
 * Tao-backed equivalent of `decorated-window-jni`'s `DecoratedDialog`.
 *
 * Same parameter set and rendering pipeline as the AWT-based backends:
 * non-resizable by default, close-only chrome via [DialogTitleBar].
 *
 * On Windows the dialog establishes a native owner relationship with the
 * enclosing [DecoratedWindow] (`SetWindowLongPtrW(GWLP_HWNDPARENT)`),
 * matching how Compose Desktop's `DialogWindow` → AWT `JDialog` behaves: the
 * dialog sits above its owner in z-order, minimises with it, stays out of
 * the taskbar, and disappears with it. The parent is **not** disabled — that
 * matches `decorated-window-jni` (its `JDialog` is not `APPLICATION_MODAL`)
 * and avoids losing the parent's keyboard focus across the dialog lifetime.
 * The parent is captured from [LocalTaoWindow] at the call site, so a
 * `DecoratedDialog` declared outside any [DecoratedWindow] degrades cleanly
 * to a regular top-level.
 */
@Suppress("LongParameterList", "FunctionNaming", "LongMethod")
@Composable
fun ApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable TaoDecoratedDialogScope.() -> Unit,
) {
    // Captured in the parent's composition: `LocalTaoWindow.current` here is
    // the enclosing DecoratedWindow's TaoWindow, not the dialog's own window
    // (which doesn't exist yet).
    val parent = LocalTaoWindow.current

    val latestContent by rememberUpdatedState(content)
    val latestOnClose by rememberUpdatedState(onCloseRequest)

    // DialogState only carries size + position; reuse the WindowState plumbing
    // of DecoratedWindow underneath and forward changes both ways.
    val windowState = rememberWindowState(
        size = state.size,
        position = state.position,
    )

    DecoratedWindow(
        onCloseRequest = {
            // Just delegate: the user callback typically toggles the `showDialog`
            // state, which removes this DecoratedDialog from composition and
            // triggers our DisposableEffect.onDispose. That's where the modal
            // teardown (parent re-enable + focus handoff) actually runs — both
            // the X click path and a pure state-toggle path share that single
            // cleanup, so there's no asymmetry.
            latestOnClose()
        },
        state = windowState,
        title = title,
        icon = icon,
        minimumSize = null,
        visible = visible,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = false,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        content = {
            val windowScope = this
            val dialogScope = remember(windowScope) {
                object : TaoDecoratedDialogScope, ColumnScope by windowScope {
                    override val window: TaoWindow = windowScope.window
                    override val state: DecoratedDialogState
                        get() = DecoratedDialogState.of(active = windowScope.state.isActive)
                }
            }

            // Native owner relationship. Runs inside the dialog's composition,
            // so `windowScope.window` is the dialog's TaoWindow and its HWND
            // is already resolvable via [TaoWindow.nativeHandle].
            DisposableEffect(windowScope.window, parent) {
                applyDialogOwnerRelationship(
                    dialog = windowScope.window,
                    parent = parent,
                )
                onDispose { /* HWND destruction restores focus to owner */ }
            }

            // Centre on parent before the first show. Only meaningful when
            // the user did not pin an explicit position via DialogState.
            LaunchedEffect(windowScope.window, parent) {
                if (state.position !is WindowPosition.Absolute) {
                    centerDialogOnParent(
                        dialog = windowScope.window,
                        parent = parent,
                    )
                }
            }

            with(dialogScope) { latestContent() }
        },
    )

    // Bidirectional bridge between DialogState and the WindowState plumbed
    // into the underlying DecoratedWindow.
    LaunchedEffect(windowState.size) {
        if (state.size != windowState.size) state.size = windowState.size
    }
    LaunchedEffect(windowState.position) {
        val p = windowState.position
        if (p is WindowPosition.Absolute && p != state.position) state.position = p
    }
    LaunchedEffect(state.size) {
        if (state.size != windowState.size) windowState.size = state.size
    }
    LaunchedEffect(state.position) {
        val p = state.position
        if (p is WindowPosition.Absolute && p != windowState.position) {
            windowState.position = p
        }
    }
}

/**
 * Wires the Windows-only owner relationship between [dialog] and [parent].
 * Mirrors `decorated-window-jni`'s `DecoratedDialog`, which uses Compose
 * Desktop's `DialogWindow` → AWT `JDialog`: the JDialog is created with the
 * parent as owner but **not** `APPLICATION_MODAL`, so the parent stays
 * interactive. We never call `EnableWindow(parent, false)` for the same
 * reason: disabling the parent strips its keyboard focus and Win32 won't
 * restore it cleanly when the dialog closes (`SetForegroundWindow` gets
 * rejected once we lose the foreground role), leaving the user having to
 * click the parent to revive it.
 *
 * No-op on non-Windows platforms or when the bridge / parent is unavailable.
 */
private fun applyDialogOwnerRelationship(dialog: TaoWindow, parent: TaoWindow?) {
    if (Platform.Current != Platform.Windows) return
    if (parent == null) return
    if (!NativeTaoWindowsDecoBridge.isLoaded) return

    val dialogHwnd = dialog.nativeHandle
    val parentHwnd = parent.nativeHandle
    if (dialogHwnd == 0L || parentHwnd == 0L) return

    NativeTaoWindowsDecoBridge.nativeSetOwner(dialogHwnd, parentHwnd)
}


/**
 * Centres [dialog] on [parent] using physical-pixel screen rects from
 * `GetWindowRect`. No-op when the parent or bridge is unavailable; the
 * resulting outer position is set in logical pixels (Tao expects dp-style
 * coordinates).
 */
private fun centerDialogOnParent(dialog: TaoWindow, parent: TaoWindow?) {
    if (Platform.Current != Platform.Windows) return
    if (parent == null) return
    if (!NativeTaoWindowsDecoBridge.isLoaded) return

    val parentHwnd = parent.nativeHandle
    val dialogHwnd = dialog.nativeHandle
    if (parentHwnd == 0L || dialogHwnd == 0L) return

    val parentRect = NativeTaoWindowsDecoBridge.nativeGetWindowRect(parentHwnd) ?: return
    val dialogRect = NativeTaoWindowsDecoBridge.nativeGetWindowRect(dialogHwnd) ?: return

    val (px, py, pw, ph) = parentRect
    val (_, _, dw, dh) = dialogRect
    val centerXPhys = px + (pw - dw) / 2
    val centerYPhys = py + (ph - dh) / 2

    val scaleMilli = NativeTaoBridge.nativeScaleFactor(dialog.handle).coerceAtLeast(1)
    val scale = scaleMilli / 1000.0
    dialog.setOuterPosition(centerXPhys / scale, centerYPhys / scale)
}

private operator fun LongArray.component1(): Long = this[0]
private operator fun LongArray.component2(): Long = this[1]
private operator fun LongArray.component3(): Long = this[2]
private operator fun LongArray.component4(): Long = this[3]

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
import androidx.compose.ui.unit.dp
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
 * On Windows and macOS the dialog establishes a native owner relationship
 * with the enclosing [DecoratedWindow] — `SetWindowLongPtrW(GWLP_HWNDPARENT)`
 * on Win32, `[NSWindow addChildWindow:ordered:]` on AppKit. The dialog sits
 * above its owner in z-order, follows it across minimisation / Spaces, stays
 * out of the taskbar, and disappears with it. The parent is **not** disabled
 * — that matches `decorated-window-jni` (its `JDialog` is not
 * `APPLICATION_MODAL`) and avoids losing the parent's keyboard focus across
 * the dialog lifetime. The parent is captured from [LocalTaoWindow] at the
 * call site, so a `DecoratedDialog` declared outside any [DecoratedWindow]
 * degrades cleanly to a regular top-level.
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

    // Resolve the initial position synchronously at composition time so
    // [DecoratedWindow]'s position effect (which runs before the visible
    // effect) sees an Absolute position and applies it before show(). Doing
    // the centring later via a LaunchedEffect inside the dialog's content
    // composition produced a visible glitch — the window briefly appeared
    // at Tao's default origin before being moved to the centre. `remember`
    // keys on `parent` only: subsequent recompositions don't re-centre, in
    // line with how Compose Desktop's DialogWindow only honours its
    // initial position.
    //
    // macOS is handled differently — see [applyDialogOwnerRelationship].
    // AppKit's `addChildWindow:` makes the child visible at its current
    // frame, which races with the Compose-side `setOuterPosition`; the
    // native bridge centres atomically right before `addChildWindow:` to
    // avoid the flash, so we don't pre-compute a position here.
    val autoCenterRequested = state.position !is WindowPosition.Absolute
    val initialPosition = remember(parent) {
        val explicit = state.position
        if (explicit is WindowPosition.Absolute) return@remember explicit
        if (Platform.Current != Platform.Windows) return@remember explicit
        val centered = centerOnParentWindows(parent, state.size.width.value, state.size.height.value)
            ?: return@remember explicit
        state.position = centered
        centered
    }

    // DialogState only carries size + position; reuse the WindowState plumbing
    // of DecoratedWindow underneath and forward changes both ways.
    val windowState = rememberWindowState(
        size = state.size,
        position = initialPosition,
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
                    autoCenter = autoCenterRequested,
                )
                onDispose { /* native handle destruction restores focus to owner */ }
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
 * Wires the native owner relationship between [dialog] and [parent].
 *
 * Mirrors `decorated-window-jni`'s `DecoratedDialog`, which uses Compose
 * Desktop's `DialogWindow` → AWT `JDialog`: the JDialog is created with the
 * parent as owner but **not** `APPLICATION_MODAL`, so the parent stays
 * interactive.
 *
 * On Win32 we never call `EnableWindow(parent, false)`: disabling the parent
 * strips its keyboard focus and Win32 won't restore it cleanly when the
 * dialog closes (`SetForegroundWindow` gets rejected once we lose the
 * foreground role), leaving the user having to click the parent to revive it.
 * On macOS `addChildWindow:ordered:` gives us the right behaviour (parent
 * stays usable, child stays above) but it also makes the child visible at
 * its current frame — we therefore pass [autoCenter] through so the native
 * side can pre-position the child on the owner's centre atomically right
 * before `addChildWindow:` makes it appear, avoiding a one-frame flash at
 * Tao's default origin.
 *
 * No-op on Linux or when the bridge / parent is unavailable.
 */
private fun applyDialogOwnerRelationship(
    dialog: TaoWindow,
    parent: TaoWindow?,
    autoCenter: Boolean,
) {
    if (parent == null) return

    when (Platform.Current) {
        Platform.Windows -> {
            if (!NativeTaoWindowsDecoBridge.isLoaded) return
            val dialogHwnd = dialog.nativeHandle
            val parentHwnd = parent.nativeHandle
            if (dialogHwnd == 0L || parentHwnd == 0L) return
            NativeTaoWindowsDecoBridge.nativeSetOwner(dialogHwnd, parentHwnd)
        }
        Platform.MacOS -> {
            if (!NativeTaoMacOsDecoBridge.isLoaded) return
            val dialogView = dialog.nativeHandle
            val parentView = parent.nativeHandle
            if (dialogView == 0L || parentView == 0L) return
            NativeTaoMacOsDecoBridge.nativeSetOwner(dialogView, parentView, autoCenter)
        }
        else -> Unit
    }
}

/**
 * Windows-only: resolves the parent's outer rect and returns a
 * [WindowPosition.Absolute] that centres a window of [dialogWidthDp] ×
 * [dialogHeightDp] on it. Returns `null` when the bridge is unavailable so
 * the caller can keep whatever default position the platform picks.
 *
 * macOS goes through [applyDialogOwnerRelationship]'s native centring path
 * instead, because `addChildWindow:` makes the child visible synchronously
 * — pre-computing the position on the JVM side leaves a window of time in
 * which AppKit can paint at the wrong origin.
 */
private fun centerOnParentWindows(
    parent: TaoWindow?,
    dialogWidthDp: Float,
    dialogHeightDp: Float,
): WindowPosition.Absolute? {
    if (parent == null) return null
    if (!NativeTaoWindowsDecoBridge.isLoaded) return null
    val hwnd = parent.nativeHandle
    if (hwnd == 0L) return null
    val parentRectPhys = NativeTaoWindowsDecoBridge.nativeGetWindowRect(hwnd) ?: return null

    val scaleMilli = NativeTaoBridge.nativeScaleFactor(parent.handle).coerceAtLeast(1)
    val scale = scaleMilli / 1000.0

    val parentXDp = parentRectPhys[0] / scale
    val parentYDp = parentRectPhys[1] / scale
    val parentWDp = parentRectPhys[2] / scale
    val parentHDp = parentRectPhys[3] / scale

    val cx = (parentXDp + (parentWDp - dialogWidthDp) / 2.0).toFloat()
    val cy = (parentYDp + (parentHDp - dialogHeightDp) / 2.0).toFloat()
    return WindowPosition.Absolute(cx.dp, cy.dp)
}

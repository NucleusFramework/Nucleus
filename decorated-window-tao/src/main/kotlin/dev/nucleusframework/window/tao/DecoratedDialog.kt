@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedDialogState
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

/**
 * Tao-backed equivalent of the legacy AWT backend's `DecoratedDialog`.
 *
 * Same parameter set and rendering pipeline as the AWT-based backends:
 * non-resizable by default, close-only chrome via [DialogTitleBar].
 *
 * On every platform the dialog establishes a native owner relationship
 * with the enclosing [DecoratedWindow] — `SetWindowLongPtrW(GWLP_HWNDPARENT)`
 * on Win32, `[NSWindow addChildWindow:ordered:]` on AppKit, and
 * `gtk_window_set_transient_for` on Linux/GTK. The dialog sits above its
 * owner in z-order, follows it across minimisation / Spaces / workspace
 * switches, stays out of the taskbar, and disappears with it. The parent is **not** disabled
 * — that matches the legacy AWT backend (its `JDialog` is not
 * `APPLICATION_MODAL`) and avoids losing the parent's keyboard focus across
 * the dialog lifetime. The parent is captured from [LocalTaoWindow] at the
 * call site, so a `DecoratedDialog` declared outside any [DecoratedWindow]
 * degrades cleanly to a regular top-level.
 */
@Suppress("LongParameterList", "FunctionNaming", "LongMethod")
@Composable
public fun ApplicationScope.DecoratedDialog(
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
    // Parent composition locals bridged into the dialog's own ComposeScene from
    // the first composition. Forwarded to the underlying [DecoratedWindow] so the
    // dialog content sees the parent window's theme/user locals without
    // hijacking popup routing. See [LocalTaoCompositionLocalContextBridge].
    compositionLocalContext: CompositionLocalContext? = null,
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
    val sizeSpecified = state.size.width.isSpecified && state.size.height.isSpecified
    val initialPosition =
        remember(parent) {
            val explicit = state.position
            if (explicit is WindowPosition.Absolute) return@remember explicit
            // Wrap-content dialogs (#532) don't know their height yet — a
            // centre computed against Dp.Unspecified is wrong. The size
            // bridge below recentres once the measured size is specified.
            if (!sizeSpecified) return@remember explicit
            val centered =
                when (Platform.Current) {
                    Platform.Windows ->
                        centerOnParentWindows(parent, state.size.width.value, state.size.height.value)
                    Platform.Linux ->
                        centerOnParentLinux(parent, state.size.width.value, state.size.height.value)
                    else -> null
                } ?: return@remember explicit
            state.position = centered
            centered
        }

    // DialogState only carries size + position; reuse the WindowState plumbing
    // of DecoratedWindow underneath and forward changes both ways.
    val windowState =
        rememberWindowState(
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
        isDialog = true,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        compositionLocalContext = compositionLocalContext,
        content = {
            val windowScope = this
            val dialogScope =
                remember(windowScope) {
                    object : TaoDecoratedDialogScope, ColumnScope by windowScope {
                        override val window: TaoWindow = windowScope.window
                        override val state: DecoratedDialogState
                            get() = DecoratedDialogState.of(active = windowScope.state.isActive)
                    }
                }

            // Native owner relationship. Runs inside the dialog's composition,
            // so `windowScope.window` is the dialog's TaoWindow and its HWND
            // is already resolvable via [TaoWindow.nativeHandle].
            // Do not wait for wrap-content size: on Wayland a hidden dialog
            // without an owner never receives a configure, so setContent
            // never runs and wrap-content deadlocks (#532).
            DisposableEffect(windowScope.window, parent) {
                applyWindowOwnerRelationship(
                    child = windowScope.window,
                    owner = parent,
                    autoCenter = autoCenterRequested && sizeSpecified,
                )
                onDispose { /* native handle destruction restores focus to owner */ }
            }

            with(dialogScope) { latestContent() }
        },
    )

    // Bidirectional bridge between DialogState and the WindowState plumbed
    // into the underlying DecoratedWindow. After wrap-content resolves,
    // position is still not Absolute — centre on the parent once.
    LaunchedEffect(windowState.size) {
        if (state.size != windowState.size) state.size = windowState.size
        recenterAfterWrapContent(autoCenterRequested, parent, windowState, state)
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

private fun recenterAfterWrapContent(
    autoCenterRequested: Boolean,
    parent: TaoWindow?,
    windowState: WindowState,
    state: DialogState,
) {
    if (!autoCenterRequested || state.position is WindowPosition.Absolute) return
    if (!windowState.size.width.isSpecified || !windowState.size.height.isSpecified) return
    val centered =
        when (Platform.Current) {
            Platform.Windows ->
                centerOnParentWindows(parent, windowState.size.width.value, windowState.size.height.value)
            Platform.Linux ->
                centerOnParentLinux(parent, windowState.size.width.value, windowState.size.height.value)
            else -> null
        } ?: return
    windowState.position = centered
    state.position = centered
}

/**
 * Wires the native owner relationship between [child] and [owner].
 *
 * Shared by [DecoratedDialog] and [SatelliteWindow]: both want the same
 * secondary-window semantics — the child sits above its owner in z-order,
 * follows it across minimisation / Spaces / workspace switches, stays out of
 * the taskbar, and disappears with it — while the owner stays interactive.
 *
 * For dialogs this mirrors the legacy AWT backend, which uses Compose
 * Desktop's `DialogWindow` → AWT `JDialog`: the JDialog is created with the
 * parent as owner but **not** `APPLICATION_MODAL`.
 *
 * On Win32 we never call `EnableWindow(owner, false)`: disabling the owner
 * strips its keyboard focus and Win32 won't restore it cleanly when the
 * child closes (`SetForegroundWindow` gets rejected once we lose the
 * foreground role), leaving the user having to click the owner to revive it.
 * On macOS `addChildWindow:ordered:` gives us the right behaviour (owner
 * stays usable, child stays above) but it also makes the child visible at
 * its current frame — we therefore pass [autoCenter] through so the native
 * side can pre-position the child on the owner's centre atomically right
 * before `addChildWindow:` makes it appear, avoiding a one-frame flash at
 * Tao's default origin. Satellites resolve their own anchored position
 * instead and pass `false`.
 *
 * Re-invoking with a different [owner] reparents the child (AppKit tears the
 * previous `addChildWindow:` down itself, Win32 and GTK overwrite the owner),
 * without moving it.
 *
 * No-op when the relevant bridge or the owner is unavailable.
 */
internal fun applyWindowOwnerRelationship(
    child: TaoWindow,
    owner: TaoWindow?,
    autoCenter: Boolean,
    /**
     * Whether the platform may take [child] down together with [owner] — the
     * JDialog behaviour a dialog wants. A satellite passes `false`: it outlives
     * the window it is anchored to, since the workspace hands it to another
     * one when that window closes.
     */
    destroyWithOwner: Boolean = true,
) {
    if (owner == null) return

    when (Platform.Current) {
        Platform.Windows -> {
            if (!NativeTaoWindowsDecoBridge.isLoaded) return
            val childHwnd = child.nativeHandle
            val ownerHwnd = owner.nativeHandle
            if (childHwnd == 0L || ownerHwnd == 0L) return
            NativeTaoWindowsDecoBridge.nativeSetOwner(childHwnd, ownerHwnd)
        }
        Platform.MacOS -> {
            if (!NativeTaoMacOsDecoBridge.isLoaded) return
            val childView = child.nativeHandle
            val ownerView = owner.nativeHandle
            if (childView == 0L || ownerView == 0L) return
            NativeTaoMacOsDecoBridge.nativeSetOwner(childView, ownerView, autoCenter)
        }
        Platform.Linux -> {
            // GTK route: `gtk_window_set_transient_for` covers z-order /
            // minimisation / focus return; `skip_taskbar_hint` and
            // `destroy_with_parent` round out the JDialog semantics. The
            // actual positioning is already done synchronously on the JVM side
            // (see [centerOnParentLinux]) before the child window is shown,
            // so we don't need a native pre-position step like macOS.
            NativeTaoBridge.nativeLinuxSetDialogOwner(child.handle, owner.handle, destroyWithOwner)
        }
        else -> Unit
    }
}

/**
 * Severs the native owner link of [child] — the inverse of
 * [applyWindowOwnerRelationship] — leaving it a plain top-level window.
 *
 * Used by [SatelliteWindow] right before its owner is destroyed: Win32
 * destroys owned windows together with their owner and GTK does the same for
 * `destroy_with_parent` transients, which would take down a satellite the app
 * is reparenting in that very frame. AppKit only orphans child windows, so
 * there this merely keeps the three platforms on one code path.
 */
internal fun clearWindowOwnerRelationship(child: TaoWindow) {
    when (Platform.Current) {
        Platform.Windows -> {
            if (!NativeTaoWindowsDecoBridge.isLoaded) return
            val childHwnd = child.nativeHandle
            if (childHwnd == 0L) return
            NativeTaoWindowsDecoBridge.nativeSetOwner(childHwnd, 0L)
        }
        Platform.MacOS -> {
            if (!NativeTaoMacOsDecoBridge.isLoaded) return
            val childView = child.nativeHandle
            if (childView == 0L) return
            NativeTaoMacOsDecoBridge.nativeSetOwner(childView, 0L, false)
        }
        Platform.Linux -> NativeTaoBridge.nativeLinuxSetDialogOwner(child.handle, 0L, false)
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

    val parentScaleMilli = NativeTaoBridge.nativeScaleFactor(parent.handle).coerceAtLeast(1)
    val parentScale = parentScaleMilli / 1000.0

    // Target physical center in virtual screen coordinates:
    val dialogWidthPhys = dialogWidthDp * parentScale
    val dialogHeightPhys = dialogHeightDp * parentScale
    val targetCenterXPhys = parentRectPhys[0] + (parentRectPhys[2] - dialogWidthPhys) / 2.0
    val targetCenterYPhys = parentRectPhys[1] + (parentRectPhys[3] - dialogHeightPhys) / 2.0

    // Tao creates newly constructed windows at the primary monitor's initial scale.
    // When Tao applies LogicalPosition(x, y), it multiplies by that initial scale.
    // Converting target physical coordinates by the initial scale guarantees that
    // Tao's multiplication reproduces the exact physical pixel location on the target monitor.
    val initScaleMilli = NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorScaleMilli().coerceAtLeast(1)
    val initScale = initScaleMilli / 1000.0

    val cx = (targetCenterXPhys / initScale).toFloat()
    val cy = (targetCenterYPhys / initScale).toFloat()
    return WindowPosition.Absolute(cx.dp, cy.dp)
}

/**
 * Linux counterpart of [centerOnParentWindows]. Pulls the parent's outer rect
 * via the GTK-backed `nativeLinuxGetWindowRect` and converts physical → logical
 * pixels using the parent's own scale factor. Returns `null` when the parent
 * isn't realised yet, in which case Tao keeps its default origin.
 *
 * Centring on the JVM side (rather than via `gtk_window_set_position(
 * CENTER_ON_PARENT)`) keeps the contract symmetric with Windows and
 * macOS — `DecoratedDialog` plumbs the resolved [WindowPosition.Absolute]
 * through the standard `WindowState` pipeline so the LE position effect fires
 * with the centred coords *before* the window is shown.
 */
private fun centerOnParentLinux(
    parent: TaoWindow?,
    dialogWidthDp: Float,
    dialogHeightDp: Float,
): WindowPosition.Absolute? {
    if (parent == null) return null
    val parentRectPhys = NativeTaoBridge.nativeLinuxGetWindowRect(parent.handle) ?: return null

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

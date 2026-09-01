@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.DialogState
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.TaoNucleusWindow
import dev.nucleusframework.window.DecoratedDialogState
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalModalDialogCount
import dev.nucleusframework.window.tao.LocalTaoCompositionLocalContextBridge
import dev.nucleusframework.window.tao.TaoDecoratedDialogScope
import dev.nucleusframework.window.tao.DecoratedDialog as TaoDecoratedDialog

internal object TaoDecoratedDialogAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Dialog(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        // Bridge every CompositionLocal across the fresh Tao ComposeScene —
        // see TaoDecoratedWindowAdapter for rationale.
        val outerLocals = currentCompositionLocalContext

        // Captured in the PARENT scene. The dialog scene is created with
        // `GlobalLayoutDirection` (Ltr) and `ProvideCommonCompositionLocals`
        // re-provides `LocalLayoutDirection` from it — ABOVE the user content but
        // BELOW the bridged `outerLocals` — so an RTL app would otherwise see its
        // dialog forced back to Ltr. Re-provide the parent's direction inside the
        // content (below ProvideCommonCompositionLocals); it's not a routing
        // local, so popups stay anchored to the dialog scene.
        val parentLayoutDirection = LocalLayoutDirection.current

        // Tell the parent window to block its pointer input while this dialog
        // is alive. The parent provides LocalModalDialogCount with a shared
        // MutableState; we increment it here and decrement on dispose.
        // outerLocals includes that state by reference, so the parent reacts.
        val parentModalCount = LocalModalDialogCount.current
        DisposableEffect(Unit) {
            parentModalCount.value++
            onDispose { parentModalCount.value-- }
        }

        with(scope.taoScope) {
            TaoDecoratedDialog(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                // Initial bridge: present from the dialog scene's FIRST
                // composition (effects run too late — a user local with a
                // throwing default, e.g. LocalAppGraph, would crash otherwise).
                compositionLocalContext = outerLocals,
            ) {
                bindNucleusDialogContent(outerLocals, parentLayoutDirection, content)
            }
        }
    }

    @Suppress("LongParameterList")
    @Composable
    fun DialogV2(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: androidx.compose.ui.window.v2.DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        minSize: androidx.compose.ui.unit.DpSize,
        maxSize: androidx.compose.ui.unit.DpSize,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        val outerLocals = currentCompositionLocalContext
        val parentLayoutDirection = LocalLayoutDirection.current
        val parentModalCount = LocalModalDialogCount.current
        DisposableEffect(Unit) {
            parentModalCount.value++
            onDispose { parentModalCount.value-- }
        }
        with(scope.taoScope) {
            TaoDecoratedDialog(
                onCloseRequest = onCloseRequest,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                minSize = minSize,
                maxSize = maxSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                compositionLocalContext = outerLocals,
            ) {
                bindNucleusDialogContent(outerLocals, parentLayoutDirection, content)
            }
        }
    }
}

@Composable
private fun TaoDecoratedDialogScope.bindNucleusDialogContent(
    outerLocals: androidx.compose.runtime.CompositionLocalContext,
    parentLayoutDirection: androidx.compose.ui.unit.LayoutDirection,
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
) {
    val taoScope: TaoDecoratedDialogScope = this
    // Tao dialogs share TaoWindow with regular windows; rebuild the
    // active-state mirror as a single-bit DecoratedWindowState so
    // [TaoNucleusWindow] can read uniform flow values.
    val windowStateMirror =
        remember(taoScope) {
            derivedStateOf {
                DecoratedWindowState.of(active = taoScope.state.isActive)
            }
        }
    val nucleusWindow: NucleusWindow =
        remember(taoScope.window) {
            TaoNucleusWindow(taoScope.window, windowStateMirror)
        }
    val nucleusScope =
        remember(taoScope, nucleusWindow) {
            TaoNucleusDecoratedDialogScope(taoScope, nucleusWindow)
        }
    // Bridge the parent composition's locals (theme, density,
    // user-provided locals, …) into the dialog's own ComposeScene
    // via `ComposeScene.compositionLocalContext` rather than a
    // `CompositionLocalProvider(outerLocals)` wrapper. The wrapper
    // would re-provide Compose's internal `LocalComposeSceneContext`
    // captured from the PARENT scene, routing every Popup /
    // DropdownMenu / Tooltip layer back into the parent window — the
    // popup-mispositioned-relative-to-parent bug. The scene property
    // is applied above the scene's own `LocalComposeSceneContext`
    // (see RootNodeOwner.setContent), so theme flows while the dialog
    // scene keeps authority over popup layer creation.
    val bridge = LocalTaoCompositionLocalContextBridge.current
    SideEffect { bridge?.invoke(outerLocals) }
    CompositionLocalProvider(
        LocalLayoutDirection provides parentLayoutDirection,
        LocalNucleusWindow provides nucleusWindow,
    ) {
        nucleusScope.content()
    }
}

private class TaoNucleusDecoratedDialogScope(
    private val taoScope: TaoDecoratedDialogScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedDialogScope,
    TaoDecoratedDialogScope by taoScope {
    override val state: DecoratedDialogState get() = taoScope.state
}

package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.TaoNucleusWindow
import dev.nucleusframework.application.contextmenu.NativeContextMenuProvider
import dev.nucleusframework.window.LocalTitleBarInfo
import dev.nucleusframework.window.tao.LocalTaoCompositionLocalContextBridge
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.render.LocalTaoTextSelectionA11yPublisher
import dev.nucleusframework.window.tao.render.TaoTextSelectionAccessibility
import dev.nucleusframework.window.tao.SatelliteWindow as TaoSatelliteWindow

/**
 * Isolates references to Tao symbols for the satellite archetype. Mirrors
 * [TaoDecoratedWindowAdapter] — a satellite *is* a decorated window as far as
 * the content scope is concerned — minus the modal-count bookkeeping
 * [TaoDecoratedDialogAdapter] does: a satellite is explicitly non-modal and
 * must never scrim its parent.
 */
internal object TaoSatelliteWindowAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Satellite(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        parent: NucleusWindow?,
        state: SatelliteWindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        focusable: Boolean,
        hideWhileParentFullscreenOrMaximized: Boolean,
        nativeContextMenu: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        // Every local (theme, density, user locals, …) has to cross the fresh
        // ComposeScene the satellite gets — see TaoDecoratedWindowAdapter for
        // why this is the scene's `compositionLocalContext` and not a wrapping
        // CompositionLocalProvider.
        val outerLocals = currentCompositionLocalContext
        val parentLayoutDirection = LocalLayoutDirection.current
        // Resolved here, in the parent's composition: the ambient Nucleus
        // window is the satellite's owner unless the caller named another one.
        val parentTaoWindow = parent?.unsafe?.taoWindow ?: LocalTaoWindow.current

        with(scope.taoScope) {
            TaoSatelliteWindow(
                onCloseRequest = onCloseRequest,
                parent = parentTaoWindow,
                state = state,
                visible = visible,
                title = title,
                icon = icon,
                resizable = resizable,
                focusable = focusable,
                hideWhileParentFullscreenOrMaximized = hideWhileParentFullscreenOrMaximized,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                compositionLocalContext = outerLocals,
            ) {
                val taoScope: TaoDecoratedWindowScope = this
                val decoratedState = remember(taoScope) { derivedStateOf { taoScope.state } }
                val nucleusWindow: NucleusWindow =
                    remember(taoScope.window) {
                        TaoNucleusWindow(taoScope.window, decoratedState)
                    }
                val nucleusScope =
                    remember(taoScope, nucleusWindow) {
                        TaoNucleusDecoratedWindowScope(taoScope, nucleusWindow)
                    }
                val bridge = LocalTaoCompositionLocalContextBridge.current
                SideEffect { bridge?.invoke(outerLocals) }
                // Snapshot of this scene's own locals, re-provided below the
                // bridged outer ones: without LocalTaoWindow bound to *this*
                // window, windowDragArea() would drag the parent instead.
                val scenePublisher = LocalTaoTextSelectionA11yPublisher.current
                val sceneTaoWindow = LocalTaoWindow.current
                val sceneTitleBarInfo = LocalTitleBarInfo.current
                CompositionLocalProvider(
                    LocalLayoutDirection provides parentLayoutDirection,
                    LocalTaoTextSelectionA11yPublisher provides scenePublisher,
                    LocalNucleusWindow provides nucleusWindow,
                    LocalTaoWindow provides sceneTaoWindow,
                    LocalTitleBarInfo provides sceneTitleBarInfo,
                ) {
                    TaoTextSelectionAccessibility {
                        NativeContextMenuProvider(enabled = nativeContextMenu) {
                            nucleusScope.content()
                        }
                    }
                }
            }
        }
    }
}

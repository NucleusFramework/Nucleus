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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.ObserveSingleInstanceRestore
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.TaoNucleusWindow
import dev.nucleusframework.application.contextmenu.NativeContextMenuProvider
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.LocalTitleBarInfo
import dev.nucleusframework.window.tao.LocalTaoCompositionLocalContextBridge
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.render.LocalTaoTextSelectionA11yPublisher
import dev.nucleusframework.window.tao.render.TaoTextSelectionAccessibility
import dev.nucleusframework.window.tao.DecoratedWindow as TaoDecoratedWindow

/**
 * Isolates references to Tao symbols. Loaded only when the Tao backend is
 * active — keeps the unified DecoratedWindow callable on AWT-only classpaths.
 */
internal object TaoDecoratedWindowAdapter {
    @Suppress("LongParameterList")
    @Composable
    fun Window(
        scope: TaoNucleusApplicationScope,
        onCloseRequest: () -> Unit,
        state: WindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        undecorated: Boolean,
        transparent: Boolean,
        clickThrough: Boolean,
        visibleOnAllWorkspaces: Boolean,
        forceX11: Boolean,
        alwaysOnBottom: Boolean,
        popupFor: NucleusWindow?,
        nativePopupLayers: Boolean,
        nativeContextMenu: Boolean,
        hiddenFromDock: Boolean,
        minimumSize: DpSize?,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        // Tao opens a fresh ComposeScene per window; CompositionLocals from
        // the outer scope don't propagate across scenes. Capture the full
        // local context so every local (theme, density, layout direction,
        // user-provided locals, …) flows into the new scene — matching how
        // Compose's own Dialog/Popup bridge across scene boundaries.
        val outerLocals = currentCompositionLocalContext

        // Captured in the OUTER composition, for the same reason
        // TaoDecoratedDialogAdapter captures it: the window scene is created
        // with `GlobalLayoutDirection` and `ProvideCommonCompositionLocals`
        // re-provides `LocalLayoutDirection` from it — ABOVE the user content
        // but BELOW the bridged `outerLocals` — so an app-level RTL override
        // (or a parent window's direction, for a secondary window) would
        // otherwise be forced back to the system direction. Re-provide it
        // inside the content below; it's not a routing local, so popups stay
        // anchored to this window's own scene.
        val parentLayoutDirection = LocalLayoutDirection.current

        with(scope.taoScope) {
            TaoDecoratedWindow(
                onCloseRequest = onCloseRequest,
                state = state,
                title = title,
                icon = icon,
                minimumSize = minimumSize,
                visible = visible,
                resizable = resizable,
                enabled = enabled,
                focusable = focusable,
                alwaysOnTop = alwaysOnTop,
                undecorated = undecorated,
                transparent = transparent,
                clickThrough = clickThrough,
                visibleOnAllWorkspaces = visibleOnAllWorkspaces,
                forceX11 = forceX11,
                alwaysOnBottom = alwaysOnBottom,
                popupFor = popupFor?.unsafe?.taoWindow,
                nativePopupLayers = nativePopupLayers,
                hiddenFromDock = hiddenFromDock,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                // Initial bridge: present from this window's own scene's FIRST
                // composition (the SideEffect below carries every composition
                // after that). Mirrors TaoDecoratedDialogAdapter's identical need
                // for the identical reason — a user local with a throwing default
                // (e.g. LocalAppGraph) would otherwise crash before the
                // SideEffect ever gets to run. DecoratedWindow's own doc comment
                // on this parameter already documents it as exactly this bridge
                // ("[DecoratedDialog] forwards its parent's locals here") — this
                // adapter is the one top-level-window caller that never did.
                compositionLocalContext = outerLocals,
            ) {
                val taoScope: TaoDecoratedWindowScope = this
                val decoratedState =
                    remember(taoScope) {
                        derivedStateOf { taoScope.state }
                    }
                val nucleusWindow: NucleusWindow =
                    remember(taoScope.window) {
                        TaoNucleusWindow(taoScope.window, decoratedState)
                    }
                val nucleusScope =
                    remember(taoScope, nucleusWindow) {
                        TaoNucleusDecoratedWindowScope(taoScope, nucleusWindow)
                    }
                ObserveSingleInstanceRestore(nucleusWindow)
                // outerLocals were captured in the OUTER composition and cross the
                // scene boundary as this scene's own compositionLocalContext (the
                // parameter above for the first composition, the bridge below for
                // every one after). Compose applies that property ABOVE the scene's
                // own provisions (RootNodeOwner.setContent), which is the whole
                // point: Compose's internal LocalComposeSceneContext stays the one
                // THIS scene provided, so Popup/Dialog/DropdownMenu/Tooltip create
                // their layers here. The previous shape — a plain
                // CompositionLocalProvider(outerLocals) wrapper nested INSIDE the
                // scene — re-provided the captured scene context instead, so a
                // window opened from another window's content routed its popups
                // back into the PARENT scene (and threw once that scene was gone).
                // TaoDecoratedDialogAdapter always bridged its locals this way; this
                // adapter never did.
                //
                // Ordering consequence: everything the scene and DecoratedWindow
                // provide for themselves — LocalDensity, LocalTaoWindow,
                // LocalTitleBarInfo, LocalTaoTextSelectionA11yPublisher — now sits
                // BELOW outerLocals and wins on its own, so the snapshot-and-
                // re-provide below is no longer load-bearing. It stays as an
                // explicit guard: without LocalTaoWindow bound to THIS window,
                // windowDragArea() and WindowControlsWindows drive the PARENT
                // window and a secondary window looks immovable. LocalLayoutDirection
                // is the one local that does not come back on its own — the scene
                // re-provides GlobalLayoutDirection over the bridged value — hence
                // parentLayoutDirection, captured outside.
                val bridge = LocalTaoCompositionLocalContextBridge.current
                SideEffect { bridge?.invoke(outerLocals) }
                // The app theme's own LocalTextContextMenu (e.g. Jewel's) is not a
                // scene-owned local, so it does come through outerLocals and shadows
                // the scene's selection observer — silently breaking cross-process
                // selection reading (PopClip, AppleScript). TaoTextSelectionAccessibility
                // below re-installs the observer INSIDE the theme's menu, keeping it as
                // its delegate — cut/copy/paste icons & shortcuts preserved — and reads
                // the scene's publisher from this snapshot.
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

private class TaoNucleusDecoratedWindowScope(
    private val taoScope: TaoDecoratedWindowScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedWindowScope,
    TaoDecoratedWindowScope by taoScope {
    override val state: DecoratedWindowState get() = taoScope.state
}

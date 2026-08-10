package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.application.LocalNucleusBackend
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.ObserveSingleInstanceRestore
import dev.nucleusframework.application.TaoNucleusApplicationScope
import dev.nucleusframework.application.TaoNucleusWindow
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
        popupFor: NucleusWindow?,
        nativePopupLayers: Boolean,
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
                // outerLocals were captured in the OUTER composition. Bridging
                // them via a plain CompositionLocalProvider(outerLocals) wrapper
                // here — this function's own approach until this fix — clobbers
                // two different things this scene owns:
                //  - LocalDensity/LocalTaoWindow/LocalTitleBarInfo, overridden
                //    with the application root's/parent window's values (handled
                //    below exactly as before: snapshot before the bridge takes
                //    effect, re-provide after).
                //  - Compose's own internal LocalComposeSceneContext, captured
                //    from whatever scene (or lack of one) outerLocals came from.
                //    Every Popup/Dialog/DropdownMenu/Tooltip composed anywhere in
                //    content — regardless of nativePopupLayers — resolves that
                //    local to decide which scene it renders into; shadowed to the
                //    wrong scene (or no scene, for an application-root capture
                //    with no window of its own yet), it throws
                //    "LocalComposeSceneContext not provided"/"NavigationEvent-
                //    DispatcherOwner not found" the moment anything in content
                //    tries to show one. TaoDecoratedDialogAdapter already solved
                //    this exact problem correctly (see its own doc comment) via
                //    compositionLocalContext (above, for the first composition)
                //    plus this same bridge (for every composition after) — both
                //    apply outerLocals as the scene's own compositionLocalContext
                //    PROPERTY, which Compose itself
                //    applies above (not below) the scene's own
                //    LocalComposeSceneContext provision, rather than as a
                //    CompositionLocalProvider wrapper nested below it. This
                //    adapter never got the same fix; it's the one real
                //    difference between it and the dialog adapter in how outer
                //    locals cross the scene boundary.
                val bridge = LocalTaoCompositionLocalContextBridge.current
                SideEffect { bridge?.invoke(outerLocals) }
                val sceneDensity = LocalDensity.current
                // outerLocals carries the app theme's own LocalTextContextMenu
                // (e.g. Jewel's). Applying it here shadows the scene's selection
                // observer, which silently breaks cross-process selection reading
                // (PopClip, AppleScript). Re-install the observer INSIDE outerLocals
                // via the publisher, so it sits below the theme's menu and keeps it
                // as its delegate — preserving cut/copy/paste icons & shortcuts. The
                // publisher itself is reset by outerLocals, so snapshot + re-provide
                // it, exactly like LocalDensity.
                val scenePublisher = LocalTaoTextSelectionA11yPublisher.current
                val sceneTaoWindow = LocalTaoWindow.current
                val sceneTitleBarInfo = LocalTitleBarInfo.current
                CompositionLocalProvider(
                    LocalDensity provides sceneDensity,
                    LocalLayoutDirection provides parentLayoutDirection,
                    LocalTaoTextSelectionA11yPublisher provides scenePublisher,
                    LocalNucleusBackend provides NucleusBackend.Tao,
                    LocalNucleusWindow provides nucleusWindow,
                    LocalTaoWindow provides sceneTaoWindow,
                    LocalTitleBarInfo provides sceneTitleBarInfo,
                ) {
                    TaoTextSelectionAccessibility {
                        nucleusScope.content()
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

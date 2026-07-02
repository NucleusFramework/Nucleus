package dev.nucleusframework.application.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
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
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
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
                // outerLocals were captured in the OUTER application composition
                // (NoOpApplier with GlobalDensity = Density(1f)). Blindly applying
                // them inside the scene would override LocalDensity with the
                // platform-incorrect 1.0 (the scene's own owner.density mirrors
                // the screen's backing scale and is what Compose layout expects).
                // Snapshot the scene's density BEFORE applying outerLocals so we
                // can re-provide it inside. LocalLayoutDirection is intentionally
                // left to outerLocals so an app-level RTL override propagates here.
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
                CompositionLocalProvider(outerLocals) {
                    CompositionLocalProvider(
                        LocalDensity provides sceneDensity,
                        LocalTaoTextSelectionA11yPublisher provides scenePublisher,
                        LocalNucleusBackend provides NucleusBackend.Tao,
                        LocalNucleusWindow provides nucleusWindow,
                    ) {
                        TaoTextSelectionAccessibility {
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

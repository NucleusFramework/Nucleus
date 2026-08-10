@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the outer-locals/LocalComposeSceneContext shadowing
 * bug fixed in `nucleus-application`'s `TaoDecoratedWindowAdapter` (the
 * adapter both `DecoratedWindow` and `MaterialDecoratedWindow` route through).
 *
 * That adapter captures `currentCompositionLocalContext` from the
 * `nucleusApplication { }` body — a composition with no `ComposeScene` of its
 * own, since no Tao window (and therefore no scene) has opened yet — and used
 * to re-apply it inside the new window's own scene as a plain
 * `CompositionLocalProvider(outerLocals) { content() }` wrapper. That
 * re-provides Compose's own internal `LocalComposeSceneContext`, captured
 * from the scene-less outer composition, shadowing the real one this window's
 * scene set up for itself. Every `Popup`/`Dialog`/`DropdownMenu`/`Tooltip`
 * composed anywhere in `content` — Compose's own `AlertDialog` included, since
 * it's a `Dialog` under the hood — resolves that shadowed local to decide
 * which scene to render into, and throws the moment it does, regardless of
 * `nativePopupLayers`.
 *
 * Reproduced here at the plumbing level, two real `CanvasLayersComposeScene`s
 * standing in for the two real compositions involved — an "outer" scene with
 * no popup content of its own (the app root) and a "window" scene under test
 * (the Tao window) — rather than through the full `nucleus-application`
 * window/native-window stack, which has no offscreen test seam of its own.
 * [captureOuterLocals] is the scene-less capture; [TaoScenePopupTest] already
 * covers `Popup` rendering correctly inside a scene nothing has shadowed.
 */
class TaoSceneOuterLocalsBridgeTest {
    @Test
    fun `wrapping window content in outer locals with CompositionLocalProvider breaks Popup`() {
        val outerLocals = captureOuterLocals()
        runTaoSceneTest(width = 100, height = 100) {
            // BUG, reproduced: the Popup's scene-layer creation needs
            // LocalComposeSceneContext, shadowed to the outer (scene-less)
            // composition's own captured value by the wrapper below —
            // Compose's own ComposeSceneContext_skikoKt.requireCurrent throws
            // exactly this IllegalStateException, the same one that crashed the
            // real app the moment a Dialog was opened inside a DecoratedWindow.
            assertFailsWith<IllegalStateException> {
                setContent {
                    // The exact shape TaoDecoratedWindowAdapter used before this fix.
                    CompositionLocalProvider(outerLocals) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            Popup(offset = IntOffset(20, 20)) {
                                Box(Modifier.size(30.dp).background(Color.Blue))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `bridging outer locals through the scene's own compositionLocalContext property does not break Popup`() {
        val outerLocals = captureOuterLocals()
        runTaoSceneTest(width = 100, height = 100) {
            // The fixed shape: outerLocals becomes the scene's own property
            // (what TaoDecoratedWindow's initialCompositionLocalContext /
            // LocalTaoCompositionLocalContextBridge actually set under the hood),
            // applied ABOVE the scene's own LocalComposeSceneContext instead of
            // shadowing it from inside content.
            scene.compositionLocalContext = outerLocals
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Popup(offset = IntOffset(20, 20)) {
                        Box(Modifier.size(30.dp).background(Color.Blue))
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
        }
    }

    @Test
    fun `bridged outer locals do not carry the outer layout direction into content`() {
        // The flip side of the fix: because the scene property is applied ABOVE
        // the scene's own locals, ProvideCommonCompositionLocals re-provides
        // LocalLayoutDirection from the scene's own direction and wins over the
        // bridged one. An app-level RTL override — or a parent window's
        // direction, for a secondary window — therefore never reaches content
        // through the bridge alone. Both nucleus-application adapters snapshot
        // the outer direction and re-provide it inside content for exactly this
        // reason; this test pins the scene behaviour that makes that necessary.
        val outerDirection =
            if (GlobalLayoutDirection == LayoutDirection.Rtl) LayoutDirection.Ltr else LayoutDirection.Rtl
        val outerLocals = captureOuterLocals(outerDirection)
        var bridged: LayoutDirection? = null
        var reProvided: LayoutDirection? = null
        runTaoSceneTest(width = 10, height = 10) {
            scene.compositionLocalContext = outerLocals
            setContent {
                bridged = LocalLayoutDirection.current
                CompositionLocalProvider(LocalLayoutDirection provides outerDirection) {
                    reProvided = LocalLayoutDirection.current
                }
            }
            frame()
        }
        assertEquals(GlobalLayoutDirection, bridged)
        assertEquals(outerDirection, reProvided)
    }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
    }
}

/**
 * Captures a [CompositionLocalContext] from a real `ComposeScene` with no
 * popup content and no relation to the scene under test — standing in for
 * `nucleusApplication { }`'s own body, which has no `ComposeScene` (and
 * therefore no `LocalComposeSceneContext`) of its own before any Tao window
 * has opened.
 *
 * [layoutDirection] is provided inside the captured composition so a caller can
 * stand in for an app-level `LocalLayoutDirection` override.
 */
private fun captureOuterLocals(layoutDirection: LayoutDirection = GlobalLayoutDirection): CompositionLocalContext {
    var captured: CompositionLocalContext? = null
    val outerScene =
        CanvasLayersComposeScene(
            density = Density(1f),
            layoutDirection = GlobalLayoutDirection,
            size = IntSize(1, 1),
            platformContext =
                object : PlatformContext.Empty() {
                    override val windowInfo =
                        TaoWindowInfo().apply {
                            containerSize = IntSize(1, 1)
                            containerDpSize = DpSize(1.dp, 1.dp)
                        }
                },
            invalidate = {},
        )
    try {
        outerScene.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                captured = currentCompositionLocalContext
            }
        }
    } finally {
        outerScene.close()
    }
    return requireNotNull(captured) { "outer scene never composed" }
}

package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Windows port of [TaoComposeSceneContext]. Plugged into
 * `PlatformLayersComposeScene` so every Compose `Popup` /
 * `DropdownMenu` / `Tooltip` / context menu materialises as a
 * [TaoPopupSceneLayerWindows] — a borderless transparent owned
 * WS_POPUP HWND with its own WGL context.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContextWindows(
    override val platformContext: PlatformContext,
    private val popupHost: TaoPopupHostWindows,
) : ComposeSceneContext {

    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer = TaoPopupSceneLayerWindows(
        host = popupHost,
        initialDensity = density,
        initialLayoutDirection = layoutDirection,
        initialFocusable = focusable,
        parentCompositionContext = compositionContext,
    )
}

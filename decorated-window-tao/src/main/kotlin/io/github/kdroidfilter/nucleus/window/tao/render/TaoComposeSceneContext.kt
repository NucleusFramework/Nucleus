package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * `ComposeSceneContext` plugged into `PlatformLayersComposeScene` so that
 * every Compose `Popup` / `DropdownMenu` / `Tooltip` / text-field context
 * menu in the host scene materialises as a [TaoPopupSceneLayer] — a
 * borderless transparent `NSPanel` attached as a child window of the host
 * `NSWindow`. That gives us, on macOS, exactly the architecture Compose
 * iOS uses for `Popup`s with sibling `UIView` overlay layers, modulo the
 * fact that AppKit's `NSPanel` is a strictly cheaper and more powerful
 * primitive than `UIWindow`.
 *
 * Threading: `createLayer` is invoked from the host scene's composition,
 * which runs on the macOS main thread.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContext(
    override val platformContext: PlatformContext,
    private val popupHost: TaoPopupHost,
) : ComposeSceneContext {

    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer = TaoPopupSceneLayer(
        host = popupHost,
        initialDensity = density,
        initialLayoutDirection = layoutDirection,
        initialFocusable = focusable,
        parentCompositionContext = compositionContext,
    )
}

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * `ComposeSceneContext` that lifts Compose `Popup` / `DropdownMenu` /
 * `Tooltip` content into a native popup window (an NSPanel on macOS, a Tao
 * popup on Linux, a child HWND on Windows) instead of drawing it inside the
 * host's own render target.
 *
 * The three platforms used to have one context class each, differing only in
 * the concrete `TaoPopupSceneLayer*` / `TaoPopupHost*` types they wired
 * together. Those types share no common supertype, so instead of a shared
 * base this context is parameterised by a [layerFactory] closure: the caller
 * supplies the platform layer, and the context stays platform-agnostic.
 *
 * Threading: `createLayer` is invoked from the overlay scene's composition,
 * which runs on the platform's UI thread (macOS main thread / Tao GTK loop /
 * Windows message pump).
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContext(
    override val platformContext: PlatformContext,
    private val layerFactory: (
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        consumePointerInputOutside: Boolean,
    ) -> ComposeSceneLayer,
) : ComposeSceneContext {
    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        consumePointerInputOutside: Boolean,
    ): ComposeSceneLayer = layerFactory(density, layoutDirection, focusable, consumePointerInputOutside)
}

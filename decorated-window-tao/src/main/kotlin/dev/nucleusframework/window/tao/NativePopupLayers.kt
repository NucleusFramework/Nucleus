@file:OptIn(InternalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.scene.TaoComposeSceneContextAccess
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.scene.TaoPopupLayerFactory

/**
 * The window's native popup layer factory, for [NativePopupLayers]. Provided
 * by every Tao window that draws its own popups in-scene; `null` when the
 * window already runs on native popup layers (nothing to opt into) or has no
 * native popup pipeline.
 */
internal val LocalTaoNativePopupLayerFactory: ProvidableCompositionLocal<TaoPopupLayerFactory?> =
    staticCompositionLocalOf { null }

/**
 * Materialises every Compose `Popup` / `DropdownMenu` / `Tooltip` opened
 * directly inside [content] as a native popup surface — an `NSPanel` on
 * macOS, a transparent `WS_POPUP` HWND on Windows, a Tao popup window on
 * Linux — exactly as `DecoratedWindow(nativePopupLayers = true)` does for the
 * whole window, but for this subtree only. Popups opened elsewhere in the
 * window keep drawing inside its render target.
 *
 * This is what an OS-looking flyout needs: it must be able to leave the
 * window like the platform's own menus, and it must not depend on what the
 * application chose for its other popups. Popups opened from *inside* a
 * native surface (a submenu) already live in that surface's own scene and
 * need no further opt-in.
 *
 * A no-op when the window already runs on native popup layers, when it has
 * no native popup pipeline (not attached yet, native bridge missing), or
 * outside a Tao window: [content] then composes unchanged.
 */
@Suppress("FunctionNaming")
@Composable
public fun NativePopupLayers(content: @Composable () -> Unit) {
    val layerFactory = LocalTaoNativePopupLayerFactory.current
    val local = TaoComposeSceneContextAccess.localComposeSceneContext()
    // Platform type: the scene provides it for its own composition, so it is
    // only null outside any scene (the application root).
    val sceneContext: ComposeSceneContext? = local.current
    if (layerFactory == null || sceneContext == null) {
        content()
        return
    }
    val nativeLayerContext =
        remember(sceneContext, layerFactory) { NativeLayerSceneContext(sceneContext, layerFactory) }
    CompositionLocalProvider(local provides nativeLayerContext, content = content)
}

/**
 * The window scene's own context with one difference: layers come out of the
 * window's native popup pipeline instead of the scene's canvas. Everything
 * else — the platform context above all — is the scene's, so nothing that
 * reads the context sees a different window.
 */
private class NativeLayerSceneContext(
    sceneContext: ComposeSceneContext,
    private val layerFactory: TaoPopupLayerFactory,
) : ComposeSceneContext by sceneContext {
    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        consumePointerInputOutside: Boolean,
    ): ComposeSceneLayer = layerFactory(density, layoutDirection, focusable, consumePointerInputOutside)
}

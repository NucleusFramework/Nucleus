package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import org.jetbrains.skia.DirectContext

/**
 * macOS: the Metal/Skia surface the enclosing Compose scene draws into.
 * Consumed by the `TextureView` composable, which must build its Skia objects
 * on the very context that will replay the recorded scene — otherwise Skia
 * silently drops the draw (a GPU image belongs to exactly one `DirectContext`).
 *
 * Unlike Windows, where host, popups and overlays all share one Skia context,
 * every macOS surface owns its own `DirectContext` on its own render thread.
 * Each surface therefore provides its own [LocalTaoMetalTextureHost]:
 * [TaoComposeSceneHost] for the window scene,
 * [dev.nucleusframework.window.tao.popup.TaoPopupSceneLayer] for native popup
 * layers, `NativeViewOverlayController` for `NativeView` overlays.
 *
 * Threading: [metalDevicePtr] / [directContext] are read on the macOS main
 * thread; every *use* of [directContext] must be wrapped in
 * [runOnRenderThread] (Skia's Metal context is thread-affine).
 */
internal interface TaoMetalTextureHost {
    /** `id<MTLDevice>` [directContext] renders with. */
    val metalDevicePtr: Long

    /** Skia context of this surface; only touch it inside [runOnRenderThread]. */
    val directContext: DirectContext

    /**
     * Runs [block] on the render thread owning [directContext] and blocks
     * until it returns. Safe from the main thread during composition,
     * disposal, and the record pass — the render thread is idle at those
     * points (see [TaoComposeSceneHost]'s lifetime invariant).
     */
    fun <T> runOnRenderThread(block: () -> T): T
}

internal val LocalTaoMetalTextureHost: ProvidableCompositionLocal<TaoMetalTextureHost?> =
    compositionLocalOf { null }

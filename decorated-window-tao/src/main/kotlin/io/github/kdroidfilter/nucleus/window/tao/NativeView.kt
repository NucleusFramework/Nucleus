package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.render.LocalTaoPopupHost
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Embeds a platform-native view inside a Compose layout. Spiritual
 * equivalent of `UIKitView` on Compose iOS / `AndroidView` on Android.
 *
 * The user supplies a [NucleusPlatformView] from [factory] — a sealed
 * type whose variant decides the embedding strategy:
 *
 *  - [NucleusPlatformView.NsView] — macOS, real AppKit subview embedding.
 *  - [NucleusPlatformView.HWnd] — Windows, child HWND (not implemented yet).
 *  - [NucleusPlatformView.Texture] — Linux, texture-based composition
 *    (not implemented yet).
 *
 * Variants whose backend isn't implemented (or whose runtime type doesn't
 * match the current OS) fall back to an empty `Box(modifier)`.
 *
 * Compose's `Modifier.clip()` does **not** propagate to embedded native
 * views (same limitation as `AndroidView` / `UIKitView`). Use
 * [cornerRadius] for rounded/circular clipping; pass [Dp.Infinity] to
 * make it fully circular regardless of size.
 *
 * The optional [content] trailing slot renders Compose UI **on top of**
 * the native view via a sibling overlay NSView with its own
 * `CAMetalLayer` + `ComposeScene`. The overlay's hit-test is region-
 * based: areas not wrapped with [Modifier.consumeOverlayPointerEvents]
 * are hit-test-transparent — clicks pass through to the native view
 * underneath (typically a `WKWebView`).
 *
 * Usage:
 * ```
 * NativeView(
 *     factory = { object : NucleusPlatformView.NsView {
 *         override val nsViewHandle = wkWebViewHandle
 *     } },
 *     modifier = Modifier.fillMaxSize(),
 *     cornerRadius = 12.dp,
 * ) {
 *     Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
 *         Watermark(modifier = Modifier.consumeOverlayPointerEvents())
 *     }
 * }
 * ```
 */
@Composable
fun NativeView(
    factory: () -> NucleusPlatformView,
    modifier: Modifier = Modifier,
    update: (NucleusPlatformView) -> Unit = {},
    cornerRadius: Dp = Dp.Unspecified,
    content: @Composable () -> Unit = {},
) {
    val view = remember { factory() }
    val latestUpdate by rememberUpdatedState(update)

    DisposableEffect(view) {
        onDispose { view.dispose() }
    }
    SideEffect { latestUpdate(view) }

    when (view) {
        is NucleusPlatformView.NsView -> NsViewEmbedding(view, modifier, cornerRadius, content)
        is NucleusPlatformView.HWnd -> Box(modifier) // Phase 2 — not yet implemented.
        is NucleusPlatformView.Texture -> Box(modifier) // Phase 2 — not yet implemented.
    }
}

/**
 * macOS NSView embedding path. Falls back to an empty `Box(modifier)`
 * when the runtime isn't macOS or the host scene plumbing isn't
 * available.
 */
@Composable
private fun NsViewEmbedding(
    view: NucleusPlatformView.NsView,
    modifier: Modifier,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val host = LocalTaoNativeViewHost.current
    val popupHost = LocalTaoPopupHost.current
    val handle = view.nsViewHandle
    val latestContent by rememberUpdatedState(content)

    if (Platform.Current != Platform.MacOS || host == null || popupHost == null || handle == 0L) {
        Box(modifier)
        return
    }

    val overlay = remember(host, popupHost) {
        NativeViewOverlayController(host, popupHost)
    }

    DisposableEffect(host, handle, overlay) {
        host.attach(handle)
        // Overlay must attach AFTER the user's subview so AppKit
        // paints it on top in the subview list.
        overlay.attach()
        onDispose {
            overlay.dispose()
            host.detach(handle)
        }
    }

    DisposableEffect(overlay) {
        overlay.setContent {
            CompositionLocalProvider(LocalNativeViewOverlayController provides overlay) {
                latestContent()
            }
        }
        onDispose { /* dispose handled above */ }
    }

    // Resolve the corner-radius into pixels here so the layout-time
    // closure doesn't have to read CompositionLocals. `Infinity`
    // tells the native side to cap at min(w, h) / 2 → fully round.
    val density = LocalDensity.current
    val cornerRadiusPx = remember(cornerRadius, density) {
        when {
            cornerRadius == Dp.Unspecified -> 0f
            cornerRadius == Dp.Infinity -> Float.POSITIVE_INFINITY
            else -> with(density) { cornerRadius.toPx() }
        }
    }
    // Cache the last applied rect + radius so layout passes that
    // don't change anything skip the JNI hop entirely.
    val lastRect = remember { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1) }
    val lastRadius = remember { floatArrayOf(Float.NaN) }
    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            val xPx = pos.x.roundToInt()
            val yPx = pos.y.roundToInt()
            val wPx = coords.size.width.coerceAtLeast(1)
            val hPx = coords.size.height.coerceAtLeast(1)
            val rectChanged = lastRect[0] != xPx || lastRect[1] != yPx ||
                lastRect[2] != wPx || lastRect[3] != hPx
            if (rectChanged) {
                lastRect[0] = xPx; lastRect[1] = yPx; lastRect[2] = wPx; lastRect[3] = hPx
                host.setFrame(handle, xPx, yPx, wPx, hPx)
                overlay.setBounds(xPx, yPx, wPx, hPx)
            }
            // Re-applied on every size change because circular mode
            // (Infinity) needs the radius rebound to min(w,h)/2;
            // a fixed radius is also re-applied when bounds change
            // since AppKit's cornerRadius is stable across resize but
            // the cap may move. Cheap call.
            if (rectChanged || lastRadius[0] != cornerRadiusPx) {
                lastRadius[0] = cornerRadiusPx
                val radiusToApply = if (cornerRadiusPx.isInfinite()) {
                    min(wPx, hPx) / 2f
                } else {
                    cornerRadiusPx
                }
                host.setCornerRadius(handle, radiusToApply)
            }
        },
    )
}

/** Plumbing CompositionLocal — provided by `DecoratedWindow`. */
internal val LocalTaoNativeViewHost = compositionLocalOf<TaoNativeViewHost?> { null }

/** Decouples [NativeView] from the platform-specific scene host. */
internal interface TaoNativeViewHost {
    fun attach(childHandle: Long)
    fun detach(childHandle: Long)
    fun setFrame(handle: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
    fun setCornerRadius(handle: Long, radiusPx: Float)

    /**
     * Enqueues an AppKit mutation to run inside the next frame's
     * `CATransaction`, atomically with the Compose Metal present.
     * Used by `NativeViewOverlayController` to keep its overlay
     * NSView's `setFrame` in lock-step with the user subview's frame
     * change.
     *
     * Default fallback runs the action immediately — preserves
     * behaviour for hosts that don't yet implement the transaction
     * model (no visual sync, but still functionally correct).
     */
    fun scheduleInterop(action: () -> Unit) { action() }
}

/**
 * CompositionLocal exposing the overlay controller (if any) to
 * descendants of [NativeView]'s `content` slot. Used by
 * [Modifier.consumeOverlayPointerEvents] to register itself as an
 * interactive region.
 */
internal val LocalNativeViewOverlayController =
    compositionLocalOf<NativeViewOverlayController?> { null }

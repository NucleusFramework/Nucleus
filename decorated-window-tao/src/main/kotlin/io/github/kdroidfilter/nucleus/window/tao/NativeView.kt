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
 * Compose's `Modifier.clip()` does **not** propagate to the embedded
 * native view (same limitation as `AndroidView` / `UIKitView`). Use
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
 *     factory = { wkWebView },
 *     modifier = Modifier.fillMaxSize(),
 *     cornerRadius = 12.dp,
 * ) {
 *     Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
 *         Watermark(modifier = Modifier.consumeOverlayPointerEvents())
 *     }
 * }
 * ```
 *
 * No-op on Windows / Linux.
 */
@Composable
fun NativeView(
    factory: () -> Long,
    modifier: Modifier = Modifier,
    update: (Long) -> Unit = {},
    onRelease: (Long) -> Unit = {},
    cornerRadius: Dp = Dp.Unspecified,
    content: @Composable () -> Unit = {},
) {
    val host = LocalTaoNativeViewHost.current
    val popupHost = LocalTaoPopupHost.current
    val latestUpdate by rememberUpdatedState(update)
    val latestRelease by rememberUpdatedState(onRelease)
    val latestContent by rememberUpdatedState(content)

    val handle = remember { factory() }

    if (Platform.Current == Platform.MacOS && host != null && popupHost != null && handle != 0L) {
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
                latestRelease(handle)
            }
        }
        SideEffect { latestUpdate(handle) }

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
    } else {
        DisposableEffect(handle) {
            onDispose { if (handle != 0L) latestRelease(handle) }
        }
        Box(modifier = modifier)
    }
}

/** Plumbing CompositionLocal — provided by `DecoratedWindow`. */
internal val LocalTaoNativeViewHost = compositionLocalOf<TaoNativeViewHost?> { null }

/** Decouples [NativeView] from the platform-specific scene host. */
internal interface TaoNativeViewHost {
    fun attach(childHandle: Long)
    fun detach(childHandle: Long)
    fun setFrame(handle: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)
    fun setCornerRadius(handle: Long, radiusPx: Float)
}

/**
 * CompositionLocal exposing the overlay controller (if any) to
 * descendants of [NativeView]'s `content` slot. Used by
 * [Modifier.consumeOverlayPointerEvents] to register itself as an
 * interactive region.
 */
internal val LocalNativeViewOverlayController =
    compositionLocalOf<NativeViewOverlayController?> { null }

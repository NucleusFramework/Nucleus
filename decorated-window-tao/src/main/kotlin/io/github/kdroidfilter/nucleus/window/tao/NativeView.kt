package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.tao.render.LocalTaoPopupHost
import kotlin.math.roundToInt

/**
 * Embeds a platform-native view inside a Compose layout. Spiritual
 * equivalent of `UIKitView` on Compose iOS / `AndroidView` on Android.
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
    content: @Composable () -> Unit = {},
) {
    val host = LocalTaoNativeViewHost.current
    val popupHost = LocalTaoPopupHost.current
    val latestUpdate by rememberUpdatedState(update)
    val latestRelease by rememberUpdatedState(onRelease)
    val latestContent by rememberUpdatedState(content)

    val handle = remember { factory() }

    if (Platform.Current == Platform.MacOS && host != null && popupHost != null && handle != 0L) {
        // Track latest layout bounds so the overlay (created lazily
        // inside onGloballyPositioned) gets positioned even when the
        // first layout pass beats `DisposableEffect`.
        val overlay = remember(host, popupHost) {
            NativeViewOverlayController(host, popupHost)
        }

        DisposableEffect(host, handle, overlay) {
            host.attach(handle)
            // Attach the overlay AFTER the user's native subview so the
            // overlay sits on top in AppKit's subview list — otherwise
            // the WebView/etc. paints over the watermark.
            overlay.attach()
            onDispose {
                overlay.dispose()
                host.detach(handle)
                latestRelease(handle)
            }
        }
        androidx.compose.runtime.SideEffect { latestUpdate(handle) }

        // Mount overlay content + provide the controller so descendants'
        // `consumeOverlayPointerEvents` modifiers can register their
        // bounds.
        DisposableEffect(overlay) {
            overlay.setContent {
                CompositionLocalProvider(LocalNativeViewOverlayController provides overlay) {
                    latestContent()
                }
            }
            onDispose { /* dispose handled above */ }
        }

        Box(
            modifier = modifier.onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val xPx = pos.x.roundToInt()
                val yPx = pos.y.roundToInt()
                val wPx = coords.size.width.coerceAtLeast(1)
                val hPx = coords.size.height.coerceAtLeast(1)
                host.setFrame(handle, xPx, yPx, wPx, hPx)
                overlay.setBounds(xPx, yPx, wPx, hPx)
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
}

/**
 * CompositionLocal exposing the overlay controller (if any) to
 * descendants of [NativeView]'s `content` slot. Used by
 * [Modifier.consumeOverlayPointerEvents] to register itself as an
 * interactive region.
 */
internal val LocalNativeViewOverlayController =
    compositionLocalOf<NativeViewOverlayController?> { null }

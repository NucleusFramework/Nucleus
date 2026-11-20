package dev.nucleusframework.application

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Density
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.share.ShareAnchor
import dev.nucleusframework.share.ShareException
import dev.nucleusframework.share.ShareParent
import dev.nucleusframework.share.ShareRequest
import dev.nucleusframework.share.ShareRequestBuilder
import dev.nucleusframework.share.ShareSheet
import dev.nucleusframework.share.share
import dev.nucleusframework.share.shareRequest
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.XdgPortalParent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shows the system share sheet for [request], attached to this window:
 *
 * - **Windows**: the Share UI opens for the window's HWND.
 * - **macOS**: the picker hangs off the window, pointing at [anchor] (e.g. the button that
 *   triggered it, see [shareAnchor]) or the top centre of the window without one.
 * - **Linux X11 / XWayland**: the portal gets `x11:<xid>`.
 * - **Linux Wayland**: the window is exported through `xdg_foreign` and unexported once the
 *   portal dialog is gone, which is the lifetime the portal requires.
 *
 * A window without a platform identity (not realized yet, native bridge missing) falls back
 * to the app's frontmost window.
 *
 * ```kotlin
 * val window = LocalNucleusWindow.current
 * scope.launch {
 *     window.share {
 *         title = "Share"
 *         url("https://github.com/NucleusFramework/Nucleus")
 *     }
 * }
 * ```
 *
 * Requires `nucleus.share` on the app's classpath; `nucleus-application` never ships it.
 *
 * @throws ShareException when the request is invalid or the UI could not be shown
 */
public suspend fun NucleusWindow.share(
    request: ShareRequest,
    anchor: ShareAnchor? = null,
) {
    // The Wayland export blocks until the compositor answers, so keep it off the UI thread.
    // NonCancellable: an export resolved as the caller is cancelled must still reach
    // ShareSheet.share, which is what closes it.
    val parent = withContext(Dispatchers.IO + NonCancellable) { unsafe.taoWindow?.shareParent(anchor) }
    ShareSheet.share(request, parent ?: ShareParent.Auto)
}

/** Builds the request with [shareRequest] and [shares][share] it from this window. */
public suspend fun NucleusWindow.share(
    anchor: ShareAnchor? = null,
    block: ShareRequestBuilder.() -> Unit,
): Unit = share(shareRequest(block), anchor)

/**
 * This node's bounds as a macOS share-picker anchor: window coordinates converted to points.
 *
 * ```kotlin
 * var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
 * val density = LocalDensity.current
 * Button(
 *     onClick = { scope.launch { window.share(request, anchor) } },
 *     modifier = Modifier.onGloballyPositioned { anchor = it.shareAnchor(density) },
 * ) { Text("Share") }
 * ```
 */
public fun LayoutCoordinates.shareAnchor(density: Density): ShareAnchor {
    val bounds = boundsInWindow()
    val scale = density.density.toDouble()
    return ShareAnchor(
        x = bounds.left / scale,
        y = bounds.top / scale,
        width = bounds.width / scale,
        height = bounds.height / scale,
    )
}

private fun TaoWindow.shareParent(anchor: ShareAnchor?): ShareParent? =
    when (Platform.Current) {
        Platform.Windows -> nativeHandle.takeIf { it != 0L }?.let(ShareParent::Windows)
        Platform.MacOS -> nsWindowHandle?.let { ShareParent.MacOs(it, anchor) }
        Platform.Linux ->
            when (val portalParent = xdgPortalParent()) {
                is XdgPortalParent.X11 -> ShareParent.Linux(portalParent.portalParent)
                is XdgPortalParent.Wayland -> ShareParent.Linux(portalParent.portalParent, keepAlive = portalParent)
                null -> null
            }
        else -> null
    }

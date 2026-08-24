@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.deco.LocalNativeViewOverlayControllerWindows
import dev.nucleusframework.window.tao.deco.LocalTaoLinuxOverlayController
import dev.nucleusframework.window.tao.deco.NativeViewOverlayController
import dev.nucleusframework.window.tao.deco.NativeViewOverlayControllerWindows
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Embeds a platform-native view inside a Compose layout. Spiritual
 * equivalent of `UIKitView` on Compose iOS / `AndroidView` on Android.
 *
 * The user supplies a [NucleusPlatformView] from [factory] — a sealed
 * type whose variant decides the embedding strategy:
 *
 *  - [NucleusPlatformView.NsView] — macOS, real AppKit view sitting
 *    **below** the Compose Metal surface. Compose punches a transparent
 *    hole so the native view shows through; overlapping Compose
 *    (the [content] slot **and** later siblings — snackbars, buttons,
 *    in-scene popups) draws on top. Same model as Compose Desktop's
 *    `SwingPanel` with `compose.interop.blending=true`.
 *  - [NucleusPlatformView.GtkWidget] — Linux, GTK widget reparented
 *    into Tao's content widget. Same hole-punch blending as macOS;
 *    the [content] slot renders in the host scene. Interactive overlay
 *    widgets still opt in with [consumeOverlayPointerEvents].
 *  - [NucleusPlatformView.HWnd] — Windows, child HWND reparented under
 *    the Tao main HWND. Child HWNDs paint above the parent surface, so
 *    overlapping Compose uses the [content] overlay slot (a DirectComposition
 *    WS_POPUP). `nativePopupLayers = true` is the equivalent of
 *    `compose.layers.type=WINDOW` for popups that must float above the
 *    child.
 *
 * Variants whose backend isn't implemented (or whose runtime type
 * doesn't match the current OS) fall back to an empty `Box(modifier)`.
 *
 * Compose's `Modifier.clip()` does **not** propagate to embedded
 * native views (same limitation as `AndroidView` / `UIKitView`). Use
 * [cornerRadius] for rounded/circular clipping; pass [Dp.Infinity] to
 * make it fully circular regardless of size.
 */
@Composable
public fun NativeView(
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
        is NucleusPlatformView.GtkWidget -> GtkWidgetEmbedding(view, modifier, content)
        is NucleusPlatformView.HWnd -> HwndEmbedding(view, modifier, cornerRadius, content)
    }
}

/**
 * Windows HWND embedding path. Falls back to an empty `Box(modifier)`
 * when the runtime isn't Windows or the host scene plumbing isn't
 * available.
 *
 * The [content] slot mounts a Compose scene rendered into a top-level
 * owned overlay HWND rendered via DirectComposition against the
 * host's share group. Outside any region wrapped with
 * `Modifier.consumeOverlayPointerEvents()`, clicks fall through via
 * `WM_NCHITTEST` returning `HTTRANSPARENT` so the embedded child HWND
 * underneath gets them.
 */
@Composable
private fun HwndEmbedding(
    view: NucleusPlatformView.HWnd,
    modifier: Modifier,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val host = LocalTaoNativeViewHost.current
    val popupHost = dev.nucleusframework.window.tao.popup.LocalTaoPopupHostWindows.current
    val handle = view.hwndHandle
    val latestContent by rememberUpdatedState(content)

    if (Platform.Current != Platform.Windows || host == null || popupHost == null) {
        Box(modifier)
        return
    }
    // [handle == 0L] used to bail out here too, but DComp-backed views
    // (WebView2 via its CompositionController) intentionally expose a
    // null HWND because they live in a visual tree, not as Win32 child
    // windows. The host's `attach`/`setFrame`/`setCornerRadius` calls
    // are no-ops on a non-window handle (defensive `IsWindow` check on
    // the C side) — safe to let through. Positioning + clipping happens
    // entirely via the view-impl's own [setBounds] / [setCornerRadius]
    // overrides (e.g. WebView2 driving its DComp visual).

    val overlay =
        remember(host, popupHost) {
            NativeViewOverlayControllerWindows(host, popupHost)
        }

    DisposableEffect(host, handle, overlay) {
        host.attach(handle)
        // Overlay attach AFTER the user's subview so Z-order is correct
        // (owned popup HWNDs are guaranteed above their owner by Win32).
        overlay.attach()
        onDispose {
            overlay.dispose()
            host.detach(handle)
        }
    }

    DisposableEffect(overlay) {
        overlay.setContent {
            CompositionLocalProvider(LocalNativeViewOverlayControllerWindows provides overlay) {
                latestContent()
            }
        }
        onDispose { /* dispose handled above */ }
    }

    val density = LocalDensity.current
    val cornerRadiusPx =
        remember(cornerRadius, density) {
            when {
                cornerRadius == Dp.Unspecified -> 0f
                cornerRadius == Dp.Infinity -> Float.POSITIVE_INFINITY
                else -> with(density) { cornerRadius.toPx() }
            }
        }
    val lastRect = remember { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1) }
    val lastRadius = remember { floatArrayOf(Float.NaN) }
    Box(
        modifier =
            modifier.onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val xPx = pos.x.roundToInt()
                val yPx = pos.y.roundToInt()
                val wPx = coords.size.width.coerceAtLeast(1)
                val hPx = coords.size.height.coerceAtLeast(1)
                val rectChanged =
                    lastRect[0] != xPx ||
                        lastRect[1] != yPx ||
                        lastRect[2] != wPx ||
                        lastRect[3] != hPx
                if (rectChanged) {
                    lastRect[0] = xPx
                    lastRect[1] = yPx
                    lastRect[2] = wPx
                    lastRect[3] = hPx
                    host.setFrame(handle, xPx, yPx, wPx, hPx)
                    overlay.setBounds(xPx, yPx, wPx, hPx)
                    view.resize(wPx, hPx)
                    view.setBounds(xPx, yPx, wPx, hPx)
                }
                if (rectChanged || lastRadius[0] != cornerRadiusPx) {
                    lastRadius[0] = cornerRadiusPx
                    val radiusToApply =
                        if (cornerRadiusPx.isInfinite()) {
                            min(wPx, hPx) / 2f
                        } else {
                            cornerRadiusPx
                        }
                    // Two complementary clip paths: the host applies
                    // `SetWindowRgn` on the user HWND (works for regular
                    // GDI/HWND-painted children), AND the view-impl gets
                    // a chance to apply its own clip on whatever surface
                    // it renders into. The latter is what makes WebView2's
                    // DComp-painted content actually round its corners.
                    host.setCornerRadius(handle, radiusToApply)
                    view.setCornerRadius(radiusToApply)
                }
            },
    )
}

/**
 * macOS NSView embedding path. The native view sits **below** the
 * Compose Metal surface; this composable punches a `BlendMode.Clear`
 * hole so it shows through, then draws [content] (and any later
 * Compose siblings) on top — SwingPanel interop-blending semantics.
 *
 * Falls back to an empty `Box(modifier)` when the runtime isn't macOS
 * or the host scene plumbing isn't available.
 */
@Composable
private fun NsViewEmbedding(
    view: NucleusPlatformView.NsView,
    modifier: Modifier,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val host = LocalTaoNativeViewHost.current
    val handle = view.nsViewHandle
    val latestContent by rememberUpdatedState(content)

    if (Platform.Current != Platform.MacOS || host == null || handle == 0L) {
        Box(modifier)
        return
    }

    DisposableEffect(host, handle) {
        host.attach(handle)
        onDispose { host.detach(handle) }
    }

    val density = LocalDensity.current
    val cornerRadiusPx =
        remember(cornerRadius, density) {
            when {
                cornerRadius == Dp.Unspecified -> 0f
                cornerRadius == Dp.Infinity -> Float.POSITIVE_INFINITY
                else -> with(density) { cornerRadius.toPx() }
            }
        }
    val lastRect = remember { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1) }
    val lastRadius = remember { floatArrayOf(Float.NaN) }
    Box(
        modifier =
            modifier
                .punchNativeViewHole()
                .nativeViewPointerInterop(host, handle, lastRect)
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val xPx = pos.x.roundToInt()
                    val yPx = pos.y.roundToInt()
                    val wPx = coords.size.width.coerceAtLeast(1)
                    val hPx = coords.size.height.coerceAtLeast(1)
                    val rectChanged =
                        lastRect[0] != xPx ||
                            lastRect[1] != yPx ||
                            lastRect[2] != wPx ||
                            lastRect[3] != hPx
                    if (rectChanged) {
                        lastRect[0] = xPx
                        lastRect[1] = yPx
                        lastRect[2] = wPx
                        lastRect[3] = hPx
                        host.setFrame(handle, xPx, yPx, wPx, hPx)
                        view.resize(wPx, hPx)
                        view.setBounds(xPx, yPx, wPx, hPx)
                    }
                    if (rectChanged || lastRadius[0] != cornerRadiusPx) {
                        lastRadius[0] = cornerRadiusPx
                        val radiusToApply =
                            if (cornerRadiusPx.isInfinite()) {
                                min(wPx, hPx) / 2f
                            } else {
                                cornerRadiusPx
                            }
                        host.setCornerRadius(handle, radiusToApply)
                        view.setCornerRadius(radiusToApply)
                    }
                },
    ) {
        NativeViewOverlayContent(latestContent)
    }
}

/**
 * Linux GTK widget embedding path — direct equivalent of
 * [NsViewEmbedding]. The [content] slot **is** supported on Linux,
 * unlike the simpler "no overlay" mode the file used to ship with:
 * the embedded `GtkWidget*` paints into GTK's window buffer, the
 * Compose scene composites on top with alpha, and `content` lives
 * inline in that scene — overlap with the embedded rect is naturally
 * handled by Compose's own draw order.
 *
 * Hit-test passthrough is region-based, mirroring macOS:
 * descendants of [content] that wrap themselves in
 * [dev.nucleusframework.window.tao.consumeOverlayPointerEvents]
 * register their bounds with the host's [TaoLinuxOverlayController];
 * the EGL surface's input region is the union of those rects, so
 * outside of them every click falls through to GTK and reaches the
 * embedded widget.
 *
 * Falls back to an empty `Box(modifier)` when the runtime isn't
 * Linux or the GTK host isn't available.
 */
@Composable
private fun GtkWidgetEmbedding(
    view: NucleusPlatformView.GtkWidget,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val host = LocalTaoNativeViewHost.current
    val overlayController = LocalTaoLinuxOverlayController.current
    val handle = view.gtkWidgetHandle
    val latestContent by rememberUpdatedState(content)

    if (Platform.Current != Platform.Linux || host == null || handle == 0L) {
        Box(modifier)
        return
    }

    DisposableEffect(host, handle) {
        host.attach(handle)
        onDispose { host.detach(handle) }
    }

    val lastRect = remember { intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1) }
    Box(
        // `drawWithContent` lets us strictly order:
        //   1. Clear the destination to alpha=0 so any opaque
        //      ancestor `background(...)` painted earlier in the
        //      scene doesn't sit on top of GTK's surface buffer in
        //      the EGL composite step and hide the embedded widget.
        //   2. Then draw children (the overlay `content()`) on top.
        // Doing this with a sibling `Box(...).drawBehind { Clear }`
        // worked for opaque widgets but caused the partial-erase
        // artefact on AA glyph edges — Compose's draw ordering with
        // `matchParentSize` siblings + internal RenderNodes (ripple,
        // shadows) doesn't always serialise the way you'd expect. A
        // single `drawWithContent` is unambiguous.
        modifier =
            modifier
                .punchNativeViewHole()
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val xPx = pos.x.roundToInt()
                    val yPx = pos.y.roundToInt()
                    val wPx = coords.size.width.coerceAtLeast(1)
                    val hPx = coords.size.height.coerceAtLeast(1)
                    if (lastRect[0] != xPx ||
                        lastRect[1] != yPx ||
                        lastRect[2] != wPx ||
                        lastRect[3] != hPx
                    ) {
                        lastRect[0] = xPx
                        lastRect[1] = yPx
                        lastRect[2] = wPx
                        lastRect[3] = hPx
                        host.setFrame(handle, xPx, yPx, wPx, hPx)
                    }
                },
    ) {
        // Overlay slot — rendered inside the *same* Compose
        // scene as the rest of the window. Interactive widgets
        // register with [overlayController] via
        // [Modifier.consumeOverlayPointerEvents] so their rect
        // joins the EGL surface's input region. Outside of those
        // rects clicks fall through to the embedded GTK widget.
        NativeViewOverlayContent {
            if (overlayController != null) {
                CompositionLocalProvider(
                    LocalTaoLinuxOverlayController provides overlayController,
                ) {
                    latestContent()
                }
            } else {
                latestContent()
            }
        }
    }
}

/**
 * Clears the NativeView slot to alpha 0 so the platform view sitting
 * under the Compose surface shows through, then draws overlay children.
 */
private fun Modifier.punchNativeViewHole(): Modifier =
    drawWithContent {
        drawRect(color = Color.Transparent, blendMode = BlendMode.Clear)
        drawContent()
    }

/**
 * Isolates overlay drawing in an offscreen layer so glyph AA does not
 * blend against the `BlendMode.Clear` hole (partially-erased edges).
 */
@Composable
private fun NativeViewOverlayContent(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        content()
    }
}

/**
 * Redispatches pointer events that Compose did not consume onto the
 * embedded AppKit view. Siblings drawn *after* [NativeView] hit-test
 * first and never reach this modifier — that's how a Button/Snackbar
 * overlapping the native view stays interactive.
 */
private fun Modifier.nativeViewPointerInterop(
    host: TaoNativeViewHost,
    handle: Long,
    lastRect: IntArray,
): Modifier =
    pointerInput(host, handle) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull()
                if (change == null || event.changes.any { it.isConsumed }) {
                    continue
                }
                val xPx = lastRect[0] + change.position.x
                val yPx = lastRect[1] + change.position.y
                val button =
                    when (event.button) {
                        PointerButton.Secondary -> 2
                        PointerButton.Primary -> 1
                        else -> if (change.pressed) 1 else 0
                    }
                val dispatched =
                    when (event.type) {
                        PointerEventType.Press -> {
                            host.noteNativePointerDispatch()
                            host.dispatchPointerToNative(handle, 1, xPx, yPx, button, true)
                            true
                        }
                        PointerEventType.Release -> {
                            host.dispatchPointerToNative(handle, 2, xPx, yPx, button, false)
                            true
                        }
                        PointerEventType.Move -> {
                            host.dispatchPointerToNative(
                                handle,
                                3,
                                xPx,
                                yPx,
                                button,
                                change.pressed,
                            )
                            true
                        }
                        PointerEventType.Scroll -> {
                            host.dispatchScrollToNative(
                                handle,
                                xPx,
                                yPx,
                                change.scrollDelta.x,
                                change.scrollDelta.y,
                            )
                            true
                        }
                        else -> false
                    }
                if (dispatched) event.changes.forEach { it.consume() }
            }
        }
    }

/** Plumbing CompositionLocal — provided by `DecoratedWindow`. */
internal val LocalTaoNativeViewHost = compositionLocalOf<TaoNativeViewHost?> { null }

/** Decouples [NativeView] from the platform-specific scene host. */
internal interface TaoNativeViewHost {
    fun attach(childHandle: Long)

    fun detach(childHandle: Long)

    fun setFrame(
        handle: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    fun setCornerRadius(
        handle: Long,
        radiusPx: Float,
    )

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
    fun scheduleInterop(action: () -> Unit) {
        action()
    }

    /**
     * Forwards a Compose pointer event that landed on this native view
     * and was not consumed by overlapping Compose content. macOS only;
     * no-op elsewhere. [type] is 1 down / 2 up / 3 move.
     */
    fun dispatchPointerToNative(
        handle: Long,
        type: Int,
        xPx: Float,
        yPx: Float,
        button: Int,
        pressed: Boolean,
    ) {
    }

    /** Forwards an unconsumed Compose scroll onto the native view. macOS only. */
    fun dispatchScrollToNative(
        handle: Long,
        xPx: Float,
        yPx: Float,
        dx: Float,
        dy: Float,
    ) {
    }

    /**
     * Marks that the in-flight pointer Press was handed to a native
     * view (so the host must not steal first-responder back).
     */
    fun noteNativePointerDispatch() {}
}

/**
 * CompositionLocal exposing the overlay controller (if any) to
 * descendants of [NativeView]'s `content` slot. Used by
 * [Modifier.consumeOverlayPointerEvents] to register itself as an
 * interactive region.
 */
internal val LocalNativeViewOverlayController =
    compositionLocalOf<NativeViewOverlayController?> { null }

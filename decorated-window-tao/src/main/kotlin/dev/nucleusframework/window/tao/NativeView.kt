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
import dev.nucleusframework.window.tao.deco.LocalTaoLinuxOverlayController
import dev.nucleusframework.window.tao.deco.NativeViewOverlayController
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
 *    a GtkEventBox covering the NativeView rect lets Compose see hits
 *    first, then unconsumed events are synthesised back onto the widget.
 *  - [NucleusPlatformView.HWnd] — Windows, child HWND reparented under
 *    the Tao main HWND. A DirectComposition overlay covering the
 *    NativeView rect composites the host scene on top (Win32 children
 *    always paint above their parent). Same sibling / `content` /
 *    in-scene popup blending as macOS.
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
 * Windows HWND embedding path. Child HWNDs paint above the parent
 * surface, so a DirectComposition overlay (owned by the scene host)
 * composites the host Compose scene over the NativeView rect — the
 * SwingPanel interop-blending model. [content] and later siblings
 * draw in the host scene; unconsumed pointer events are synthesised
 * onto the child HWND (or the owner, for hwnd=0 WebView2).
 *
 * Falls back to an empty `Box(modifier)` when the runtime isn't
 * Windows or the host scene plumbing isn't available.
 */
@Composable
private fun HwndEmbedding(
    view: NucleusPlatformView.HWnd,
    modifier: Modifier,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
) {
    val host = LocalTaoNativeViewHost.current
    val handle = view.hwndHandle
    val latestContent by rememberUpdatedState(content)

    if (Platform.Current != Platform.Windows || host == null) {
        Box(modifier)
        return
    }
    // [handle == 0L] is intentional for DComp-backed views (WebView2
    // CompositionController): they have no Win32 child HWND. attach /
    // setFrame / setCornerRadius no-op on a non-window handle; bounds
    // and clip go through the view-impl's own overrides.

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
 * Linux GTK widget embedding path — same hole-punch blending as
 * [NsViewEmbedding]. The embedded widget paints into GTK's buffer;
 * Compose composites on top with alpha. A GtkEventBox covering the
 * NativeView rect captures hits for Compose (siblings and [content]);
 * unconsumed events are synthesised back onto the widget.
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
                .nativeViewPointerInterop(host, handle, lastRect)
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
        // Overlay slot — same Compose scene as the rest of the window.
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
 * embedded native view. Siblings drawn *after* [NativeView] hit-test
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

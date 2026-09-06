@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.event.AWT_PIXEL_TO_ROTATION
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
        is NucleusPlatformView.NsView ->
            EmbeddedNativeView(
                handle = view.nsViewHandle,
                view = view,
                modifier = modifier,
                cornerRadius = cornerRadius,
                content = content,
                enabled = Platform.Current == Platform.MacOS && view.nsViewHandle != 0L,
                applyCornerRadius = true,
            )
        is NucleusPlatformView.GtkWidget ->
            EmbeddedNativeView(
                handle = view.gtkWidgetHandle,
                view = view,
                modifier = modifier,
                cornerRadius = cornerRadius,
                content = content,
                enabled = Platform.Current == Platform.Linux && view.gtkWidgetHandle != 0L,
                applyCornerRadius = false,
            )
        is NucleusPlatformView.HWnd ->
            EmbeddedNativeView(
                handle = view.hwndHandle,
                view = view,
                modifier = modifier,
                cornerRadius = cornerRadius,
                content = content,
                // hwnd == 0 is intentional for DComp-backed views (WebView2
                // CompositionController): they have no Win32 child HWND.
                enabled = Platform.Current == Platform.Windows,
                applyCornerRadius = true,
            )
    }
}

/**
 * Shared host-scene embedding: hole-punch, pointer redispatch, frame
 * sync, [content] in the same Compose scene. Platform hosts only attach
 * the native child and (Windows/Linux) capture hits for Compose.
 */
@Composable
private fun EmbeddedNativeView(
    handle: Long,
    view: NucleusPlatformView,
    modifier: Modifier,
    cornerRadius: Dp,
    content: @Composable () -> Unit,
    enabled: Boolean,
    applyCornerRadius: Boolean,
) {
    val host = LocalTaoNativeViewHost.current
    val latestContent by rememberUpdatedState(content)
    if (!enabled || host == null) {
        Box(modifier)
        return
    }

    val regionToken = remember { Any() }
    DisposableEffect(host, regionToken) {
        host.attach(handle, regionToken)
        onDispose { host.detach(handle, regionToken) }
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
                        host.setFrame(handle, xPx, yPx, wPx, hPx, regionToken)
                        view.resize(wPx, hPx)
                        view.setBounds(xPx, yPx, wPx, hPx)
                    }
                    if (applyCornerRadius && (rectChanged || lastRadius[0] != cornerRadiusPx)) {
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
        // Pointer redispatch lives on a layer *below* [content]: Compose
        // hit-testing stops at the topmost child with a pointer node, so
        // this layer only sees positions where no overlapping Compose
        // content handles input — i.e. the punched hole itself. Chrome
        // drawn over the native view (text fields, buttons) keeps its
        // events exclusively; they are never replayed onto the native
        // view (e.g. a right-click on a text field must open only the
        // Compose context menu, not the native one underneath too).
        Box(
            Modifier
                .matchParentSize()
                .nativeViewPointerInterop(host, handle, lastRect),
        )
        latestContent()
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
 * Redispatches pointer events that Compose did not consume onto the
 * embedded native view. Mounted below the `content` slot, so both the
 * slot and siblings drawn *after* [NativeView] hit-test first and never
 * reach this modifier — that's how a Button/Snackbar overlapping the
 * native view stays interactive and why their events are never also
 * replayed onto the native view.
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
                                TaoNativeViewHost.SCROLL_WHEEL,
                            )
                            true
                        }
                        // Trackpad pan (#654): the whole gesture belongs to the
                        // native view — begin and end included, so its own
                        // scroll view finishes rubber-banding / fades its
                        // scrollers — and is consumed so the Compose scrollable
                        // above never opens a pan session of its own. Offsets
                        // are handed over in AWT wheel units: panOffset is
                        // 10 dp per unit (see TaoSceneScrollRouter).
                        PointerEventType.PanStart,
                        PointerEventType.PanMove,
                        PointerEventType.PanEnd,
                        -> {
                            val unitPx = AWT_PIXEL_TO_ROTATION * density
                            host.dispatchScrollToNative(
                                handle,
                                xPx,
                                yPx,
                                change.panOffset.x / unitPx,
                                change.panOffset.y / unitPx,
                                when (event.type) {
                                    PointerEventType.PanStart -> TaoNativeViewHost.PAN_START
                                    PointerEventType.PanEnd -> TaoNativeViewHost.PAN_END
                                    else -> TaoNativeViewHost.PAN_MOVE
                                },
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
    /**
     * Mounts [childHandle] in the native window. [regionToken] uniquely
     * identifies this embedding for hit-capture bookkeeping (EventBox /
     * overlay RGN) so hwnd=0 WebView2 slots do not collide.
     */
    fun attach(
        childHandle: Long,
        regionToken: Any,
    )

    fun detach(
        childHandle: Long,
        regionToken: Any,
    )

    fun setFrame(
        handle: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
        regionToken: Any,
    )

    fun setCornerRadius(
        handle: Long,
        radiusPx: Float,
    )

    /**
     * Forwards a Compose pointer event that landed on this native view
     * and was not consumed by overlapping Compose content.
     * [type] is 1 down / 2 up / 3 move.
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

    /**
     * Forwards an unconsumed Compose scroll onto the native view. [dx] / [dy]
     * are AWT wheel units; [phase] is one of [SCROLL_WHEEL], [PAN_START],
     * [PAN_MOVE], [PAN_END] so the native side can hand the embedded view a
     * gesture with a proper begin and end (macOS replays the live AppKit event
     * when it is the matching one, else synthesises a phased scroll).
     */
    @Suppress("LongParameterList")
    fun dispatchScrollToNative(
        handle: Long,
        xPx: Float,
        yPx: Float,
        dx: Float,
        dy: Float,
        phase: Int,
    ) {
    }

    companion object {
        /** Mouse-wheel notch / phase-less precise scroll. */
        const val SCROLL_WHEEL: Int = 0
        const val PAN_START: Int = 1
        const val PAN_MOVE: Int = 2
        const val PAN_END: Int = 3
    }

    /**
     * Marks that the in-flight pointer Press was handed to a native
     * view (so the host must not steal first-responder back).
     */
    fun noteNativePointerDispatch() {}
}

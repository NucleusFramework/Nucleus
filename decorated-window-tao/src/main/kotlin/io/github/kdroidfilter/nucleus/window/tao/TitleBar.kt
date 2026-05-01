package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState
import io.github.kdroidfilter.nucleus.window.LocalTitleBarInfo
import io.github.kdroidfilter.nucleus.window.TitleBarChildDataNode
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.TitleBarScopeImpl
import io.github.kdroidfilter.nucleus.window.utils.linux.rememberLinuxButtonLayout
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// Native traffic-lights occupy roughly the leftmost 78 points on macOS. On
// Windows the title bar zone is fully under Compose's control — no native
// reservation, the user's TitleBar content places min/max/close itself.
private val NATIVE_BUTTONS_INSET_MACOS: Dp = 78.dp
private val NATIVE_BUTTONS_INSET_NONE: Dp = 0.dp

// KDE breeze gives the leading edge of its title bar a small padding so the
// edge-most window control button doesn't sit flush against the window border.
// Mirrors `decorated-window-core/TitleBarLinuxCommon.kt::kdePaddingForButtonLayout`.
private val LINUX_KDE_EDGE_PADDING: Dp = 4.dp
private val isLinuxKde: Boolean =
    Platform.Current == Platform.Linux &&
        LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    background: Color = Color.Transparent,
    titleColor: Color = Color(0xFFE6E6E6),
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = { state ->
        BasicText(
            text = title,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = TextStyle(
                color = if (state.isActive) titleColor else titleColor.copy(alpha = 0.5f),
                fontSize = 12.sp,
            ),
        )
    },
) {
    val taoWindow = window
    val info = LocalTitleBarInfo.current
    val currentState = state
    val scope = remember(info.title, info.icon) { TitleBarScopeImpl(title = info.title, icon = info.icon) }

    // Publish our requested height up to DecoratedWindow, which applies the
    // native button-centering constraints once the window is shown (post
    // first Resized event — earlier the AppKit title-bar hierarchy isn't
    // ready yet and applying constraints during warm-up corrupts it).
    val heightHolder = LocalRequestedTitleBarHeight.current
    SideEffect { heightHolder.value = height.value }

    // GNOME `button-layout` is read once per composition (and re-emitted by
    // the GSettings observer when the user changes it in Tweaks) — we need it
    // both to pick the side WindowControlsLinux is rendered on, and to apply
    // the KDE 4 dp edge padding on the right side.
    val linuxLayout = if (Platform.Current == Platform.Linux) rememberLinuxButtonLayout() else null

    val (leftInset, rightInset) = when (Platform.Current) {
        Platform.MacOS -> NATIVE_BUTTONS_INSET_MACOS to NATIVE_BUTTONS_INSET_MACOS
        Platform.Linux -> {
            // KDE-only edge padding, mirroring `kdePaddingForButtonLayout()`
            // from decorated-window-core. Applied on whichever side the
            // controls live so they don't sit flush against the border.
            if (isLinuxKde && linuxLayout != null) {
                if (linuxLayout.controlsOnRight) {
                    NATIVE_BUTTONS_INSET_NONE to LINUX_KDE_EDGE_PADDING
                } else {
                    LINUX_KDE_EDGE_PADDING to NATIVE_BUTTONS_INSET_NONE
                }
            } else {
                NATIVE_BUTTONS_INSET_NONE to NATIVE_BUTTONS_INSET_NONE
            }
        }
        else -> NATIVE_BUTTONS_INSET_NONE to NATIVE_BUTTONS_INSET_NONE
    }
    val viewConfig = LocalViewConfiguration.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(background)
            .windowDragHandler(
                window = taoWindow,
                doubleTapMinMs = viewConfig.doubleTapMinTimeMillis,
                doubleTapMaxMs = viewConfig.doubleTapTimeoutMillis,
            ),
    ) {
        Layout(
            content = {
                // Linux + controls-on-left (rare KDE setup): declare controls
                // BEFORE user content so the measurePolicy places them at the
                // start edge — Start items are placed in declaration order.
                if (linuxLayout != null && !linuxLayout.controlsOnRight) {
                    with(scope) {
                        WindowControlsLinux(
                            win = taoWindow,
                            state = currentState,
                            isResizable = taoWindow.isResizable,
                            layout = linuxLayout,
                        )
                    }
                }

                scope.content(currentState)

                // Windows: native min/max/close are not painted by DWM (the
                // WndProc subclass returns HTCLIENT for the title bar zone),
                // so the library injects its own Compose buttons here. The
                // user does not see them on macOS, where AppKit traffic-light
                // buttons are positioned by `nativeApplyButtonLayout`.
                if (Platform.Current == Platform.Windows) {
                    with(scope) {
                        WindowControlsWindows(
                            win = taoWindow,
                            state = currentState,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }

                // Linux + controls-on-right (default): declare AFTER user
                // content. End items are placed via `ends.reversed()` so the
                // last declared sits at the rightmost edge — exactly what we
                // want for the close button (which is `layout.buttons[0]`,
                // declared first inside WindowControlsLinux).
                if (linuxLayout != null && linuxLayout.controlsOnRight) {
                    with(scope) {
                        WindowControlsLinux(
                            win = taoWindow,
                            state = currentState,
                            isResizable = taoWindow.isResizable,
                            layout = linuxLayout,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(height),
            measurePolicy = remember(leftInset, rightInset) { TaoTitleBarMeasurePolicy(leftInset, rightInset) },
        )
    }
}

// ── Layout ────────────────────────────────────────────────────────────────

private class TaoTitleBarMeasurePolicy(
    private val leftInset: Dp,
    private val rightInset: Dp,
) : androidx.compose.ui.layout.MeasurePolicy {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val leftPx = leftInset.roundToPx()
        val rightPx = rightInset.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val starts = mutableListOf<Placeable>()
        val ends = mutableListOf<Placeable>()
        val centers = mutableListOf<Placeable>()
        var maxH = constraints.minHeight

        // End first — they get priority on the right edge.
        var endTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != Alignment.End) continue
            val p = m.measure(childConstraints.offset(horizontal = -endTotal - leftPx - rightPx))
            endTotal += p.width
            maxH = max(maxH, p.height)
            ends += p
        }

        // Start.
        var startTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != Alignment.Start) continue
            val p = m.measure(childConstraints.offset(horizontal = -startTotal - leftPx - rightPx - endTotal))
            startTotal += p.width
            maxH = max(maxH, p.height)
            starts += p
        }

        // Center (default).
        var centerTotal = 0
        for (m in measurables) {
            val align = (m.parentData as? TitleBarChildDataNode)?.horizontalAlignment
            if (align != null && align != Alignment.CenterHorizontally) continue
            val p = m.measure(childConstraints)
            centerTotal += p.width
            maxH = max(maxH, p.height)
            centers += p
        }

        val width = constraints.maxWidth
        val height = max(maxH, constraints.minHeight)

        return layout(width, height) {
            val y: (Int) -> Int = { ph -> (height - ph) / 2 }

            var sx = leftPx
            starts.forEach { p ->
                p.place(sx, y(p.height))
                sx += p.width
            }

            var ex = width - rightPx
            ends.reversed().forEach { p ->
                ex -= p.width
                p.place(ex, y(p.height))
            }

            val centerStartLimit = leftPx + startTotal
            val centerEndLimit = width - rightPx - endTotal - centerTotal
            var cx = (width - centerTotal) / 2
            if (centerStartLimit <= centerEndLimit) {
                cx = cx.coerceIn(centerStartLimit, centerEndLimit)
                centers.forEach { p ->
                    p.place(cx, y(p.height))
                    cx += p.width
                }
            }
        }
    }
}

// ── Drag ──────────────────────────────────────────────────────────────────

private fun Modifier.windowDragHandler(
    window: TaoWindow,
    doubleTapMinMs: Long,
    doubleTapMaxMs: Long,
): Modifier =
    pointerInput(window) {
        // We always consume Press events on the title bar to dispatch them to
        // `dragWindow()` (Tao posts the platform-specific drag-start message).
        // Because we consume, the native window machinery never sees the
        // sequence as a real title-bar click → its native double-click→zoom
        // (macOS) or →maximize (Windows) doesn't fire. Detect it in Compose
        // and toggle maximize ourselves on every platform. Mirrors
        // `decorated-window-jni`'s `TitleBar.{MacOS,Windows}.kt`.
        val ctx = currentCoroutineContext()
        var lastPress = 0L
        awaitPointerEventScope {
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                if (event.type == PointerEventType.Press && !change.isConsumed) {
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastPress in doubleTapMinMs..doubleTapMaxMs) {
                        window.setMaximized(!window.isMaximized)
                        lastPress = 0L
                    } else {
                        window.dragWindow()
                        lastPress = now
                    }
                }
            }
        }
    }

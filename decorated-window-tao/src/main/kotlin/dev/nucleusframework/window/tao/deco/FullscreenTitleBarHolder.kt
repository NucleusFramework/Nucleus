package dev.nucleusframework.window.tao.deco

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a bar revealed by touch stays before hiding itself. */
private const val TOUCH_REVEAL_MS = 3_000L

/**
 * Holds the title-bar rendering captured by the [TitleBar] composable when
 * `Modifier.newFullscreenControls()` is active and the window is fullscreen.
 *
 * [DecoratedWindow] reads from this holder and renders the bar as a sliding
 * overlay above the user content. [compositionLocalContext] preserves the
 * CompositionLocals from the original call site so user-provided values
 * (themes, etc.) remain accessible inside the overlay.
 *
 * Mirrors the legacy AWT backend's `FullscreenTitleBarHolder`.
 */
internal class FullscreenTitleBarHolder {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    var titleBarHeight: Dp by mutableStateOf(0.dp)
    var compositionLocalContext: CompositionLocalContext? by mutableStateOf(null)
}

internal val LocalFullscreenTitleBarHolder = compositionLocalOf<FullscreenTitleBarHolder?> { null }

/**
 * Wraps user content and renders [holder] as a sliding title-bar overlay when
 * the window is fullscreen and the user opted in via `Modifier.newFullscreenControls()`.
 *
 * Pointer Y position near the top edge toggles visibility; the bar slides in
 * via [animateDpAsState]. On a touch screen a tap anywhere reveals it, another
 * tap outside it hides it, and it hides itself after [TOUCH_REVEAL_MS]. Mirrors the legacy AWT backend's
 * `FullscreenTitleBarOverlay` and the pointer-tracking Box in `DecoratedWindow`.
 *
 * Pointer tracking uses [PointerEventPass.Initial] so it observes events
 * without consuming them — user content still receives all clicks.
 */
@Suppress("FunctionNaming")
@Composable
internal fun FullscreenOverlayHost(
    holder: FullscreenTitleBarHolder,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    // Bumped by every touch that reveals the bar or lands on it; each bump
    // restarts the auto-hide below.
    var touchReveal by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Clear residual overlay content when leaving fullscreen so the next
    // composition starts from a clean slate (the TitleBar repopulates it).
    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) {
            visible = false
            holder.content = null
        }
    }

    // A touch screen has no pointer to follow, so a tapped-in bar goes away on
    // its own, as GNOME's fullscreen header bars do.
    LaunchedEffect(touchReveal) {
        if (touchReveal > 0) {
            delay(TOUCH_REVEAL_MS)
            visible = false
        }
    }

    val rootModifier =
        if (isFullscreen) {
            modifier.pointerInput(holder.titleBarHeight) {
                val titleBarHeightPx = with(density) { holder.titleBarHeight.toPx() }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val onBar = change.position.y < titleBarHeightPx
                        if (change.type != PointerType.Touch) {
                            visible = onBar
                        } else if (event.type == PointerEventType.Press) {
                            // GNOME HIG: a tap anywhere reveals the controls,
                            // another one outside them hides them again; a tap
                            // on the shown bar is for its buttons.
                            if (!(visible && onBar)) visible = !visible
                            if (visible) touchReveal++
                        }
                    }
                }
            }
        } else {
            modifier
        }

    Box(modifier = rootModifier) {
        content()

        if (isFullscreen && holder.content != null) {
            val offsetY by animateDpAsState(
                targetValue = if (visible) 0.dp else -holder.titleBarHeight,
                animationSpec = tween(durationMillis = 200),
            )
            val ctx = holder.compositionLocalContext
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .offset(y = offsetY),
            ) {
                if (ctx != null) {
                    CompositionLocalProvider(ctx) {
                        holder.content?.invoke()
                    }
                } else {
                    holder.content?.invoke()
                }
            }
        }
    }
}

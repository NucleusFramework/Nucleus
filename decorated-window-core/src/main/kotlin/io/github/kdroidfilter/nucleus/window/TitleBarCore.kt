package io.github.kdroidfilter.nucleus.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.styling.LocalTitleBarStyle
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.awt.Window

private const val GRADIENT_MIDPOINT = 0.5f

val LocalContentColor = staticCompositionLocalOf { Color.Black }

/**
 * The resolved layout direction for window control buttons.
 * Provided by [GenericTitleBarImpl] so that control button composables
 * can apply this as [LocalLayoutDirection] around their content,
 * independently of the app's content direction.
 */
val LocalControlButtonsDirection = staticCompositionLocalOf { LayoutDirection.Ltr }

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GenericTitleBarImpl(
    window: Window,
    state: DecoratedWindowState,
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    val titleBarInfo = LocalTitleBarInfo.current

    val background by style.colors.backgroundFor(state)

    val density = LocalDensity.current

    val backgroundBrush =
        remember(background, gradientStartColor) {
            if (gradientStartColor.isUnspecified) {
                SolidColor(background)
            } else {
                with(density) {
                    Brush.horizontalGradient(
                        0.0f to background,
                        GRADIENT_MIDPOINT to gradientStartColor,
                        1.0f to background,
                        startX = style.metrics.gradientStartX.toPx(),
                        endX = style.metrics.gradientEndX.toPx(),
                    )
                }
            }
        }

    Box(
        modifier =
            modifier
                .background(backgroundBrush)
                .then(
                    // Block focus on Windows/Linux so Tab navigation cannot enter the Compose-driven
                    // title bar drag area. On macOS the traffic-light buttons are native (outside the
                    // Compose hit-test area), and focus must remain enabled so TextField/TextArea
                    // children in the title bar can receive keyboard input (issue #206 / PR #208).
                    if (Platform.Current == Platform.MacOS) {
                        Modifier
                    } else {
                        Modifier.focusProperties { canFocus = false }
                    },
                ).layoutId(TITLE_BAR_LAYOUT_ID)
                .height(style.metrics.height)
                .onSizeChanged { with(density) { applyTitleBar(it.height.toDp(), state) } }
                .fillMaxWidth(),
    ) {
        backgroundContent()
        Layout(
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides style.colors.content,
                    LocalControlButtonsDirection provides controlButtonsDirection,
                ) {
                    val scope = TitleBarScopeImpl(titleBarInfo.title, titleBarInfo.icon)
                    scope.content(state)
                }
            },
            modifier = Modifier.fillMaxSize().onPlaced { onPlace?.invoke() },
            measurePolicy =
                rememberTitleBarMeasurePolicy(
                    window = window,
                    state = state,
                    applyTitleBar = applyTitleBar,
                    controlButtonsDirection = controlButtonsDirection,
                    layoutPolicy = layoutPolicy,
                ),
        )
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GenericTitleBarImpl(
    window: Window,
    state: DecoratedWindowState,
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    GenericTitleBarImpl(
        window = window,
        state = state,
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
        content = content,
    )
}

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBarImpl(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    layoutPolicy: TitleBarLayoutPolicy = TitleBarLayoutPolicy.Default,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    GenericTitleBarImpl(
        window = window,
        state = state,
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = layoutPolicy,
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
        content = content,
    )
}

@Suppress("FunctionNaming")
@Composable
fun DecoratedWindowScope.TitleBarImpl(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: LayoutDirection = LocalLayoutDirection.current,
    applyTitleBar: (Dp, DecoratedWindowState) -> PaddingValues,
    onPlace: (() -> Unit)? = null,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit,
) {
    TitleBarImpl(
        modifier = modifier,
        gradientStartColor = gradientStartColor,
        style = style,
        controlButtonsDirection = controlButtonsDirection,
        layoutPolicy = TitleBarLayoutPolicy.Default,
        applyTitleBar = applyTitleBar,
        onPlace = onPlace,
        backgroundContent = backgroundContent,
        content = content,
    )
}

@Stable
interface TitleBarScope {
    val title: String

    val icon: Painter?

    fun Modifier.align(alignment: Alignment.Horizontal): Modifier

    /**
     * Click handler for title bar elements that works reliably in macOS
     * fullscreen on non-notch screens.
     *
     * Standard [clickable][androidx.compose.foundation.clickable] requires a
     * complete Press -> Release (tap) gesture. On some JDK/macOS combinations,
     * the system injects phantom pointer-exit events between Press and Release
     * in fullscreen, which cancels the tap gesture and prevents `onClick` from
     * firing.
     *
     * This modifier fires [onClick] on the **press** event instead, making it
     * immune to phantom exit events. It is the recommended replacement for
     * `clickable` on interactive elements placed inside a title bar.
     */
    fun Modifier.titleBarClickable(onClick: () -> Unit): Modifier
}

class TitleBarScopeImpl(
    override val title: String,
    override val icon: Painter?,
) : TitleBarScope {
    @Suppress("MaxLineLength")
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier =
        this then TitleBarChildDataElement(alignment)

    override fun Modifier.titleBarClickable(onClick: () -> Unit): Modifier =
        pointerInput(onClick) {
            val ctx = currentCoroutineContext()
            awaitPointerEventScope {
                while (ctx.isActive) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Press) {
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.isConsumed) {
                            change.consume()
                            onClick()
                        }
                    }
                }
            }
        }
}

class TitleBarChildDataElement(
    val horizontalAlignment: Alignment.Horizontal,
) : ModifierNodeElement<TitleBarChildDataNode>() {
    override fun create(): TitleBarChildDataNode = TitleBarChildDataNode(horizontalAlignment)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val otherModifier = other as? TitleBarChildDataElement ?: return false
        return horizontalAlignment == otherModifier.horizontalAlignment
    }

    override fun hashCode(): Int = horizontalAlignment.hashCode()

    override fun update(node: TitleBarChildDataNode) {
        node.horizontalAlignment = horizontalAlignment
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "align"
        value = horizontalAlignment
    }
}

class TitleBarChildDataNode(
    var horizontalAlignment: Alignment.Horizontal,
) : Modifier.Node(),
    ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?) = this@TitleBarChildDataNode
}

// Handles window dragging via Compose pointer events.
// Drag starts only when the press is not consumed by a child composable (e.g. a button),
// so interactive elements in the title bar keep working correctly.
fun Modifier.windowDragHandler(window: Window): Modifier =
    pointerInput(window) {
        val ctx = currentCoroutineContext()
        awaitPointerEventScope {
            var dragging = false
            var startScreenX = 0
            var startScreenY = 0
            var startWindowX = 0
            var startWindowY = 0

            @Suppress("LoopWithTooManyJumpStatements")
            while (ctx.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue

                when (event.type) {
                    PointerEventType.Press -> {
                        if (!change.isConsumed) {
                            val loc =
                                java.awt.MouseInfo
                                    .getPointerInfo()
                                    ?.location
                            startScreenX = loc?.x ?: 0
                            startScreenY = loc?.y ?: 0
                            startWindowX = window.x
                            startWindowY = window.y
                            dragging = true
                        }
                    }
                    PointerEventType.Move -> {
                        if (dragging) {
                            val loc =
                                java.awt.MouseInfo
                                    .getPointerInfo()
                                    ?.location ?: continue
                            window.setLocation(
                                startWindowX + (loc.x - startScreenX),
                                startWindowY + (loc.y - startScreenY),
                            )
                        }
                    }
                    PointerEventType.Release -> {
                        dragging = false
                    }
                    else -> Unit
                }
            }
        }
    }

package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset

const val TITLE_BAR_COMPONENT_LAYOUT_ID_PREFIX = "__TITLE_BAR_"

const val TITLE_BAR_LAYOUT_ID = "__TITLE_BAR_CONTENT__"

const val TITLE_BAR_BORDER_LAYOUT_ID = "__TITLE_BAR_BORDER__"

/**
 * Backend-agnostic scope exposed inside a decorated window.
 * Each backend (AWT-bound: jbr/jni, native: tao) provides its own
 * sub-interface adding a backend-specific window handle.
 */
@Stable
interface DecoratedWindowScope {
    val state: DecoratedWindowState
}

object DecoratedWindowMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) {
            return layout(width = constraints.minWidth, height = constraints.minHeight) {}
        }

        val titleBars = measurables.filter { it.layoutId == TITLE_BAR_LAYOUT_ID }
        if (titleBars.size > 1) {
            error("Window just can have only one title bar")
        }
        val titleBar = titleBars.firstOrNull()
        val titleBarBorder = measurables.firstOrNull { it.layoutId == TITLE_BAR_BORDER_LAYOUT_ID }

        val contentConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val titleBarPlaceable = titleBar?.measure(contentConstraints)
        val titleBarHeight = titleBarPlaceable?.height ?: 0

        val titleBarBorderPlaceable = titleBarBorder?.measure(contentConstraints)
        val titleBarBorderHeight = titleBarBorderPlaceable?.height ?: 0

        val measuredPlaceable = mutableListOf<Placeable>()

        for (it in measurables) {
            if (it.layoutId.toString().startsWith(TITLE_BAR_COMPONENT_LAYOUT_ID_PREFIX)) continue
            val offsetConstraints = contentConstraints.offset(vertical = -titleBarHeight - titleBarBorderHeight)
            val placeable = it.measure(offsetConstraints)
            measuredPlaceable += placeable
        }

        return layout(constraints.maxWidth, constraints.maxHeight) {
            titleBarPlaceable?.placeRelative(0, 0)
            titleBarBorderPlaceable?.placeRelative(0, titleBarHeight)

            measuredPlaceable.forEach { it.placeRelative(0, titleBarHeight + titleBarBorderHeight) }
        }
    }
}

@Immutable
@JvmInline
value class DecoratedWindowState(
    val state: ULong,
) {
    val isActive: Boolean
        get() = state and Active != 0UL

    val isFullscreen: Boolean
        get() = state and Fullscreen != 0UL

    val isMinimized: Boolean
        get() = state and Minimize != 0UL

    val isMaximized: Boolean
        get() = state and Maximize != 0UL

    fun copy(
        fullscreen: Boolean = isFullscreen,
        minimized: Boolean = isMinimized,
        maximized: Boolean = isMaximized,
        active: Boolean = isActive,
    ): DecoratedWindowState = of(fullscreen = fullscreen, minimized = minimized, maximized = maximized, active = active)

    override fun toString(): String = "${javaClass.simpleName}(isFullscreen=$isFullscreen, isActive=$isActive)"

    companion object {
        val Active: ULong = 1UL shl 0
        val Fullscreen: ULong = 1UL shl 1
        val Minimize: ULong = 1UL shl 2
        val Maximize: ULong = 1UL shl 3

        fun of(
            fullscreen: Boolean = false,
            minimized: Boolean = false,
            maximized: Boolean = false,
            active: Boolean = true,
        ): DecoratedWindowState =
            DecoratedWindowState(
                (if (fullscreen) Fullscreen else 0UL) or
                    (if (minimized) Minimize else 0UL) or
                    (if (maximized) Maximize else 0UL) or
                    (if (active) Active else 0UL),
            )
    }
}

@Stable
class TitleBarInfo(
    title: String,
    icon: Painter?,
) {
    var title by mutableStateOf(title)
    var icon by mutableStateOf(icon)
    val clientRegions: MutableMap<String, Rect> = mutableMapOf()
}

val LocalTitleBarInfo: ProvidableCompositionLocal<TitleBarInfo> =
    compositionLocalOf {
        error("LocalTitleBarInfo not provided, TitleBar must be used in DecoratedWindow")
    }

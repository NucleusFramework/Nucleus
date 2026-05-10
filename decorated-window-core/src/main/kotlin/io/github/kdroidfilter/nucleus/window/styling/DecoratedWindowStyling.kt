package io.github.kdroidfilter.nucleus.window.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.DecoratedWindowState

data class DecoratedWindowStyle(
    val colors: DecoratedWindowColors,
    val metrics: DecoratedWindowMetrics,
)

data class DecoratedWindowColors(
    val border: Color,
    val borderInactive: Color,
) {
    @Composable
    fun borderFor(state: DecoratedWindowState): State<Color> =
        rememberUpdatedState(
            when {
                !state.isActive -> borderInactive
                else -> border
            },
        )
}

data class DecoratedWindowMetrics(
    val borderWidth: Dp = 1.dp,
)

val LocalDecoratedWindowStyle =
    staticCompositionLocalOf<DecoratedWindowStyle> {
        io.github.kdroidfilter.nucleus.window.DecoratedWindowDefaults
            .darkWindowStyle()
    }

package dev.nucleusframework.window.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DecoratedWindowState

public data class DecoratedWindowStyle(
    val colors: DecoratedWindowColors,
    val metrics: DecoratedWindowMetrics,
)

public data class DecoratedWindowColors(
    val border: Color,
    val borderInactive: Color,
    val background: Color = Color.White,
) {
    @Composable
    public fun borderFor(state: DecoratedWindowState): State<Color> =
        rememberUpdatedState(
            when {
                !state.isActive -> borderInactive
                else -> border
            },
        )
}

public data class DecoratedWindowMetrics(
    val borderWidth: Dp = 1.dp,
)

public val LocalDecoratedWindowStyle: ProvidableCompositionLocal<DecoratedWindowStyle> =
    staticCompositionLocalOf<DecoratedWindowStyle> {
        dev.nucleusframework.window.DecoratedWindowDefaults
            .darkWindowStyle()
    }

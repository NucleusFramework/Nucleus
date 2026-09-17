package dev.nucleusframework.window

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

public enum class WindowControlsSide {
    Start,
    End,
    Unspecified,
}

public val LocalWindowControlsSide: ProvidableCompositionLocal<WindowControlsSide> =
    staticCompositionLocalOf { WindowControlsSide.Unspecified }

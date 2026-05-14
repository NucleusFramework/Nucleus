package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.staticCompositionLocalOf

enum class WindowControlsSide {
    Start,
    End,
    Unspecified,
}

val LocalWindowControlsSide = staticCompositionLocalOf { WindowControlsSide.Unspecified }

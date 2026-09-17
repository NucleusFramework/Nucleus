package dev.nucleusframework.window.internal

import androidx.compose.ui.graphics.Color

private const val LUMINANCE_THRESHOLD = 0.5f
private const val LUMINANCE_R = 0.299f
private const val LUMINANCE_G = 0.587f
private const val LUMINANCE_B = 0.114f

public fun Color.isDark(): Boolean {
    val luminance = LUMINANCE_R * red + LUMINANCE_G * green + LUMINANCE_B * blue
    return luminance < LUMINANCE_THRESHOLD
}

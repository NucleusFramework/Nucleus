package dev.nucleusframework.window.internal

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsCaptionButtonStyleTest {
    @Test
    fun closeIdleSharesHueWithHoveredSoFadeDoesNotGoGray() {
        val idle = WindowsCaptionButtonStyle.CloseIdle
        val hovered = WindowsCaptionButtonStyle.CloseHovered
        assertEquals(hovered.red, idle.red, 0.001f)
        assertEquals(hovered.green, idle.green, 0.001f)
        assertEquals(hovered.blue, idle.blue, 0.001f)
        assertEquals(0f, idle.alpha, 0.001f)
        assertEquals(1f, hovered.alpha, 0.001f)
    }

    @Test
    fun closeHoveredIsWin11CriticalRed() {
        assertEquals(Color(0xFFC42B1C), WindowsCaptionButtonStyle.CloseHovered)
    }

    @Test
    fun fadeOutDurationsMatchTerminalCaptionButtons() {
        assertEquals(150, WindowsCaptionButtonStyle.BackgroundFadeOutMillis)
        assertEquals(100, WindowsCaptionButtonStyle.ForegroundFadeOutMillis)
    }

    @Test
    fun customHoverAppliesToMinMaxButNotClose() {
        val custom = Color(0xFF336699)
        val minHover =
            windowsCaptionButtonBackground(
                hovered = true,
                pressed = false,
                isCloseButton = false,
                isDark = true,
                customHover = custom,
                customPressed = Color.Transparent,
            )
        val closeHover =
            windowsCaptionButtonBackground(
                hovered = true,
                pressed = false,
                isCloseButton = true,
                isDark = true,
                customHover = custom,
                customPressed = Color.Transparent,
            )
        assertEquals(custom, minHover)
        assertEquals(WindowsCaptionButtonStyle.CloseHovered, closeHover)
    }

    @Test
    fun idleMinMaxKeepsHoverHueAtZeroAlpha() {
        val idle =
            windowsCaptionButtonBackground(
                hovered = false,
                pressed = false,
                isCloseButton = false,
                isDark = true,
                customHover = Color.Transparent,
                customPressed = Color.Transparent,
            )
        val hover = WindowsCaptionButtonStyle.HoveredDark
        assertEquals(hover.red, idle.red, 0.001f)
        assertEquals(hover.green, idle.green, 0.001f)
        assertEquals(hover.blue, idle.blue, 0.001f)
        assertEquals(0f, idle.alpha, 0.001f)
    }

    @Test
    fun defaultDarkHoverIsWinuiSubtleFillSecondary() {
        val hover =
            windowsCaptionButtonBackground(
                hovered = true,
                pressed = false,
                isCloseButton = false,
                isDark = true,
                customHover = Color.Transparent,
                customPressed = Color.Transparent,
            )
        assertEquals(WindowsCaptionButtonStyle.HoveredDark, hover)
        assertTrue(hover.alpha in 0.05f..0.07f)
    }
}

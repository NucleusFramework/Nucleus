package dev.nucleusframework.window.material2

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Material2ColorMappingTest {
    @Test
    fun `light and dark palettes map window and title bar fields`() =
        runComposeUiTest {
            val light = lightColors()
            val dark = darkColors()
            var lightWindow: DecoratedWindowStyle? = null
            var darkWindow: DecoratedWindowStyle? = null
            var lightBar: TitleBarStyle? = null
            var darkBar: TitleBarStyle? = null
            setContent {
                lightWindow = rememberMaterialWindowStyle(light)
                darkWindow = rememberMaterialWindowStyle(dark)
                lightBar = rememberMaterialTitleBarStyle(light)
                darkBar = rememberMaterialTitleBarStyle(dark)
            }
            waitForIdle()
            assertWindowStyle(lightWindow!!, light.background, light.onSurface)
            assertWindowStyle(darkWindow!!, dark.background, dark.onSurface)
            assertTitleBarStyle(lightBar!!, light.surface, light.onSurface, light.onSurface)
            assertTitleBarStyle(darkBar!!, dark.surface, dark.onSurface, dark.onSurface)
            assertTrue(lightWindow!!.colors.background != darkWindow!!.colors.background)
        }

    private fun assertWindowStyle(
        style: DecoratedWindowStyle,
        background: Color,
        onSurface: Color,
    ) {
        assertEquals(background, style.colors.background)
        assertEquals(onSurface.copy(alpha = 0.12f), style.colors.border)
        assertEquals(onSurface.copy(alpha = 0.06f), style.colors.borderInactive)
        assertEquals(1.dp, style.metrics.borderWidth)
    }

    private fun assertTitleBarStyle(
        style: TitleBarStyle,
        surface: Color,
        onSurface: Color,
        borderSource: Color,
    ) {
        assertEquals(surface, style.colors.background)
        assertEquals(surface, style.colors.inactiveBackground)
        assertEquals(onSurface, style.colors.content)
        assertEquals(borderSource.copy(alpha = 0.12f), style.colors.border)
        assertEquals(surface, style.colors.fullscreenControlButtonsBackground)
        assertEquals(40.dp, style.metrics.height)
    }
}

package dev.nucleusframework.window.material

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.styling.DecoratedWindowStyle
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MaterialColorMappingTest {
    @Test
    fun `light and dark schemes map every shipped window and title bar field`() =
        runComposeUiTest {
            val light = lightColorScheme()
            val dark = darkColorScheme()
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
            assertWindowStyle(lightWindow!!, light.background, light.outlineVariant)
            assertWindowStyle(darkWindow!!, dark.background, dark.outlineVariant)
            assertTitleBarStyle(lightBar!!, light.surface, light.onSurface, light.outlineVariant)
            assertTitleBarStyle(darkBar!!, dark.surface, dark.onSurface, dark.outlineVariant)
            assertFalse(light.isDark())
            assertTrue(dark.isDark())
            assertTrue(lightWindow!!.colors.background != darkWindow!!.colors.background)
        }

    private fun assertWindowStyle(
        style: DecoratedWindowStyle,
        background: Color,
        outline: Color,
    ) {
        assertEquals(background, style.colors.background)
        assertEquals(outline, style.colors.border)
        assertEquals(outline.copy(alpha = 0.5f), style.colors.borderInactive)
        assertEquals(1.dp, style.metrics.borderWidth)
    }

    private fun assertTitleBarStyle(
        style: TitleBarStyle,
        surface: Color,
        onSurface: Color,
        outline: Color,
    ) {
        assertEquals(surface, style.colors.background)
        assertEquals(surface, style.colors.inactiveBackground)
        assertEquals(onSurface, style.colors.content)
        assertEquals(outline, style.colors.border)
        assertEquals(surface, style.colors.fullscreenControlButtonsBackground)
        assertEquals(40.dp, style.metrics.height)
        assertEquals(DpSize(40.dp, 40.dp), style.metrics.titlePaneButtonSize)
    }
}

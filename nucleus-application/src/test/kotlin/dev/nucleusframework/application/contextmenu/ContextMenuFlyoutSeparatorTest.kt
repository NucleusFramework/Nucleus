package dev.nucleusframework.application.contextmenu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuFlyoutSeparatorTest {
    @Test
    fun `adwaita separators follow libadwaita's 15 percent of currentColor rule`() {
        // separator { background: $border_color } with
        // $border_color: color-mix(in srgb, currentColor var(--border-opacity), transparent)
        // and --border-opacity: 15%; currentColor in a menu is popover_fg_color.
        assertEquals(Color.White.copy(alpha = 0.15f), AdwaitaMenuTheme.colors(true).separator)
        // Light popover_fg_color is RGB(0 0 6 / 80%), premultiplied down to 12 %.
        val light = AdwaitaMenuTheme.colors(false).separator
        assertEquals(0.12f, light.alpha, 0.005f)
        assertEquals(0f, light.red, 0.001f)
    }

    @Test
    fun `dark separators sit brighter than the menu surface`() {
        themes().forEach { (name, theme) ->
            val colors = theme.colors(true)
            assertTrue(
                "$name dark separator must lighten the surface, not darken it",
                colors.separator.over(colors.surface).luminance() > colors.surface.luminance(),
            )
        }
    }

    @Test
    fun `light separators sit darker than the menu surface`() {
        themes().forEach { (name, theme) ->
            val colors = theme.colors(false)
            assertTrue(
                "$name light separator must darken the surface",
                colors.separator.over(colors.surface).luminance() < colors.surface.luminance(),
            )
        }
    }

    private fun themes() =
        listOf(
            "Adwaita" to AdwaitaMenuTheme,
            "Breeze" to BreezeMenuTheme,
            "Fluent" to FluentMenuTheme,
        )
}

/** Source-over composite, as the flyout draws a separator on the menu surface. */
private fun Color.over(background: Color): Color =
    Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

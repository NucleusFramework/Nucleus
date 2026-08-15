@file:Suppress("MagicNumber")

package dev.nucleusframework.window.internal

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * Native Win11 caption-button tokens, taken from WinUI
 * `Common_themeresources_any.xaml` and Windows Terminal
 * `MinMaxCloseControl.xaml` (the Microsoft control that matches Notepad).
 *
 * Hover-in is instantaneous. Hover-out fades the fill in 150ms and the glyph
 * in 100ms. Colors interpolate in sRGB so the close button stays on
 * `#C42B1C` instead of flashing gray (microsoft/terminal#9762).
 */
public object WindowsCaptionButtonStyle {
    /** WinUI `SubtleFillColorSecondary` (light). */
    public val HoveredLight: Color = Color(0x09000000)

    /** WinUI `SubtleFillColorSecondary` (dark). */
    public val HoveredDark: Color = Color(0x0FFFFFFF)

    /** WinUI `SubtleFillColorTertiary` (light). */
    public val PressedLight: Color = Color(0x06000000)

    /** WinUI `SubtleFillColorTertiary` (dark). */
    public val PressedDark: Color = Color(0x0AFFFFFF)

    /** Win11 / WinUI `SystemFillColorCritical` close hover. */
    public val CloseHovered: Color = Color(0xFFC42B1C)

    /** Terminal close-pressed: the same red at 90% opacity. */
    public val ClosePressed: Color = Color(0xE6C42B1C)

    /**
     * Idle close fill: `#C42B1C` at alpha 0. ColorAnimation from this to
     * [CloseHovered] only changes alpha, so the fade never goes through gray.
     */
    public val CloseIdle: Color = Color(0x00C42B1C)

    /** `MinMaxCloseControl.xaml` PointerOver → Normal background duration. */
    public const val BackgroundFadeOutMillis: Int = 150

    /** `MinMaxCloseControl.xaml` PointerOver → Normal glyph duration. */
    public const val ForegroundFadeOutMillis: Int = 100
}

/**
 * Caption-button fill for the current pointer state.
 *
 * [customHover] / [customPressed] override the min/max overlay when they are
 * not [Color.Transparent]. Close hover/pressed always use the system red,
 * matching WinUI `AppWindowTitleBar` (the button background color is not
 * applied to the close button hover and pressed states).
 */
public fun windowsCaptionButtonBackground(
    hovered: Boolean,
    pressed: Boolean,
    isCloseButton: Boolean,
    isDark: Boolean,
    customHover: Color,
    customPressed: Color,
): Color {
    val pressedColor =
        customPressed.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsCaptionButtonStyle.PressedDark else WindowsCaptionButtonStyle.PressedLight
    val hoveredColor =
        customHover.takeUnless { it == Color.Transparent }
            ?: if (isDark) WindowsCaptionButtonStyle.HoveredDark else WindowsCaptionButtonStyle.HoveredLight
    return when {
        pressed && isCloseButton -> WindowsCaptionButtonStyle.ClosePressed
        pressed -> pressedColor
        hovered && isCloseButton -> WindowsCaptionButtonStyle.CloseHovered
        hovered -> hoveredColor
        isCloseButton -> WindowsCaptionButtonStyle.CloseIdle
        else -> hoveredColor.copy(alpha = 0f)
    }
}

/**
 * Animates a caption-button color the way native Win32 / Terminal does:
 * snap when the overlay appears, linear sRGB fade when it disappears.
 */
@Composable
public fun animateWindowsCaptionColor(
    target: Color,
    appearing: Boolean,
    durationMillis: Int,
): Color {
    val spec =
        if (appearing) {
            snap()
        } else {
            tween<Color>(durationMillis = durationMillis, easing = LinearEasing)
        }
    val animated by animateValueAsState(
        targetValue = target,
        typeConverter = WindowsCaptionColorConverter,
        animationSpec = spec,
        label = "windowsCaptionColor",
    )
    return animated
}

// sRGB component interpolation — not Oklab — so `#C42B1C` ↔ `#00C42B1C`
// only changes alpha. Compose's default Color converter would fade through
// a neutral gray, which is the Terminal #9762 bug.
private val WindowsCaptionColorConverter =
    TwoWayConverter<Color, AnimationVector4D>(
        convertToVector = { color ->
            AnimationVector4D(color.red, color.green, color.blue, color.alpha)
        },
        convertFromVector = { vector ->
            Color(vector.v1, vector.v2, vector.v3, vector.v4)
        },
    )

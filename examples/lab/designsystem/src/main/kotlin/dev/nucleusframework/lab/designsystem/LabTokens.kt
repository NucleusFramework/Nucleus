package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The only spacing, shape and surface values the Lab uses. Probes never write a raw dp for
 * padding, gaps or corners, nor a hex colour for chrome: they pick one of these.
 */
object LabDimens {
    /** Outer padding of a probe page or a session window body. */
    val page = 16.dp

    /** Inner padding of a Section, a CodeBlock, a TargetArea. */
    val block = 12.dp

    /** Gap between blocks of a page. */
    val blockGap = 16.dp

    /** Gap between items inside a block. */
    val gap = 8.dp

    /** Gap between lines of a log or a readout list. */
    val lineGap = 2.dp

    /** Width of the label column of Readout / LabeledRow / Meter. */
    val labelWidth = 160.dp

    /** Indentation of a nested line (sub-events, details). */
    val indent = 16.dp
}

object LabShapes {
    /** Sections, code blocks, target areas, fields. */
    val block = RoundedCornerShape(6.dp)

    /** Specimen frames: content under test. */
    val specimen = RoundedCornerShape(8.dp)

    /** Rows, small controls, title bar buttons. */
    val small = RoundedCornerShape(4.dp)

    /** Chips and pills. */
    val pill = RoundedCornerShape(50)
}

/** Surfaces derived from [LabColors], so light and dark stay consistent. */
object LabSurfaces {
    /** Behind a specimen: neutral, slightly off the page so its bounds are visible. */
    val specimen: Color
        @Composable @ReadOnlyComposable
        get() = LabTheme.colors.specimen

    /** Code, output, JSON. */
    val code: Color
        @Composable @ReadOnlyComposable
        get() = LabTheme.colors.code

    /** A target area the user acts on. */
    val target: Color
        @Composable @ReadOnlyComposable
        get() = LabTheme.colors.target

    /** A Compose pill drawn over native content (video, WebView, SwiftUI, textures). */
    val overlay: Color
        @Composable @ReadOnlyComposable
        get() = LabTheme.colors.overlay

    val onOverlay: Color
        @Composable @ReadOnlyComposable
        get() = LabTheme.colors.onOverlay
}

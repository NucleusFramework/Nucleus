package dev.nucleusframework.tray.utils

import androidx.compose.ui.unit.Density
import dev.nucleusframework.core.runtime.Platform

/**
 * Properties for rendering a Composable icon.
 *
 * [sceneWidth]/[sceneHeight] are the master bitmap resolution. [targetWidth]/[targetHeight]
 * are the logical (unscaled) size the native backend presents, **unless**
 * [jvmOwnsDownscaling] is true, in which case they are physical pixels the JVM
 * downsamples to before encoding.
 *
 * @property sceneWidth Width of the [androidx.compose.ui.ImageComposeScene] in pixels
 * @property sceneHeight Height of the [androidx.compose.ui.ImageComposeScene] in pixels
 * @property sceneDensity Density for [androidx.compose.ui.ImageComposeScene].
 *   Controls how Compose `dp` maps into the scene; it is **not** the display scale factor.
 * @property targetWidth Logical width (or physical width when [jvmOwnsDownscaling] is true)
 * @property targetHeight Logical height (or physical height when [jvmOwnsDownscaling] is true)
 * @property jvmOwnsDownscaling When true, the JVM scales the scene to
 *   [targetWidth]×[targetHeight] before encoding. When false, the JVM emits the
 *   scene-resolution master and native backends (or a multi-frame ICO) own
 *   display-scale downsampling.
 */
public data class IconRenderProperties(
    val sceneWidth: Int = 192,
    val sceneHeight: Int = 192,
    val sceneDensity: Density = Density(2f),
    val targetWidth: Int = 192,
    val targetHeight: Int = 192,
    val jvmOwnsDownscaling: Boolean = true,
) {
    public val requiresScaling: Boolean =
        jvmOwnsDownscaling && (sceneWidth != targetWidth || sceneHeight != targetHeight)

    public companion object {
        /**
         * Provides an [IconRenderProperties] configured for the current operating system.
         *
         * The scene is kept at full master resolution. [targetWidth]/[targetHeight] record the
         * typical logical tray size per platform (Windows 32, macOS 44 / 18pt@2x, Linux 24);
         * the JVM does **not** downsample to those sizes. Native backends pick the display
         * scale at draw time (SNI pixmap pyramid, multi-frame ICO, AppKit point size).
         *
         * @param sceneWidth Width of the [androidx.compose.ui.ImageComposeScene] in pixels.
         * @param sceneHeight Height of the [androidx.compose.ui.ImageComposeScene] in pixels.
         * @param density Density of the [androidx.compose.ui.ImageComposeScene].
         * @return An instance of [IconRenderProperties] with the appropriate logical size
         *         based on the operating system.
         */
        public fun forCurrentOperatingSystem(
            sceneWidth: Int = 192,
            sceneHeight: Int = 192,
            density: Density = Density(2f),
        ): IconRenderProperties {
            val (targetWidth, targetHeight) =
                when (Platform.Current) {
                    Platform.Windows -> 32 to 32
                    Platform.MacOS -> 44 to 44
                    Platform.Linux -> 24 to 24
                    else -> sceneWidth to sceneHeight
                }

            return IconRenderProperties(
                sceneWidth = sceneWidth,
                sceneHeight = sceneHeight,
                sceneDensity = density,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                jvmOwnsDownscaling = false,
            )
        }

        /**
         * Provides an [IconRenderProperties] configured with settings that don't force icon scaling and aliasing.
         *
         * @param sceneWidth Width of the [androidx.compose.ui.ImageComposeScene] in pixels.
         * @param sceneHeight Height of the [androidx.compose.ui.ImageComposeScene] in pixels.
         * @param density Density of the [androidx.compose.ui.ImageComposeScene].
         * @return An instance of [IconRenderProperties] with the appropriate target width and height based on the operating system.
         */
        public fun withoutScalingAndAliasing(
            sceneWidth: Int = 192,
            sceneHeight: Int = 192,
            density: Density = Density(2f),
        ): IconRenderProperties = IconRenderProperties(
            sceneWidth = sceneWidth,
            sceneHeight = sceneHeight,
            sceneDensity = density,
            targetWidth = sceneWidth,
            targetHeight = sceneHeight,
        )

        /**
         * Provides an [IconRenderProperties] configured for menu items.
         *
         * Menu items are presented at 16 logical pixels/points on every platform. The scene
         * defaults to 64px (4×) so Retina / 200% DPI menus stay sharp: the JVM keeps that
         * master and native backends size it in points (macOS) or DPI-scaled pixels (Windows).
         *
         * @param sceneWidth Width of the [androidx.compose.ui.ImageComposeScene] in pixels. Defaults to 64.
         * @param sceneHeight Height of the [androidx.compose.ui.ImageComposeScene] in pixels. Defaults to 64.
         * @param density Density of the [androidx.compose.ui.ImageComposeScene]. Defaults to 2.0 for high-DPI support.
         * @return An instance of [IconRenderProperties] with a 16px logical size and a high-res master.
         */
        public fun forMenuItem(
            sceneWidth: Int = 64,
            sceneHeight: Int = 64,
            density: Density = Density(2f),
        ): IconRenderProperties {
            val (targetWidth, targetHeight) =
                when (Platform.Current) {
                    Platform.Windows -> 16 to 16
                    Platform.MacOS -> 16 to 16
                    Platform.Linux -> 16 to 16
                    else -> 16 to 16
                }

            return IconRenderProperties(
                sceneWidth = sceneWidth,
                sceneHeight = sceneHeight,
                sceneDensity = density,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                jvmOwnsDownscaling = false,
            )
        }
    }
}

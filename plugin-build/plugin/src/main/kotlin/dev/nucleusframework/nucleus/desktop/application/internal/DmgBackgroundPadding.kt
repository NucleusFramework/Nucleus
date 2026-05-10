/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File
import javax.imageio.ImageIO

/**
 * Height of the macOS DMG window title bar in pixels.
 *
 * The Finder window bounds include the title bar, so the visible content area
 * is `boundsHeight - titleBarHeight`. This constant is used by the native
 * AppleScript DMG path and the electron-builder path to ensure the window
 * is large enough for the full background image to be visible.
 */
internal const val MACOS_DMG_TITLE_BAR_HEIGHT = 28

/**
 * Copies a DMG background image to [outputDir] without modification.
 *
 * electron-builder uses the image dimensions as the DMG window size.
 * The macOS title bar takes [MACOS_DMG_TITLE_BAR_HEIGHT] pixels, so the window
 * height is adjusted accordingly in the generated config (windowOverride).
 *
 * TIFF files must never be re-encoded because ImageIO destroys Apple's
 * multi-resolution TIFF metadata (2x + 1x layers with DPI info).
 * PNG files are also copied directly to avoid any quality loss from re-encoding.
 *
 * @return the copied image file in [outputDir]
 */
internal fun padDmgBackgroundForTitleBar(
    source: File,
    outputDir: File,
    logger: Logger? = null,
): File {
    outputDir.mkdirs()
    val output = File(outputDir, source.name)
    source.copyTo(output, overwrite = true)
    logger?.info("Copied DMG background directly: ${source.name}")

    // Also copy the @2x Retina variant if present alongside the source
    val retinaSource = File(source.parentFile, "${source.nameWithoutExtension}@2x.${source.extension}")
    if (retinaSource.isFile) {
        retinaSource.copyTo(File(outputDir, retinaSource.name), overwrite = true)
        logger?.info("Copied @2x Retina DMG background: ${retinaSource.name}")
    }

    return output
}

/**
 * Reads the dimensions of an image file.
 *
 * @return a [Pair] of (width, height) in pixels, or `null` if the image cannot be read.
 */
internal fun readImageDimensions(file: File): Pair<Int, Int>? {
    return try {
        val readers = ImageIO.getImageReadersBySuffix(file.extension)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        ImageIO.createImageInputStream(file).use { stream ->
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            reader.dispose()
            width to height
        }
    } catch (_: Exception) {
        null
    }
}

/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.DmgContentEntry
import dev.nucleusframework.desktop.application.dsl.DmgFormat
import dev.nucleusframework.desktop.application.internal.MACOS_DMG_TITLE_BAR_HEIGHT
import dev.nucleusframework.desktop.application.internal.readImageDimensions
import dev.nucleusframework.internal.utils.ioFile
import dev.nucleusframework.internal.utils.notNullProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "Depends on external macOS native tools")
abstract class AbstractNativeMacApplicationPackageDmgTask : AbstractNativeMacApplicationPackageTask() {
    companion object {
        private const val DEFAULT_ICON_SIZE = 72
        private const val DEFAULT_WINDOW_X = 400
        private const val DEFAULT_WINDOW_Y = 100
        private const val DEFAULT_WINDOW_WIDTH = 485
        private const val DEFAULT_WINDOW_HEIGHT = 330
        private const val CSS_SHORT_HEX_LENGTH = 3
        private const val CSS_FULL_HEX_LENGTH = 6
        private const val APPLE_SCRIPT_RGB_SCALE = 257
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val hdiutil: RegularFileProperty = objects.fileProperty().value { File("/usr/bin/hdiutil") }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val osascript: RegularFileProperty = objects.fileProperty().value { File("/usr/bin/osascript") }

    @get:Input
    val installDir: Property<String> = objects.notNullProperty("/Applications")

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val appDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val dmgFormat: Property<DmgFormat> = objects.property(DmgFormat::class.java)

    @get:Input
    @get:Optional
    val dmgIconSize: Property<Int> = objects.property(Int::class.java)

    @get:Input
    @get:Optional
    val dmgWindowX: Property<Int> = objects.property(Int::class.java)

    @get:Input
    @get:Optional
    val dmgWindowY: Property<Int> = objects.property(Int::class.java)

    @get:Input
    @get:Optional
    val dmgWindowWidth: Property<Int> = objects.property(Int::class.java)

    @get:Input
    @get:Optional
    val dmgWindowHeight: Property<Int> = objects.property(Int::class.java)

    @get:Input
    @get:Optional
    val dmgTitle: Property<String> = objects.property(String::class.java)

    @get:Input
    @get:Optional
    val dmgBackgroundColor: Property<String> = objects.property(String::class.java)

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val dmgBackgroundImage: RegularFileProperty = objects.fileProperty()

    @get:Input
    val dmgContents: ListProperty<DmgContentEntry> =
        objects.listProperty(DmgContentEntry::class.java).convention(emptyList())

    override fun createPackage(
        destinationDir: File,
        workingDir: File,
    ) {
        val bundleName = bundleName.get()
        val volumeName =
            dmgTitle.orNull
                ?.replace("\${productName}", bundleName)
                ?.replace("\${version}", packageVersion.get())
                ?: bundleName
        val fullPackageName = fullPackageName.get()
        val tmpImage = workingDir.resolve("$fullPackageName.tmp.dmg")
        val finalImage = destinationDir.resolve("$fullPackageName.dmg")

        createImage(volumeName = volumeName, imageFile = tmpImage, srcDir = appDir.ioFile)
        val mounted = mountImage(volumeName = volumeName, imageFile = tmpImage)
        try {
            // Must match the .app directory name inside the image, which is the bundle name.
            runSetupScript(appName = bundleName, mounted)
        } finally {
            unmountImage(mounted)
        }
        finalizeImage(tmpImage, finalImage)
        logger.lifecycle("The distribution is written to ${finalImage.canonicalPath}")
    }

    private fun createImage(
        volumeName: String,
        imageFile: File,
        srcDir: File,
    ) {
        var size = srcDir.walk().filter { it.isFile }.sumOf { it.length() }
        size += 10 * 1024 * 1024

        hdiutil(
            "create",
            "-srcfolder",
            srcDir.absolutePath,
            "-volname",
            volumeName,
            "-size",
            size.toString(),
            "-ov",
            imageFile.absolutePath,
            "-fs",
            "HFS+",
            "-format",
            "UDRW",
        )
    }

    private data class MountedImage(
        val device: String,
        val disk: String,
    )

    private fun mountImage(
        volumeName: String,
        imageFile: File,
    ): MountedImage {
        val output =
            hdiutil(
                "attach",
                "-readwrite",
                "-noverify",
                "-noautoopen",
                imageFile.absolutePath,
            )
        Thread.sleep(3000)
        var device: String? = null
        var volume: String? = null

        for (line in output.split("\n")) {
            if (!line.startsWith("/dev/")) continue

            val volumeIndex = line.lastIndexOf("/Volumes/$volumeName")
            if (volumeIndex <= 0) continue

            volume = line.substring(volumeIndex).trimEnd()
            device = line.substring(0, line.indexOfFirst(Char::isWhitespace))
        }
        check(device != null && volume != null) {
            "Could not parse mounted image's device ($device) & volume ($volume) from hdiutil output:" +
                "\n=======\n" +
                output +
                "\n=======\n"
        }
        if (verbose.get()) {
            logger.info("Mounted DMG image '$imageFile': volume '$volume', device '$device'")
        }
        return MountedImage(device = device, disk = volume.removePrefix("/Volumes/"))
    }

    private fun unmountImage(mounted: MountedImage) {
        hdiutil("detach", mounted.device)
    }

    private fun finalizeImage(
        tmpImage: File,
        finalImage: File,
    ) {
        val format = dmgFormat.orNull?.id ?: "UDZO"
        val args =
            mutableListOf(
                "convert",
                tmpImage.absolutePath,
                "-format",
                format,
                "-o",
                finalImage.absolutePath,
            )
        // Add zlib compression level for UDZO format
        if (format == "UDZO") {
            args.addAll(listOf("-imagekey", "zlib-level=9"))
        }
        hdiutil(args)
    }

    private fun hdiutil(args: List<String>): String {
        var resultStdout = ""
        val allArgs = args.toMutableList()
        if (verbose.get()) {
            allArgs.add("-verbose")
        }
        runExternalTool(tool = hdiutil.ioFile, args = allArgs, processStdout = { resultStdout = it })
        return resultStdout
    }

    private fun hdiutil(vararg args: String): String = hdiutil(args.toList())

    private fun runSetupScript(
        appName: String,
        mounted: MountedImage,
    ) {
        val disk = mounted.disk
        val installDir = installDir.get()
        val iconSize = dmgIconSize.orNull ?: DEFAULT_ICON_SIZE
        val winX = dmgWindowX.orNull ?: DEFAULT_WINDOW_X
        val winY = dmgWindowY.orNull ?: DEFAULT_WINDOW_Y
        var winW = dmgWindowWidth.orNull ?: DEFAULT_WINDOW_WIDTH
        var winH = dmgWindowHeight.orNull ?: DEFAULT_WINDOW_HEIGHT
        val contents = dmgContents.get()

        // Copy background image to the mounted volume and adjust window size
        val backgroundFile = dmgBackgroundImage.orNull?.asFile
        var backgroundFileName: String? = null
        if (backgroundFile != null && backgroundFile.isFile) {
            val volumePath = File("/Volumes/$disk")
            val bgDir = File(volumePath, ".background")
            bgDir.mkdirs()
            val bgDest = File(bgDir, "background.${backgroundFile.extension}")
            backgroundFile.copyTo(bgDest, overwrite = true)
            backgroundFileName = bgDest.name

            // Adjust window size to fit the background image (issue #26).
            // The Finder window bounds include the title bar, so the content area
            // is (boundsHeight - titleBarHeight). We ensure the window is large enough
            // for the full image to be visible.
            val dimensions = readImageDimensions(backgroundFile)
            if (dimensions != null) {
                val (imgW, imgH) = dimensions
                val requiredW = imgW
                val requiredH = imgH + MACOS_DMG_TITLE_BAR_HEIGHT
                if (winW < requiredW) winW = requiredW
                if (winH < requiredH) winH = requiredH
            }
        }

        val scriptBuilder = StringBuilder()
        scriptBuilder.appendLine("""tell application "Finder"""")
        scriptBuilder.appendLine("""  tell disk "$disk"""")
        scriptBuilder.appendLine("        open")
        scriptBuilder.appendLine("        set current view of container window to icon view")
        scriptBuilder.appendLine("        set toolbar visible of container window to false")
        scriptBuilder.appendLine("        set statusbar visible of container window to false")
        val boundsRight = winX + winW
        val boundsBottom = winY + winH
        scriptBuilder.appendLine(
            "        set the bounds of container window to {$winX, $winY, $boundsRight, $boundsBottom}",
        )
        scriptBuilder.appendLine("        set theViewOptions to the icon view options of container window")
        scriptBuilder.appendLine("        set arrangement of theViewOptions to not arranged")
        scriptBuilder.appendLine("        set icon size of theViewOptions to $iconSize")

        if (backgroundFileName != null) {
            scriptBuilder.appendLine(
                "        set background picture of theViewOptions to file \".background:$backgroundFileName\"",
            )
        } else {
            dmgBackgroundColor.orNull?.let { color ->
                val rgb = cssColorToAppleScriptRgb(color)
                scriptBuilder.appendLine(
                    "        set background color of theViewOptions to {$rgb}",
                )
            }
        }

        if (contents.isEmpty()) {
            // Default layout when no contents specified
            scriptBuilder.appendLine(
                "        make new alias file at container window" +
                    " to POSIX file \"$installDir\" with properties {name:\"$installDir\"}",
            )
            scriptBuilder.appendLine(
                """        set position of item "$appName" of container window to {100, 100}""",
            )
            scriptBuilder.appendLine(
                """        set position of item "$installDir" of container window to {375, 100}""",
            )
        } else {
            for (entry in contents) {
                val entryName = entry.name ?: entry.path ?: continue
                scriptBuilder.appendLine(
                    "        set position of item \"$entryName\"" +
                        " of container window to {${entry.x}, ${entry.y}}",
                )
            }
        }

        scriptBuilder.appendLine("        update without registering applications")
        scriptBuilder.appendLine("        delay 5")
        scriptBuilder.appendLine("        close")
        scriptBuilder.appendLine("  end tell")
        scriptBuilder.appendLine("end tell")

        val setupScript =
            workingDir.ioFile.resolve("setup-dmg.scpt").apply {
                writeText(scriptBuilder.toString())
            }
        runExternalTool(tool = osascript.ioFile, args = listOf(setupScript.absolutePath))
    }

    /** Converts a CSS hex color (e.g. "#ff0000") to AppleScript RGB {R, G, B} (0–65535 range). */
    @Suppress("MagicNumber")
    private fun cssColorToAppleScriptRgb(cssColor: String): String {
        val hex = cssColor.removePrefix("#")
        val (r, g, b) =
            when (hex.length) {
                CSS_SHORT_HEX_LENGTH ->
                    Triple(
                        hex.substring(0, 1).repeat(2).toInt(16),
                        hex.substring(1, 2).repeat(2).toInt(16),
                        hex.substring(2, 3).repeat(2).toInt(16),
                    )
                CSS_FULL_HEX_LENGTH ->
                    Triple(
                        hex.substring(0, 2).toInt(16),
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16),
                    )
                else -> return "65535, 65535, 65535"
            }
        // Scale 0–255 to 0–65535
        return "${r * APPLE_SCRIPT_RGB_SCALE}, ${g * APPLE_SCRIPT_RGB_SCALE}, ${b * APPLE_SCRIPT_RGB_SCALE}"
    }
}

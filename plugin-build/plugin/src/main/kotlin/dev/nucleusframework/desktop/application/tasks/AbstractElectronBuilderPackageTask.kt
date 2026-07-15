/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.MacOSSigningSettings
import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.internal.UpdateYmlChecksums
import dev.nucleusframework.desktop.application.internal.UpdateYmlPublish
import dev.nucleusframework.desktop.application.internal.UpdateYmlGenerator
import dev.nucleusframework.desktop.application.internal.LinuxSigner
import dev.nucleusframework.desktop.application.internal.LinuxUpdateHelper
import dev.nucleusframework.desktop.application.internal.MacDmgLzma
import dev.nucleusframework.desktop.application.internal.MacSigner
import dev.nucleusframework.desktop.application.internal.MacSignerImpl
import dev.nucleusframework.desktop.application.internal.NoCertificateSigner
import dev.nucleusframework.desktop.application.internal.WindowsKitsLocator
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderConfigGenerator
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderInvocation
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderToolManager
import dev.nucleusframework.desktop.application.internal.electronbuilder.NodeJsDetector
import dev.nucleusframework.desktop.application.internal.files.isDylibPath
import dev.nucleusframework.desktop.application.internal.MACOS_DMG_TITLE_BAR_HEIGHT
import dev.nucleusframework.desktop.application.internal.padDmgBackgroundForTitleBar
import dev.nucleusframework.desktop.application.internal.readImageDimensions
import dev.nucleusframework.desktop.application.internal.updateExecutableTypeInAppImage
import dev.nucleusframework.desktop.application.internal.validation.ValidatedMacOSSigningSettings
import dev.nucleusframework.desktop.application.internal.validation.validate
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import dev.nucleusframework.internal.utils.ioFile
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.filters.Canvas
import net.coobird.thumbnailator.geometry.Positions
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import javax.imageio.ImageIO
import javax.inject.Inject
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Gradle task that packages a pre-built app-image (from jpackage) using electron-builder.
 *
 * Pipeline:
 *   1. Resolve the platform-specific app directory from the jpackage app-image output.
 *   2. Update the executable type in the app image's .cfg launcher file.
 *   3. Generate an electron-builder YAML configuration from the DSL settings.
 *   4. Invoke electron-builder via npx with `--prepackaged`.
 *   5. Output the final installer/package to [destinationDir].
 */
@DisableCachingByDefault(because = "Depends on external electron-builder tool")
@Suppress("LargeClass", "TooManyFunctions")
abstract class AbstractElectronBuilderPackageTask
    @Inject
    constructor(
        @get:Input val targetFormat: TargetFormat,
    ) : AbstractNucleusTask() {
        companion object {
            private const val APPX_STORE_LOGO_SIZE = 50
            private const val APPX_SQUARE44_LOGO_SIZE = 44
            private const val APPX_SQUARE150_LOGO_SIZE = 150
            private const val APPX_WIDE_LOGO_WIDTH = 310
            private const val APPX_WIDE_LOGO_HEIGHT = 150
        }

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val appImageRoot: DirectoryProperty = objects.directoryProperty()

        @get:OutputDirectory
        val destinationDir: DirectoryProperty = objects.directoryProperty()

        @get:Input
        val packageName: Property<String> = objects.notNullProperty()

        @get:Input
        @get:Optional
        val packageVersion: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val customNodePath: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val publishMode: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val startupWMClass: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val executableName: Property<String> = objects.nullableProperty()

        @get:Input
        val targetArch: Property<String> =
            objects.notNullProperty<String>().apply {
                set(currentArch.id)
            }

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val linuxIconFile: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val windowsIconFile: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val appxStoreLogo: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val appxSquare44x44Logo: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val appxSquare150x150Logo: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val appxWide310x150Logo: RegularFileProperty = objects.fileProperty()

        /**
         * The distributions DSL object providing all platform-specific settings.
         * Marked @Internal because the individual settings are tracked via other @Input properties
         * on the DSL objects themselves, and this reference is used for config generation only.
         */
        @get:Internal
        var distributions: JvmApplicationDistributions? = null

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val macEntitlementsFile: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val macRuntimeEntitlementsFile: RegularFileProperty = objects.fileProperty()

        @get:Input
        @get:Optional
        internal val nonValidatedMacBundleID: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macAppStore: Property<Boolean> = objects.nullableProperty()

        @get:Optional
        @get:Nested
        internal var nonValidatedMacSigningSettings: MacOSSigningSettings? = null

        private val macSigner: MacSigner? by lazy {
            val nonValidatedSettings = nonValidatedMacSigningSettings
            if (currentOS == OS.MacOS) {
                if (nonValidatedSettings?.sign?.get() == true) {
                    val validatedSettings =
                        nonValidatedSettings.validate(nonValidatedMacBundleID, project, macAppStore)
                    MacSignerImpl(validatedSettings, runExternalTool)
                } else {
                    NoCertificateSigner(runExternalTool)
                }
            } else {
                null
            }
        }

        @TaskAction
        fun run() {
            val dist =
                this.distributions
                    ?: throw GradleException("distributions must be set on AbstractElectronBuilderPackageTask")

            if (!targetFormat.isCompatibleWithCurrentOS) {
                logger.lifecycle(
                    "Skipping ${targetFormat.name} packaging: not compatible with current OS ($currentOS)",
                )
                return
            }
            if (shouldSkipForMissingTool()) return

            val originalAppDir = resolveAppImageDir()
            logger.info("Resolved app image directory: ${originalAppDir.absolutePath}")

            val outputDir = destinationDir.ioFile.apply { mkdirs() }

            // Create a task-private copy of the app image so parallel tasks don't
            // interfere when modifying .cfg files or signing the bundle.
            val workingAppDir = copyAppImage(originalAppDir, outputDir, logger)

            ensureResourcesDirForElectronBuilder(workingAppDir)
            bundleUpdatePublicKey(workingAppDir, dist)
            ensureLinuxExecutableAlias(workingAppDir)
            updateExecutableTypeInAppImage(workingAppDir, targetFormat, logger, packageVersion.orNull)
            ensureMacAdHocSigning(workingAppDir, targetFormat)

            val npx = detectNpx()
            validateNodeVersion()

            val linuxIconOverride = prepareLinuxIconSet(outputDir)
            val windowsIconOverride = resolveWindowsIcon()
            val linuxAfterInstallTemplate = prepareLinuxAfterInstallTemplate(outputDir, isLinuxSilentUpdateEnabled(dist))
            if (targetFormat == TargetFormat.AppX) {
                val hasExplicitWindowsIcon =
                    dist.windows.iconFile.orNull
                        ?.asFile != null
                stageAppXAssets(
                    outputDir = outputDir,
                    windowsIconOverride = windowsIconOverride,
                    hasExplicitWindowsIcon = hasExplicitWindowsIcon,
                )
            }
            val configFile =
                generateConfig(
                    distributions = dist,
                    appDir = workingAppDir,
                    outputDir = outputDir,
                    linuxIconOverride = linuxIconOverride,
                    windowsIconOverride = windowsIconOverride,
                    linuxAfterInstallTemplate = linuxAfterInstallTemplate,
                )
            ensureProjectPackageMetadata(outputDir, dist)

            cleanupParasiticFiles(outputDir)

            val toolManager = ElectronBuilderToolManager(execOperations, logger)
            val extraConfigArgs =
                buildList {
                    if (targetFormat == TargetFormat.Snap && dist.publish.github.enabled) {
                        add("--config.snap.publish=github")
                    }
                }
            val ebEnvironment =
                resolveElectronBuilderEnvironment(
                    targetFormat = targetFormat,
                    currentOs = currentOS,
                    currentArchitecture = currentArch,
                    logger = logger,
                ) + isolatedCacheEnv(outputDir)
            toolManager.invoke(
                ElectronBuilderInvocation(
                    configFile = configFile,
                    prepackagedDir = workingAppDir,
                    outputDir = outputDir,
                    targets = buildElectronBuilderTargets(),
                    extraConfigArgs = extraConfigArgs,
                    npx = npx,
                    environment = ebEnvironment,
                    publishFlag = resolvePublishFlag(),
                ),
            )

            if (targetFormat == TargetFormat.Pkg) {
                signPkgInstaller(outputDir)
            }
            signLinuxPackage(outputDir, dist)

            // Must run before cleanupBuildTemporaries(), which removes the isolated npm cache
            // where the app-builder binary used to regenerate the blockmap lives.
            recompressDmgWithLzma(outputDir, dist)

            cleanupParasiticFiles(outputDir)
            cleanupBuildTemporaries(outputDir)
            configFile.delete()
            exportPackagingMetadata(outputDir, dist)
            generateUpdateYmlIfNeeded(outputDir, dist)
            logger.lifecycle("nucleus builder package written to ${outputDir.canonicalPath}")
        }

        private fun generateUpdateYmlIfNeeded(
            outputDir: File,
            dist: JvmApplicationDistributions,
        ) {
            if (!targetFormat.needsPluginUpdateYml) return
            val channel = resolveUpdateChannel(dist)
            val ymlFilename = targetFormat.updateYmlFilename(channel)
            val version = packageVersion.orNull ?: "0.0.0"
            UpdateYmlGenerator.generateIfMissing(outputDir, ymlFilename, version, logger)
        }

        private fun resolveUpdateChannel(dist: JvmApplicationDistributions): ReleaseChannel {
            val publish = dist.publish
            return when {
                publish.github.enabled -> publish.github.channel
                publish.generic.enabled -> publish.generic.channel
                else -> ReleaseChannel.Latest
            }
        }

        private fun resolvePublishFlag(): String {
            val publish = distributions?.publish
            val anyProviderEnabled =
                publish != null && (publish.github.enabled || publish.s3.enabled || publish.generic.enabled)
            // Priority: env var > Gradle property > DSL default (never when no provider enabled).
            val flag =
                UpdateYmlPublish.resolvePublishFlag(
                    anyProviderEnabled = anyProviderEnabled,
                    envValue = System.getenv(UpdateYmlPublish.PUBLISH_MODE_ENV),
                    propValue = publishMode.orNull,
                    dslValue = publish?.publishMode?.id ?: "never",
                )
            logger.info("Resolved electron-builder publish mode: $flag")
            return flag
        }

        private fun detectNpx(): File =
            NodeJsDetector.detectNpx(
                customNodePath = customNodePath.orNull,
                logger = logger,
            ) ?: throw GradleException(
                "npx not found. Node.js 18+ is required for electron-builder packaging. " +
                    "Install Node.js or set the 'compose.electronBuilder.nodePath' Gradle property.",
            )

        private fun validateNodeVersion() {
            val node =
                NodeJsDetector.detectNode(
                    customNodePath = customNodePath.orNull,
                    logger = logger,
                ) ?: return
            val version = NodeJsDetector.getNodeVersion(node) ?: return
            if (!NodeJsDetector.isNodeVersionSupported(version)) {
                throw GradleException(
                    "Node.js $version is not supported. Version 18+ is required for electron-builder.",
                )
            }
            logger.info("Using Node.js: ${node.absolutePath} ($version)")
        }

        private fun generateConfig(
            distributions: JvmApplicationDistributions,
            appDir: File,
            outputDir: File,
            linuxIconOverride: File?,
            windowsIconOverride: File?,
            linuxAfterInstallTemplate: File?,
        ): File {
            val configGenerator = ElectronBuilderConfigGenerator()
            val resolvedArch = Arch.entries.first { it.id == targetArch.get() }

            val (dmgBackgroundOverride, dmgWindowOverride) = if (targetFormat == TargetFormat.Dmg) {
                val bgFile = distributions.macOS.dmg.background.orNull?.asFile
                if (bgFile != null) {
                    val processedBg = padDmgBackgroundForTitleBar(bgFile, outputDir.resolve("dmg-assets"), logger)
                    val windowOverride = readImageDimensions(processedBg)?.let { (w, h) ->
                        ElectronBuilderConfigGenerator.DmgWindowOverride(w, h + MACOS_DMG_TITLE_BAR_HEIGHT)
                    }
                    processedBg to windowOverride
                } else {
                    null to null
                }
            } else {
                null to null
            }

            if (targetFormat == TargetFormat.AppImage && distributions.compressionLevel == CompressionLevel.Maximum) {
                logger.warn(
                    "AppImage with 'maximum' compression can cause extremely slow startup times (60s+) " +
                        "due to squashfs/FUSE decompression overhead. Consider 'normal' or 'store' instead. " +
                        "See https://github.com/electron-userland/electron-builder/issues/7483",
                )
            }

            val nsisProtocolInclude = generateProtocolNsisInclude(distributions, outputDir)

            val configContent =
                configGenerator.generateConfig(
                    distributions = distributions,
                    targetFormat = targetFormat,
                    appImageDir = appDir,
                    targetArch = resolvedArch,
                    startupWMClass = startupWMClass.orNull,
                    linuxIconOverride = linuxIconOverride,
                    windowsIconOverride = windowsIconOverride,
                    linuxAfterInstallTemplate = linuxAfterInstallTemplate,
                    executableName = resolveExecutableName(),
                    dmgBackgroundOverride = dmgBackgroundOverride,
                    dmgWindowOverride = dmgWindowOverride,
                    nsisProtocolInclude = nsisProtocolInclude,
                )
            val configFile = File(outputDir, "electron-builder.yml")
            configFile.writeText(configContent)
            logger.info("Generated electron-builder config at: ${configFile.absolutePath}")
            return configFile
        }

        /**
         * Generates an NSIS include script that registers the declared URL protocol handlers
         * (deep linking) in the Windows registry at install time.
         *
         * electron-builder's `protocols` field only registers schemes on macOS (Info.plist) and
         * Linux (.desktop `x-scheme-handler`); the NSIS target ignores it. Windows therefore needs
         * explicit registry writes, which we emit via the `customInstall`/`customUnInstall` hooks.
         *
         * Returns null (no registration) when the current OS is not Windows, the target is not an
         * NSIS-family installer, no protocols are declared, or the user already supplied a custom
         * NSIS include script (which must not be overridden).
         */
        private fun generateProtocolNsisInclude(
            distributions: JvmApplicationDistributions,
            outputDir: File,
        ): File? {
            if (currentOS != OS.Windows) return null
            if (distributions.protocols.isEmpty()) return null
            if (targetFormat !in setOf(TargetFormat.Nsis, TargetFormat.NsisWeb, TargetFormat.Exe)) return null

            if (distributions.windows.nsis.includeScript.orNull != null) {
                logger.warn(
                    "URL protocol handlers are declared but a custom nsis.includeScript is set; " +
                        "skipping automatic protocol registration. Register the schemes yourself " +
                        "in a customInstall macro inside your include script.",
                )
                return null
            }

            // Pair each scheme with its human-readable protocol name. The (Default) value of
            // the protocol key is what Windows and Chrome show in the "Open with …?" prompt
            // (convention: "URL:<friendly name>"). Using the protocol name — which may be in
            // Hebrew/Arabic/etc. — instead of the raw scheme makes that prompt readable.
            // Falls back to appName, then to the scheme itself.
            val handlers =
                distributions.protocols
                    .flatMap { protocol ->
                        val friendlyName =
                            protocol.name.takeIf { it.isNotBlank() }
                                ?: distributions.appName
                        protocol.schemes
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { scheme -> scheme to (friendlyName ?: scheme) }
                    }.distinctBy { it.first }
            if (handlers.isEmpty()) return null

            // SHELL_CONTEXT resolves to HKLM (per-machine) or HKCU (per-user) automatically.
            // ${APP_EXECUTABLE_FILENAME} is provided by electron-builder's NSIS template.
            val script =
                buildString {
                    appendLine("!macro customInstall")
                    for ((scheme, friendlyName) in handlers) {
                        val key = "Software\\Classes\\$scheme"
                        appendLine("  DetailPrint \"Registering $scheme:// URL handler\"")
                        appendLine("  DeleteRegKey SHELL_CONTEXT \"$key\"")
                        appendLine("  WriteRegStr SHELL_CONTEXT \"$key\" \"\" \"URL:$friendlyName\"")
                        appendLine("  WriteRegStr SHELL_CONTEXT \"$key\" \"URL Protocol\" \"\"")
                        appendLine(
                            "  WriteRegStr SHELL_CONTEXT \"$key\\DefaultIcon\" \"\" " +
                                "\"\$INSTDIR\\\${APP_EXECUTABLE_FILENAME},0\"",
                        )
                        appendLine(
                            "  WriteRegStr SHELL_CONTEXT \"$key\\shell\\open\\command\" \"\" " +
                                "'\"\$INSTDIR\\\${APP_EXECUTABLE_FILENAME}\" \"%1\"'",
                        )
                    }
                    appendLine("!macroend")
                    appendLine()
                    appendLine("!macro customUnInstall")
                    // Guard against auto-update: the new installer runs before the old uninstaller,
                    // so unconditional cleanup would drop a just-registered scheme.
                    appendLine("  \${ifNot} \${isUpdated}")
                    for ((scheme, _) in handlers) {
                        appendLine("    DeleteRegKey SHELL_CONTEXT \"Software\\Classes\\$scheme\"")
                    }
                    appendLine("  \${endIf}")
                    appendLine("!macroend")
                }

            val nshFile = File(outputDir, "nucleus-protocols.nsh")
            nshFile.parentFile.mkdirs()
            // Write with a UTF-8 BOM so makensis detects the encoding and keeps non-ASCII
            // protocol names (e.g. Hebrew) intact. NSIS treats '#' as a comment, so a
            // "#pragma" directive would be inert — the BOM is the supported mechanism.
            nshFile.writeText("﻿$script", Charsets.UTF_8)
            logger.info(
                "Generated NSIS protocol registration script at ${nshFile.absolutePath} " +
                    "for schemes: ${handlers.joinToString { it.first }}",
            )
            return nshFile
        }

        private fun exportPackagingMetadata(
            outputDir: File,
            distributions: JvmApplicationDistributions,
        ) {
            when (currentOS) {
                OS.Windows -> exportWindowsSigningMetadata(outputDir, distributions)
                OS.MacOS -> exportMacOSPackagingMetadata(outputDir, distributions)
                else -> {}
            }
        }

        private fun exportWindowsSigningMetadata(
            outputDir: File,
            distributions: JvmApplicationDistributions,
        ) {
            val signing = distributions.windows.signing
            if (!signing.enabled) return

            val certFile =
                signing.certificateFile.orNull
                    ?.asFile
                    ?.absolutePath
            val metadata =
                buildString {
                    appendLine("{")
                    appendLine("  \"enabled\": true,")
                    val certJson = certFile?.let { "\"${it.replace("\\", "\\\\")}\"" } ?: "null"
                    appendLine("  \"certificateFile\": $certJson,")
                    appendLine("  \"algorithm\": \"${signing.algorithm.id}\",")
                    appendLine("  \"timestampServer\": ${signing.timestampServer?.let { "\"$it\"" } ?: "null"}")
                    appendLine("}")
                }
            val metadataFile = File(outputDir, "signing-metadata.json")
            metadataFile.writeText(metadata)
            logger.info("Exported signing metadata to: ${metadataFile.absolutePath}")
        }

        private fun exportMacOSPackagingMetadata(
            outputDir: File,
            distributions: JvmApplicationDistributions,
        ) {
            val mac = distributions.macOS
            val appId =
                mac.bundleID?.takeIf { it.isNotBlank() }
                    ?: distributions.packageName?.let { "com.app.$it" }
            val sign = mac.signing.sign.orNull == true
            val dmg = mac.dmg

            // Copy DMG asset files to a subdirectory so they travel with the metadata artifact.
            // The background image is padded using native sips to compensate for the macOS
            // title bar — see issue #26 and padDmgBackgroundForTitleBar().
            val assetsDir = File(outputDir, "dmg-assets")
            val dmgBackground =
                dmg.background.orNull?.asFile?.let { bgFile ->
                    val padded = padDmgBackgroundForTitleBar(bgFile, assetsDir, logger)
                    copyDmgAsset(padded, assetsDir, "background")
                }
            val dmgBadgeIcon = copyDmgAsset(dmg.badgeIcon.orNull?.asFile, assetsDir, "badge-icon")
            val dmgIcon = copyDmgAsset(dmg.icon.orNull?.asFile, assetsDir, "icon")

            val metadata =
                buildString {
                    appendLine("{")
                    val resolvedProductName =
                        distributions.appName ?: distributions.packageName ?: executableName.orNull
                    appendLine("  \"productName\": ${jsonStr(resolvedProductName)},")
                    appendLine("  \"appId\": ${jsonStr(appId)},")
                    appendLine("  \"copyright\": ${jsonStr(distributions.copyright)},")
                    appendLine("  \"artifactName\": ${jsonStr(distributions.artifactName)},")
                    appendLine("  \"compression\": ${jsonStr(distributions.compressionLevel?.id)},")
                    appendLine("  \"category\": ${jsonStr(mac.appCategory)},")
                    appendLine("  \"minimumSystemVersion\": ${jsonStr(mac.minimumSystemVersion)},")
                    appendLine("  \"sign\": $sign,")
                    appendLine("  \"installLocation\": ${jsonStr(mac.installationPath)},")
                    appendLine("  \"dmg\": {")
                    appendLine("    \"sign\": ${dmg.sign},")
                    appendLine("    \"background\": ${jsonStr(dmgBackground)},")
                    appendLine("    \"backgroundColor\": ${jsonStr(dmg.backgroundColor)},")
                    appendLine("    \"badgeIcon\": ${jsonStr(dmgBadgeIcon)},")
                    appendLine("    \"icon\": ${jsonStr(dmgIcon)},")
                    appendLine("    \"iconSize\": ${dmg.iconSize ?: "null"},")
                    appendLine("    \"iconTextSize\": ${dmg.iconTextSize ?: "null"},")
                    appendLine("    \"title\": ${jsonStr(dmg.title)},")
                    appendLine("    \"format\": ${jsonStr(dmg.format?.id)},")
                    appendLine("    \"windowX\": ${dmg.window.x ?: "null"},")
                    appendLine("    \"windowY\": ${dmg.window.y ?: "null"},")
                    appendLine("    \"windowWidth\": ${dmg.window.width ?: "null"},")
                    appendLine("    \"windowHeight\": ${dmg.window.height ?: "null"},")
                    appendLine("    \"contents\": [")
                    for ((index, entry) in dmg.contents.withIndex()) {
                        val comma = if (index < dmg.contents.size - 1) "," else ""
                        val parts =
                            buildList {
                                add("\"x\": ${entry.x}")
                                add("\"y\": ${entry.y}")
                                entry.type?.let { add("\"type\": \"${it.id}\"") }
                                entry.name?.let { add("\"name\": \"${it.escapeForJson()}\"") }
                                entry.path?.let { add("\"path\": \"${it.escapeForJson()}\"") }
                            }
                        appendLine("      {${parts.joinToString(", ")}}$comma")
                    }
                    appendLine("    ]")
                    appendLine("  }")
                    appendLine("}")
                }
            val metadataFile = File(outputDir, "packaging-metadata.json")
            metadataFile.writeText(metadata)
            logger.info("Exported macOS packaging metadata to: ${metadataFile.absolutePath}")
        }

        /**
         * Copies a DMG asset file into the assets directory, preserving its extension.
         * Returns the relative path (e.g. "dmg-assets/background.png") or null if no source file.
         */
        private fun copyDmgAsset(
            source: File?,
            assetsDir: File,
            baseName: String,
        ): String? {
            if (source == null || !source.isFile) return null
            assetsDir.mkdirs()
            val dest = File(assetsDir, "$baseName.${source.extension}")
            // Skip copy when source is already the destination (e.g. padDmgBackgroundForTitleBar
            // wrote directly into assetsDir). Kotlin's copyTo(overwrite=true) deletes the target
            // before copying, which destroys the source when they are the same file (issue #166).
            if (source.canonicalPath != dest.canonicalPath) {
                source.copyTo(dest, overwrite = true)
                logger.info("Copied DMG asset: ${source.absolutePath} → ${dest.absolutePath}")
            }
            return "dmg-assets/${dest.name}"
        }

        private fun jsonStr(value: String?): String = value?.let { "\"${it.escapeForJson()}\"" } ?: "null"

        private fun ensureResourcesDirForElectronBuilder(appDir: File) {
            if (currentOS == OS.MacOS) return
            val resourcesDir = appDir.resolve("resources")
            if (!resourcesDir.exists()) {
                resourcesDir.mkdirs()
            }
        }

        private fun ensureMacAdHocSigning(
            appDir: File,
            targetFormat: TargetFormat,
        ) {
            if (currentOS != OS.MacOS) return
            if (!appDir.isDirectory) return

            // For PKG (App Store), re-sign the .app with proper entitlements after .cfg modification.
            // The jpackage task signed the app, but updateExecutableTypeInAppImage() modified .cfg
            // files which invalidated the code signature. We must re-sign before electron-builder
            // packages it into the PKG.
            if (targetFormat == TargetFormat.Pkg) {
                resignAppForPkg(appDir)
                return
            }

            // When signing is configured, re-sign properly with Developer ID so the app
            // passes notarization (timestamp + hardened runtime). Without this, DMG/ZIP
            // formats would ship with an ad-hoc signature that Apple rejects.
            val signer = macSigner
            if (signer != null && signer.settings != null) {
                resignApp(appDir, "${targetFormat.name} format")
                return
            }

            // Fallback: ad-hoc signing for unsigned builds
            logger.info("Applying ad-hoc code signature to macOS app before electron-builder packaging")

            execOperations.exec { spec ->
                spec.executable = "codesign"
                spec.args =
                    listOf(
                        "--force",
                        "--deep",
                        "--sign",
                        "-",
                        appDir.absolutePath,
                    )
                spec.isIgnoreExitValue = false
            }

            logger.info("Ad-hoc signature applied successfully")
        }

        /**
         * Re-signs the .app bundle with the configured [macSigner], preserving Developer ID,
         * secure timestamp, and hardened runtime. This is needed because
         * [updateExecutableTypeInAppImage] modifies .cfg files which invalidates the
         * code signature applied earlier by jpackage.
         *
         * Mirrors the signing flow in [AbstractJPackageTask.modifyRuntimeOnMacOsIfNeeded]:
         * sign individual binaries inside-out, then seal each container directory.
         */
        private fun resignApp(
            appDir: File,
            label: String,
        ) {
            val signer = macSigner ?: return
            val appEntitlements = macEntitlementsFile.orNull?.asFile
            val runtimeEntitlements = macRuntimeEntitlementsFile.orNull?.asFile

            logger.info("Re-signing macOS app after .cfg modification for $label")

            // Re-sign all executables and dylibs in the runtime directory
            val runtimeDir = appDir.resolve("Contents/runtime")
            if (runtimeDir.exists()) {
                runtimeDir.walk().forEach { file ->
                    val path = file.toPath()
                    if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                        (path.isExecutable() || file.name.isDylibPath)
                    ) {
                        signer.sign(file, runtimeEntitlements)
                    }
                }
                signer.sign(runtimeDir, runtimeEntitlements, forceEntitlements = true)
            }

            // Re-sign native libs in Frameworks directory
            val frameworksDir = appDir.resolve("Contents/Frameworks")
            if (frameworksDir.exists()) {
                frameworksDir.walk().forEach { file ->
                    val path = file.toPath()
                    if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && file.name.isDylibPath) {
                        signer.sign(file, appEntitlements)
                    }
                }
            }

            // Re-sign the entire app bundle
            signer.sign(appDir, appEntitlements, forceEntitlements = true)
        }

        /**
         * Re-signs the .app bundle for PKG builds (always App Store).
         * Delegates to [resignApp] for the core signing, then augments entitlements
         * with application-identifier and team-identifier for App Store submissions.
         */
        private fun resignAppForPkg(appDir: File) {
            resignApp(appDir, "PKG format")

            // For App Store builds, re-sign the bundle with augmented entitlements
            // (application-identifier + team-identifier required by TestFlight / Transporter, error 90886).
            if (macAppStore.orNull == true) {
                val signer = macSigner ?: return
                val appEntitlements = macEntitlementsFile.orNull?.asFile
                // augmentEntitlementsForAppStore returns null when settings is null (NoCertificateSigner /
                // unsigned builds). Fall back to the original entitlements so the app is never re-signed
                // without them — which would silently strip sandbox entitlements from the bundle.
                val bundleEntitlements = augmentEntitlementsForAppStore(appEntitlements, signer.settings)
                signer.sign(appDir, bundleEntitlements ?: appEntitlements, forceEntitlements = true)
            }
        }

        /**
         * Returns a copy of [entitlements] with `com.apple.application-identifier` and
         * `com.apple.developer.team-identifier` injected, which Apple requires for
         * TestFlight / App Store submissions.
         */
        private fun augmentEntitlementsForAppStore(
            entitlements: File?,
            settings: ValidatedMacOSSigningSettings?,
        ): File? {
            if (entitlements == null || settings == null) return null

            val teamId = settings.teamID
            if (teamId == null) {
                logger.warn(
                    "Cannot extract team ID from signing identity '${settings.identity}'. " +
                        "Add com.apple.application-identifier to your entitlements manually.",
                )
                return entitlements
            }
            val bundleId = settings.bundleID
            val appIdentifier = "$teamId.$bundleId"

            val content = entitlements.readText()
            if (content.contains("com.apple.application-identifier")) return entitlements

            logger.info("Injecting application-identifier ($appIdentifier) into entitlements for App Store")

            val additions =
                """
                |    <key>com.apple.application-identifier</key>
                |    <string>$appIdentifier</string>
                |    <key>com.apple.developer.team-identifier</key>
                |    <string>$teamId</string>
                """.trimMargin()
            val augmented = content.replace("</dict>", "$additions\n</dict>")

            val tempFile = File.createTempFile("entitlements-appstore-", ".plist")
            tempFile.deleteOnExit()
            tempFile.writeText(augmented)
            return tempFile
        }

        /**
         * Signs the PKG installer for App Store distribution using `productsign`.
         *
         * PKG is always treated as an App Store format. electron-builder creates an
         * unsigned PKG (installer identity is always null), and this method re-signs
         * it with the correct "3rd Party Mac Developer Installer" certificate.
         */
        private fun signPkgInstaller(outputDir: File) {
            if (currentOS != OS.MacOS) return
            if (macAppStore.orNull != true) return

            val signer = macSigner ?: return
            val settings = signer.settings ?: return

            // Resolve the installer identity from the configured Application identity
            val installerIdentity = "3rd Party Mac Developer Installer: ${settings.bareIdentityName}"

            val pkgFile =
                outputDir
                    .listFiles()
                    ?.firstOrNull { it.isFile && it.extension == "pkg" }
                    ?: run {
                        logger.warn("No .pkg file found in output directory; skipping PKG signing")
                        return
                    }

            logger.info("Signing PKG installer for App Store: ${pkgFile.name}")
            logger.info("Using installer identity: $installerIdentity")

            val signedPkg = File(outputDir, "${pkgFile.nameWithoutExtension}-signed.pkg")
            val keychainPath = settings.keychain?.absolutePath

            execOperations.exec { spec ->
                spec.executable = "productsign"
                spec.args =
                    buildList {
                        add("--sign")
                        add(installerIdentity)
                        if (keychainPath != null) {
                            add("--keychain")
                            add(keychainPath)
                        }
                        add(pkgFile.absolutePath)
                        add(signedPkg.absolutePath)
                    }
                spec.isIgnoreExitValue = false
            }

            // Replace the unsigned PKG with the signed one
            pkgFile.delete()
            signedPkg.renameTo(pkgFile)
            logger.lifecycle("Signed PKG installer: ${pkgFile.name}")
        }

        /**
         * Post-processes the DMG electron-builder just produced by recompressing it with LZMA (ULMO).
         *
         * electron-builder's bundled dmgbuild caps DMG compression at bzip2 (UDBZ), so we reconvert
         * with `hdiutil convert -format ULMO` — LZMA, ~20% smaller — then re-sign (reconversion drops
         * the signature) and refresh the auto-update blockmap/manifest checksums.
         *
         * Runs only when the user opted into maximum compression, left the DMG format unpinned, and
         * targets macOS 10.15+ (ULMO images do not mount on older systems). It is also skipped when
         * electron-builder is publishing inline, since the pre-recompression artifact would already
         * have been uploaded.
         */
        private fun recompressDmgWithLzma(
            outputDir: File,
            dist: JvmApplicationDistributions,
        ) {
            if (currentOS != OS.MacOS) return
            if (targetFormat != TargetFormat.Dmg) return
            if (dist.compressionLevel != CompressionLevel.Maximum) return

            dist.macOS.dmg.format?.let {
                logger.info("Skipping LZMA DMG recompression: an explicit dmg.format=$it is set")
                return
            }
            if (!MacDmgLzma.isUlmoCompatible(dist.macOS.minimumSystemVersion)) {
                logger.lifecycle(
                    "Skipping LZMA (ULMO) DMG recompression: minimumSystemVersion " +
                        "'${dist.macOS.minimumSystemVersion}' predates macOS 10.15. Keeping bzip2 (UDBZ).",
                )
                return
            }
            if (resolvePublishFlag() != "never") {
                logger.warn(
                    "Skipping LZMA (ULMO) DMG recompression: electron-builder is publishing inline " +
                        "(publish != never), so the DMG has already been uploaded. Use a separate upload " +
                        "step (publish = never) to benefit from LZMA recompression.",
                )
                return
            }

            val dmgFiles =
                outputDir
                    .listFiles { f -> f.isFile && f.extension.equals("dmg", ignoreCase = true) }
                    ?.toList()
                    .orEmpty()
            if (dmgFiles.isEmpty()) {
                logger.info("No .dmg artifact found to recompress in ${outputDir.absolutePath}")
                return
            }

            val resolvedArch = Arch.entries.first { it.id == targetArch.get() }
            val appBuilder = MacDmgLzma.locateAppBuilder(outputDir, resolvedArch)
            if (appBuilder == null) {
                logger.info("app-builder not found in npm cache; blockmaps will be dropped after recompression")
            }
            for (dmg in dmgFiles) {
                recompressSingleDmg(dmg, appBuilder)
            }
        }

        private fun recompressSingleDmg(
            dmg: File,
            appBuilder: File?,
        ) {
            val sizeBefore = dmg.length()
            val wasSigned = isDmgSigned(dmg)

            val recompressed = File(dmg.parentFile, "${dmg.nameWithoutExtension}.ulmo.dmg")
            if (recompressed.exists()) recompressed.delete()

            logger.lifecycle("Recompressing ${dmg.name} with LZMA (ULMO)…")
            execOperations.exec { spec ->
                spec.executable = "hdiutil"
                spec.args =
                    listOf("convert", dmg.absolutePath, "-format", "ULMO", "-o", recompressed.absolutePath, "-quiet")
                spec.isIgnoreExitValue = false
            }
            if (!recompressed.isFile) {
                throw GradleException("LZMA recompression produced no output: ${recompressed.absolutePath}")
            }

            if (!dmg.delete()) throw GradleException("Could not replace original DMG: ${dmg.absolutePath}")
            if (!recompressed.renameTo(dmg)) {
                recompressed.copyTo(dmg, overwrite = true)
                recompressed.delete()
            }

            // Reconversion drops the code signature, so re-sign when the source image was signed.
            if (wasSigned) signDmg(dmg)

            refreshDmgMetadata(dmg, appBuilder)
            verifyDmg(dmg)

            val sizeAfter = dmg.length()
            val savedPct = if (sizeBefore > 0) (sizeBefore - sizeAfter) * 100.0 / sizeBefore else 0.0
            logger.lifecycle(
                String.format(
                    Locale.ROOT,
                    "LZMA recompression: %s  %,d → %,d bytes (−%.1f%%)",
                    dmg.name,
                    sizeBefore,
                    sizeAfter,
                    savedPct,
                ),
            )
        }

        /** Whether [dmg] currently carries a code signature (electron-builder signs it when dmg.sign = true). */
        private fun isDmgSigned(dmg: File): Boolean {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            return try {
                val result =
                    execOperations.exec { spec ->
                        spec.executable = "codesign"
                        spec.args = listOf("-v", "--verify", dmg.absolutePath)
                        spec.isIgnoreExitValue = true
                        spec.standardOutput = stdout
                        spec.errorOutput = stderr
                    }
                result.exitValue == 0
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Signs [dmg] with the configured Developer ID Application identity. Uses `--force` directly
         * (no prior `--remove-signature`, which fails on the freshly-converted, unsigned image).
         */
        private fun signDmg(dmg: File) {
            val settings = macSigner?.settings
            if (settings == null) {
                logger.warn(
                    "${dmg.name} was signed before recompression but no Developer ID identity " +
                        "is available to re-sign it.",
                )
                return
            }
            logger.info("Re-signing ${dmg.name} with '${settings.fullDeveloperID}' after recompression")
            execOperations.exec { spec ->
                spec.executable = "codesign"
                spec.args =
                    buildList {
                        add("--force")
                        add("--timestamp")
                        add("--sign")
                        add(settings.fullDeveloperID)
                        settings.keychain?.let {
                            add("--keychain")
                            add(it.absolutePath)
                        }
                        add(dmg.absolutePath)
                    }
                spec.isIgnoreExitValue = false
            }
        }

        /**
         * Regenerates the differential-update blockmap for the recompressed [dmg] (via electron-builder's
         * app-builder) and rewrites the sha512/size/blockMapSize any local manifest records for it.
         * When app-builder is unavailable, the stale blockmap is dropped and updaters fall back to a
         * full download.
         */
        private fun refreshDmgMetadata(
            dmg: File,
            appBuilder: File?,
        ) {
            val blockmap = File(dmg.parentFile, "${dmg.name}.blockmap")
            var newBlockMapSize: Long? = null
            if (appBuilder != null) {
                try {
                    execOperations.exec { spec ->
                        spec.executable = appBuilder.absolutePath
                        spec.args = listOf("blockmap", "--input", dmg.absolutePath, "--output", blockmap.absolutePath)
                        spec.isIgnoreExitValue = false
                    }
                    if (blockmap.isFile) newBlockMapSize = blockmap.length()
                } catch (e: Exception) {
                    logger.warn("Failed to regenerate blockmap for ${dmg.name}: ${e.message}. Dropping stale blockmap.")
                    blockmap.delete()
                }
            } else if (blockmap.exists()) {
                blockmap.delete()
            }

            val newHash = UpdateYmlChecksums.sha512Base64(dmg)
            val newSize = dmg.length()
            val ymls =
                dmg.parentFile.listFiles { f ->
                    f.isFile && (f.extension == "yml" || f.extension == "yaml")
                } ?: return
            for (yml in ymls) {
                val content = yml.readText()
                if (!content.contains(dmg.name)) continue
                val updated = UpdateYmlChecksums.updateYamlEntry(content, dmg.name, newHash, newSize, newBlockMapSize)
                if (updated != content) {
                    yml.writeText(updated)
                    logger.lifecycle("Updated auto-update manifest ${yml.name} for ${dmg.name}")
                }
            }
        }

        /** Verifies the recompressed image mounts and its checksums are intact. */
        private fun verifyDmg(dmg: File) {
            execOperations.exec { spec ->
                spec.executable = "hdiutil"
                spec.args = listOf("verify", dmg.absolutePath)
                spec.isIgnoreExitValue = false
            }
        }

        /**
         * Signs the produced `.deb`/`.rpm` with a GPG key and exports the public key next to
         * each artifact, so users can verify a direct download (`gpg --verify` / `rpm -K`)
         * without configuring a repository. No-op unless Linux signing is enabled.
         */
        private fun signLinuxPackage(
            outputDir: File,
            dist: JvmApplicationDistributions,
        ) {
            if (currentOS != OS.Linux) return
            if (targetFormat != TargetFormat.Deb && targetFormat != TargetFormat.Rpm) return

            val signing = dist.linux.signing
            if (!signing.enabled.get()) return

            val keyId = signing.keyId.orNull
            if (keyId.isNullOrBlank()) {
                logger.warn("Linux signing enabled but no signing.keyId configured; skipping signing")
                return
            }

            val packages =
                outputDir
                    .listFiles { file ->
                        file.isFile && (file.extension.equals("deb", true) || file.extension.equals("rpm", true))
                    }?.toList()
                    .orEmpty()
            if (packages.isEmpty()) {
                logger.warn("Linux signing enabled but no .deb/.rpm artifacts found in ${outputDir.absolutePath}")
                return
            }

            LinuxSigner(runExternalTool, logger).sign(
                packages = packages,
                keyId = keyId,
                keyFile =
                    signing.keyFile.orNull
                        ?.asFile,
                passphrase = signing.passphrase.orNull,
                debMethod = signing.debMethod,
                requireDetachedSignature = isLinuxSilentUpdateEnabled(dist),
            )
        }

        /**
         * Whether passwordless signature-verified self-update should be wired into this DEB/RPM.
         * Requires Linux, a deb/rpm target, and signing to be fully configured.
         */
        private fun isLinuxSilentUpdateEnabled(dist: JvmApplicationDistributions): Boolean {
            if (currentOS != OS.Linux) return false
            if (targetFormat != TargetFormat.Deb && targetFormat != TargetFormat.Rpm) return false
            val signing = dist.linux.signing
            if (!signing.silentUpdate.get()) return false
            if (!signing.enabled.get()) {
                logger.warn("linux.signing.silentUpdate requires linux.signing.enabled = true; ignoring silentUpdate")
                return false
            }
            if (signing.keyId.orNull.isNullOrBlank()) {
                logger.warn("linux.signing.silentUpdate requires a signing.keyId; ignoring silentUpdate")
                return false
            }
            return true
        }

        /**
         * Exports the signing public key into the app's `resources/` so the installed update helper
         * can verify downloaded updates against it. Must run before electron-builder packages the app.
         */
        private fun bundleUpdatePublicKey(
            workingAppDir: File,
            dist: JvmApplicationDistributions,
        ) {
            if (!isLinuxSilentUpdateEnabled(dist)) return
            val signing = dist.linux.signing
            val keyId = signing.keyId.orNull ?: return
            val dest = workingAppDir.resolve("resources/nucleus-update.pub.asc")
            dest.parentFile.mkdirs()
            LinuxSigner(runExternalTool, logger).exportPublicKey(
                keyId = keyId,
                keyFile =
                    signing.keyFile.orNull
                        ?.asFile,
                passphrase = signing.passphrase.orNull,
                destination = dest,
            )
            logger.lifecycle("Bundled update public key into resources/nucleus-update.pub.asc")
        }

        private fun prepareLinuxIconSet(outputDir: File): File? {
            if (currentOS != OS.Linux) return null

            val iconFile = linuxIconFile.orNull?.asFile ?: return null
            if (!iconFile.isFile) {
                logger.warn("Linux icon file not found: ${iconFile.absolutePath}")
                return null
            }

            val extension = iconFile.extension.lowercase(Locale.ROOT)
            if (extension != "png") {
                // Let electron-builder handle non-PNG icons as-is.
                return iconFile
            }

            val source = ImageIO.read(iconFile)
            if (source == null) {
                logger.warn("Unable to read Linux icon: ${iconFile.absolutePath}")
                return iconFile
            }

            val iconsDir = outputDir.resolve("linux-icons")
            if (iconsDir.exists()) iconsDir.deleteRecursively()
            iconsDir.mkdirs()

            @Suppress("MagicNumber")
            val sizes = listOf(16, 32, 48, 64, 128, 256, 512)
            for (size in sizes) {
                val resized = resizeIcon(source, size, size)
                val target = iconsDir.resolve("${size}x$size.png")
                ImageIO.write(resized, "png", target)
            }
            logger.info("Generated Linux icon set at: ${iconsDir.absolutePath}")
            return iconsDir
        }

        private fun resolveWindowsIcon(): File? {
            if (currentOS != OS.Windows) return null

            val iconFile = windowsIconFile.orNull?.asFile ?: return null
            if (!iconFile.isFile) {
                logger.warn("Windows icon file not found: ${iconFile.absolutePath}")
                return null
            }
            return iconFile
        }

        private data class AppXAsset(
            val targetFileName: String,
            val width: Int,
            val height: Int,
            val source: File?,
        )

        private fun stageAppXAssets(
            outputDir: File,
            windowsIconOverride: File?,
            hasExplicitWindowsIcon: Boolean,
        ) {
            val stagedAssetsDir = outputDir.resolve("build").resolve("appx")
            stagedAssetsDir.deleteRecursively()

            val assets = appXAssets()
            validateAppXAssetSources(assets)
            val fallbackImage = resolveAppXFallbackImage(windowsIconOverride, hasExplicitWindowsIcon)

            if (assets.none { it.source != null } && fallbackImage == null) return

            stagedAssetsDir.mkdirs()
            copyOrGenerateAppXAssets(assets, stagedAssetsDir, fallbackImage)

            if (assets.any { it.source == null } && fallbackImage == null) {
                logger.warn(
                    "Some AppX assets are missing and no readable fallback icon was found. " +
                        "Provide AppX logo files explicitly to avoid incomplete assets.",
                )
            }
        }

        private fun appXAssets(): List<AppXAsset> =
            listOf(
                AppXAsset("StoreLogo.png", APPX_STORE_LOGO_SIZE, APPX_STORE_LOGO_SIZE, appxStoreLogo.orNull?.asFile),
                AppXAsset(
                    "Square44x44Logo.png",
                    APPX_SQUARE44_LOGO_SIZE,
                    APPX_SQUARE44_LOGO_SIZE,
                    appxSquare44x44Logo.orNull?.asFile,
                ),
                AppXAsset(
                    "Square150x150Logo.png",
                    APPX_SQUARE150_LOGO_SIZE,
                    APPX_SQUARE150_LOGO_SIZE,
                    appxSquare150x150Logo.orNull?.asFile,
                ),
                AppXAsset(
                    "Wide310x150Logo.png",
                    APPX_WIDE_LOGO_WIDTH,
                    APPX_WIDE_LOGO_HEIGHT,
                    appxWide310x150Logo.orNull?.asFile,
                ),
            )

        private fun validateAppXAssetSources(assets: List<AppXAsset>) {
            for (asset in assets) {
                val source = asset.source ?: continue
                if (!source.isFile) {
                    throw GradleException("AppX asset file not found: ${source.absolutePath}")
                }
            }
        }

        private fun resolveAppXFallbackImage(
            windowsIconOverride: File?,
            hasExplicitWindowsIcon: Boolean,
        ): BufferedImage? =
            readImage(windowsIconOverride)
                ?: if (!hasExplicitWindowsIcon) readImage(linuxIconFile.orNull?.asFile) else null

        private fun copyOrGenerateAppXAssets(
            assets: List<AppXAsset>,
            stagedAssetsDir: File,
            fallbackImage: BufferedImage?,
        ) {
            for (asset in assets) {
                val target = stagedAssetsDir.resolve(asset.targetFileName)
                val source = asset.source
                if (source != null) {
                    source.copyTo(target, overwrite = true)
                } else if (fallbackImage != null) {
                    val generated = resizeIconToCanvas(fallbackImage, asset.width, asset.height)
                    ImageIO.write(generated, "png", target)
                }
            }
        }

        private fun readImage(file: File?): BufferedImage? {
            if (file == null || !file.isFile) return null
            return ImageIO.read(file)
        }

        private fun shouldSkipForMissingTool(): Boolean {
            if (currentOS != OS.Linux) return false

            return when (targetFormat) {
                TargetFormat.Snap -> {
                    // electron-builder builds snaps with its bundled `app-builder` tool (downloading
                    // its own snap template), and never invokes the `snapcraft` binary — so snapcraft
                    // is not a prerequisite. arm64 stays unsupported because that template
                    // (gnome-3-28-1804 build-snaps) is unavailable for the arch.
                    if (currentArch == Arch.Arm64) {
                        logger.lifecycle(
                            "Skipping Snap packaging on arm64: electron-builder uses " +
                                "build-snaps (gnome-3-28-1804) unavailable for arm64.",
                        )
                        true
                    } else {
                        false
                    }
                }
                TargetFormat.Flatpak -> {
                    if (!isCommandAvailable("flatpak")) {
                        logger.lifecycle(
                            "Skipping Flatpak packaging: 'flatpak' is not available on this runner. " +
                                "Install it with: sudo apt-get install -y flatpak flatpak-builder",
                        )
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        private fun isCommandAvailable(command: String): Boolean {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            return try {
                val result =
                    execOperations.exec { spec ->
                        spec.executable = "sh"
                        spec.args = listOf("-lc", "command -v $command >/dev/null 2>&1")
                        spec.isIgnoreExitValue = true
                        spec.standardOutput = stdout
                        spec.errorOutput = stderr
                    }
                result.exitValue == 0
            } catch (_: Exception) {
                false
            }
        }

        private fun prepareLinuxAfterInstallTemplate(
            outputDir: File,
            silentUpdate: Boolean,
        ): File? {
            if (currentOS != OS.Linux) return null
            if (targetFormat != TargetFormat.Deb &&
                targetFormat != TargetFormat.Rpm &&
                targetFormat != TargetFormat.Pacman
            ) {
                return null
            }

            val templateFile = outputDir.resolve("after-install-nucleus.tpl")
            val script =
                $$"""
                #!/bin/bash

                if type update-alternatives >/dev/null 2>&1; then
                    # Remove previous link if it doesn't use update-alternatives
                    if [ -L '/usr/bin/${executable}' -a -e '/usr/bin/${executable}' -a "`readlink '/usr/bin/${executable}'`" != '/etc/alternatives/${executable}' ]; then
                        rm -f '/usr/bin/${executable}'
                    fi
                    update-alternatives --install '/usr/bin/${executable}' '${executable}' '/opt/${sanitizedProductName}/${executable}' 100 || ln -sf '/opt/${sanitizedProductName}/${executable}' '/usr/bin/${executable}'
                else
                    ln -sf '/opt/${sanitizedProductName}/${executable}' '/usr/bin/${executable}'
                fi

                SANDBOX_PATH='/opt/${sanitizedProductName}/chrome-sandbox'
                if [ -e "$SANDBOX_PATH" ]; then
                    # Check if user namespaces are supported by the kernel and working with a quick test:
                    if ! { [[ -L /proc/self/ns/user ]] && unshare --user true; }; then
                        # Use SUID chrome-sandbox only on systems without user namespaces:
                        chmod 4755 "$SANDBOX_PATH" || true
                    else
                        chmod 0755 "$SANDBOX_PATH" || true
                    fi
                fi

                if hash update-mime-database 2>/dev/null; then
                    update-mime-database /usr/share/mime || true
                fi

                if hash update-desktop-database 2>/dev/null; then
                    update-desktop-database /usr/share/applications || true
                fi

                # Install apparmor profile. (Ubuntu 24+)
                # First check if the version of AppArmor running on the device supports our profile.
                # This is in order to keep backwards compatibility with Ubuntu 22.04 which does not support abi/4.0.
                # In that case, we just skip installing the profile since the app runs fine without it on 22.04.
                #
                # Those apparmor_parser flags are akin to performing a dry run of loading a profile.
                # https://wiki.debian.org/AppArmor/HowToUse#Dumping_profiles
                #
                # Unfortunately, at the moment AppArmor doesn't have a good story for backwards compatibility.
                # https://askubuntu.com/questions/1517272/writing-a-backwards-compatible-apparmor-profile
                if apparmor_status --enabled > /dev/null 2>&1; then
                  APPARMOR_PROFILE_SOURCE='/opt/${sanitizedProductName}/resources/apparmor-profile'
                  APPARMOR_PROFILE_TARGET='/etc/apparmor.d/${executable}'
                  if apparmor_parser --skip-kernel-load --debug "$APPARMOR_PROFILE_SOURCE" > /dev/null 2>&1; then
                    cp -f "$APPARMOR_PROFILE_SOURCE" "$APPARMOR_PROFILE_TARGET"

                    # Updating the current AppArmor profile is not possible and probably not meaningful in a chroot'ed environment.
                    # Use cases are for example environments where images for clients are maintained.
                    # There, AppArmor might correctly be installed, but live updating makes no sense.
                    if ! { [ -x '/usr/bin/ischroot' ] && /usr/bin/ischroot; } && hash apparmor_parser 2>/dev/null; then
                      # Extra flags taken from dh_apparmor:
                      # > By using '-W -T' we ensure that any abstraction updates are also pulled in.
                      # https://wiki.debian.org/AppArmor/Contribute/FirstTimeProfileImport
                      apparmor_parser --replace --write-cache --skip-read-cache "$APPARMOR_PROFILE_TARGET"
                    fi
                  else
                    echo "Skipping the installation of the AppArmor profile as this version of AppArmor does not seem to support the bundled profile"
                  fi
                fi
                """.trimIndent() + "\n"

            val fullScript = if (silentUpdate) script + linuxSilentUpdateAfterInstallBlock() else script
            templateFile.writeText(fullScript)
            logger.info("Generated Linux after-install template at: ${templateFile.absolutePath}")
            return templateFile
        }

        /**
         * Root-run afterInstall fragment that installs the passwordless update helper plus a polkit
         * policy scoped to it. The helper verifies a detached signature against the bundled public
         * key and only upgrades the package that owns the helper, so `allow_active=yes` cannot be
         * abused to install arbitrary packages. `${'$'}{executable}`/`${'$'}{sanitizedProductName}`
         * are substituted by electron-builder at package time.
         */
        private fun linuxSilentUpdateAfterInstallBlock(): String {
            val header =
                $$"""
                # --- Nucleus passwordless self-update (signature-verified) ---
                NUCLEUS_HELPER='/opt/${sanitizedProductName}/nucleus-update-helper'
                NUCLEUS_POLKIT_ACTION='dev.nucleusframework.${executable}.update'

                cat > "$NUCLEUS_HELPER" <<'NUCLEUS_HELPER_EOF'
                """.trimIndent()
            val footer =
                $$"""
                NUCLEUS_HELPER_EOF
                chmod 0755 "$NUCLEUS_HELPER"
                chown root:root "$NUCLEUS_HELPER" 2>/dev/null || true

                # polkit policy: an ACTIVE local session may run ONLY this helper without a password.
                POLKIT_DIR='/usr/share/polkit-1/actions'
                if mkdir -p "$POLKIT_DIR" 2>/dev/null; then
                cat > "$POLKIT_DIR/$NUCLEUS_POLKIT_ACTION.policy" <<NUCLEUS_POLKIT_EOF
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
                <policyconfig>
                  <action id="$NUCLEUS_POLKIT_ACTION">
                    <description>Install ${sanitizedProductName} updates</description>
                    <message>Authentication is required to install ${sanitizedProductName} updates</message>
                    <defaults>
                      <allow_any>auth_admin</allow_any>
                      <allow_inactive>auth_admin</allow_inactive>
                      <allow_active>yes</allow_active>
                    </defaults>
                    <annotate key="org.freedesktop.policykit.exec.path">$NUCLEUS_HELPER</annotate>
                    <annotate key="org.freedesktop.policykit.exec.allow_gui">true</annotate>
                  </action>
                </policyconfig>
                NUCLEUS_POLKIT_EOF
                fi
                """.trimIndent()
            return "\n" + header + "\n" + LinuxUpdateHelper.SCRIPT + "\n" + footer + "\n"
        }

        private fun resizeIcon(
            source: BufferedImage,
            width: Int,
            height: Int,
        ): BufferedImage =
            Thumbnails
                .of(source)
                .forceSize(width, height)
                .imageType(BufferedImage.TYPE_INT_ARGB)
                .asBufferedImage()

        private fun resizeIconToCanvas(
            source: BufferedImage,
            width: Int,
            height: Int,
        ): BufferedImage =
            Thumbnails
                .of(source)
                .size(width, height)
                .keepAspectRatio(true)
                .addFilter(Canvas(width, height, Positions.CENTER, true, Color(0, 0, 0, 0)))
                .imageType(BufferedImage.TYPE_INT_ARGB)
                .asBufferedImage()

        private fun resolveExecutableName(): String? =
            resolveLinuxExecutableName(
                targetFormat = targetFormat,
                snapName = distributions?.linux?.snap?.name,
                executableName = executableName.orNull,
            )

        private fun ensureLinuxExecutableAlias(appDir: File) {
            if (currentOS != OS.Linux) return

            val launcherName = packageName.get()

            // jpackage layout: bin/{packageName}
            val jpackageLauncher = appDir.resolve("bin").resolve(launcherName)

            // GraalVM native image layout: executable directly in appDir/
            // The binary name may differ from packageName (e.g. imageName), so
            // look for any executable file in appDir root.
            val graalvmLauncher =
                if (!jpackageLauncher.isFile) {
                    appDir.listFiles()?.firstOrNull { it.isFile && it.canExecute() }
                } else {
                    null
                }

            val launcher = jpackageLauncher.takeIf { it.isFile } ?: graalvmLauncher
            if (launcher == null) {
                logger.warn(
                    "Expected launcher not found at ${jpackageLauncher.absolutePath}. " +
                        "Skipping Linux executable alias creation.",
                )
                return
            }

            val relativePath = launcher.relativeTo(appDir).path

            val aliasName = resolveExecutableName() ?: launcherName.toNpmPackageName()
            val aliasFile = appDir.resolve(aliasName)
            if (aliasFile.exists()) return

            val script =
                $$"""
                #!/usr/bin/env sh
                SCRIPT="$0"
                while [ -L "$SCRIPT" ]; do
                  TARGET="$(readlink "$SCRIPT")"
                  case "$TARGET" in
                    /*) SCRIPT="$TARGET" ;;
                    *) SCRIPT="$(dirname "$SCRIPT")/$TARGET" ;;
                  esac
                done
                DIR="$(cd "$(dirname "$SCRIPT")" && pwd)"
                # electron-builder's AppImage AppRun injects --no-sandbox when unprivileged user
                # namespaces are unavailable (the Ubuntu 24.04+ default), on the assumption the
                # binary is Chromium/Electron. This is a JVM/native app with no such sandbox, and
                # its launcher may abort on the unknown option, so drop the flag before delegating.
                for arg in "$@"; do
                  shift
                  [ "$arg" = "--no-sandbox" ] && continue
                  set -- "$@" "$arg"
                done
                exec "$DIR/$$relativePath" "$@"
                """.trimIndent() + "\n"

            aliasFile.writeText(script)
            // Ensure mode is effectively 0755 to keep launcher visible/runnable for non-root users.
            aliasFile.setReadable(true, false)
            aliasFile.setWritable(false, false)
            aliasFile.setWritable(true, true)
            aliasFile.setExecutable(true, false)
            logger.info("Created Linux launcher alias: ${aliasFile.absolutePath}")
        }

        private fun ensureProjectPackageMetadata(
            outputDir: File,
            distributions: JvmApplicationDistributions,
        ) {
            val packageJson = File(outputDir, "package.json")
            if (packageJson.exists()) return

            val normalizedName = (executableName.orNull ?: packageName.get()).toNpmPackageName()
            val normalizedVersion = packageVersion.orNull?.takeIf { it.isNotBlank() } ?: "1.0.0"
            val normalizedDescription =
                distributions.description?.takeIf { it.isNotBlank() }
                    ?: "Packaged desktop application"
            val normalizedAuthor =
                distributions.vendor?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
            val repositoryUrl =
                distributions.publish.github
                    .takeIf { it.enabled }
                    ?.let { github ->
                        val owner = github.owner?.takeIf { value -> value.isNotBlank() }
                        val repo = github.repo?.takeIf { value -> value.isNotBlank() }
                        if (owner != null && repo != null) {
                            "https://github.com/$owner/$repo.git"
                        } else {
                            null
                        }
                    }
            val repositoryField =
                repositoryUrl
                    ?.let { value ->
                        ",\n  \"repository\": \"${value.escapeForJson()}\""
                    }.orEmpty()

            packageJson.writeText(
                """
                {
                  "name": "${normalizedName.escapeForJson()}",
                  "version": "${normalizedVersion.escapeForJson()}",
                  "description": "${normalizedDescription.escapeForJson()}",
                  "author": "${normalizedAuthor.escapeForJson()}",
                  "private": true$repositoryField
                }
                """.trimIndent(),
            )
            logger.info("Generated package metadata for electron-builder: ${packageJson.absolutePath}")
        }

        private fun String.toNpmPackageName(): String =
            lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]"), "-")
                .trim('-')
                .ifBlank { "app" }

        private fun String.escapeForJson(): String =
            replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

        /**
         * Removes parasitic executable files that electron-builder may copy from the app-image
         * into the output directory. These raw executables (e.g. java.exe, AppName.exe) are not
         * installers and should not be published as release assets.
         */
        private fun cleanupParasiticFiles(outputDir: File) {
            if (currentOS != OS.Windows) return

            val knownParasitic = setOf("java.exe", "javaw.exe")
            val rawLauncherName = "${packageName.get()}.exe"

            outputDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name in knownParasitic || file.name.equals(rawLauncherName, ignoreCase = true)) {
                    logger.info("Removing parasitic executable from output: ${file.name}")
                    file.delete()
                }
            }
        }

        /**
         * Removes isolated caches and the task-private app image copy created
         * for parallel-safe builds. Called only after electron-builder finishes.
         */
        private fun cleanupBuildTemporaries(outputDir: File) {
            for (dirName in listOf(".npm-cache", ".npm-prefix", ".electron-builder-cache", ".app-image")) {
                val dir = File(outputDir, dirName)
                if (dir.isDirectory) {
                    dir.deleteRecursively()
                }
            }
            File(outputDir, ".npmrc-user").delete()
            File(outputDir, ".npmrc-global").delete()
        }

        /**
         * Resolves the actual app directory inside the jpackage app-image output.
         *
         * jpackage produces: `<destinationDir>/<packageName>` on Linux/Windows
         *                  or `<destinationDir>/<packageName>.app` on macOS.
         */
        private fun resolveAppImageDir(): File {
            val root = appImageRoot.ioFile
            if (!root.isDirectory) {
                throw GradleException("App image directory not found: ${root.absolutePath}")
            }

            val name = packageName.get()

            // Try platform-specific name, then plain name, then single-child fallback
            val resolved =
                when {
                    currentOS == OS.MacOS && root.resolve("$name.app").isDirectory ->
                        root.resolve("$name.app")
                    root.resolve(name).isDirectory -> root.resolve(name)
                    else -> root.listFiles()?.singleOrNull { it.isDirectory }
                }

            return resolved ?: throw GradleException(
                "Unable to locate app image directory. " +
                    "Expected '$name' or '$name.app' inside: ${root.absolutePath}",
            )
        }

        /**
         * Builds the electron-builder CLI target arguments based on the current OS and target format.
         *
         * electron-builder uses platform flags: `--linux`, `--win`, `--mac`
         * followed by the target type (e.g., `deb`, `nsis`, `dmg`).
         */
        private fun buildElectronBuilderTargets(): List<String> {
            val platformFlag =
                when (currentOS) {
                    OS.Linux -> "--linux"
                    OS.Windows -> "--win"
                    OS.MacOS -> "--mac"
                }

            return listOf(platformFlag, targetFormat.electronBuilderTarget)
        }
    }

/**
 * Resolves the Linux `executableName` handed to electron-builder.
 *
 * For the Snap target, a non-blank [snapName] takes precedence: electron-builder 26.x derives the
 * snap name (`meta/snap.yaml` `name:` and the Snap Store namespace) from the executable name, so it
 * is the only lever that renames the snap independently of `packageName`. See issue #244.
 */
internal fun resolveLinuxExecutableName(
    targetFormat: TargetFormat,
    snapName: String?,
    executableName: String?,
): String? =
    if (targetFormat == TargetFormat.Snap && !snapName.isNullOrBlank()) snapName else executableName

/**
 * Creates a task-private copy of the app image directory so that parallel
 * packaging tasks do not interfere with each other when modifying .cfg files,
 * signing the bundle, or when electron-builder writes into the prepackaged dir.
 *
 * The copy is placed under `<outputDir>/.app-image/<appDirName>` and is cleaned
 * up by [AbstractElectronBuilderPackageTask.cleanupParasiticFiles] after electron-builder finishes.
 */
private fun copyAppImage(
    source: File,
    outputDir: File,
    logger: Logger,
): File {
    val workingRoot = File(outputDir, ".app-image")
    val destination = File(workingRoot, source.name)
    if (destination.exists()) {
        deleteWithRetry(destination, logger)
    }

    logger.info("Copying app image to task-private working directory: ${destination.absolutePath}")
    val srcPath = source.toPath()
    val destPath = destination.toPath()

    Files.walkFileTree(
        srcPath,
        emptySet(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.createDirectories(destPath.resolve(srcPath.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                val target = destPath.resolve(srcPath.relativize(file))
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                } else {
                    Files.copy(
                        file,
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
    return destination
}

/**
 * Returns an env map that isolates npm and electron-builder caches to subdirectories
 * of [outputDir]. This prevents EPERM/EBUSY errors on Windows when multiple
 * electron-builder tasks run in parallel and compete for shared caches (npx cache,
 * NSIS downloads, etc.). The prefix is also isolated to avoid npm 11+ ECOMPROMISED
 * errors caused by concurrent npx invocations sharing the global prefix.
 *
 * Additional npm config isolation (userconfig, globalconfig) prevents npm from
 * reading shared config files that could cause lock contention on Windows ARM64.
 */
private fun isolatedCacheEnv(outputDir: File): Map<String, String> {
    val cacheDir = File(outputDir, ".npm-cache").apply { mkdirs() }
    val prefixDir =
        File(outputDir, ".npm-prefix").apply {
            mkdirs()
            File(this, "lib").mkdirs()
        }
    val ebCacheDir = File(outputDir, ".electron-builder-cache").apply { mkdirs() }
    // Per-task .npmrc files prevent npm from reading/writing shared user/global config.
    // They must be separate files — npm rejects loading the same file as both user and global.
    val userNpmrc = File(outputDir, ".npmrc-user").apply { if (!exists()) createNewFile() }
    val globalNpmrc = File(outputDir, ".npmrc-global").apply { if (!exists()) createNewFile() }
    return mapOf(
        "NPM_CONFIG_CACHE" to cacheDir.absolutePath,
        "NPM_CONFIG_PREFIX" to prefixDir.absolutePath,
        "NPM_CONFIG_USERCONFIG" to userNpmrc.absolutePath,
        "NPM_CONFIG_GLOBALCONFIG" to globalNpmrc.absolutePath,
        "NPM_CONFIG_UPDATE_NOTIFIER" to "false",
        "ELECTRON_BUILDER_CACHE" to ebCacheDir.absolutePath,
    )
}

private fun resolveElectronBuilderEnvironment(
    targetFormat: TargetFormat,
    currentOs: OS,
    currentArchitecture: Arch,
    logger: Logger,
): Map<String, String> {
    val env = mutableMapOf<String, String>()

    // macOS: disable automatic certificate discovery when no signing identity is configured
    if (currentOs == OS.MacOS) {
        env["CSC_IDENTITY_AUTO_DISCOVERY"] = "false"
    }

    // Windows: auto-configure SignTool path for electron-builder signing
    val noExternalSignToolConfigured =
        currentOs == OS.Windows &&
            System.getenv("SIGNTOOL_PATH").isNullOrBlank() &&
            System.getenv("WINDOWS_SIGNTOOL_PATH").isNullOrBlank()

    if (noExternalSignToolConfigured) {
        val architectureId =
            when (currentArchitecture) {
                Arch.X64 -> "x64"
                Arch.Arm64 -> "arm64"
            }
        val signToolPath = WindowsKitsLocator.locateSignTool(architectureId)?.absolutePath
        if (signToolPath != null) {
            logger.info("Using Windows SDK SignTool: $signToolPath")
            env["SIGNTOOL_PATH"] = signToolPath
            env["WINDOWS_SIGNTOOL_PATH"] = signToolPath
        }
    }

    // Linux Snap: use destructive mode so snapcraft doesn't require LXD/multipass
    if (currentOs == OS.Linux && targetFormat == TargetFormat.Snap) {
        env["SNAPCRAFT_BUILD_ENVIRONMENT"] = "host"
    }

    return env
}

private const val DELETE_MAX_RETRIES = 5
private const val DELETE_RETRY_DELAY_MS = 1000L

/**
 * Deletes [dir] with retries. On Windows, files may be locked by processes
 * (e.g. a previously launched AppX app or antivirus). Before each retry,
 * attempts to kill any process whose executable resides inside [dir].
 */
private fun deleteWithRetry(
    dir: File,
    logger: Logger,
) {
    for (attempt in 1..DELETE_MAX_RETRIES) {
        // Kill processes that may lock files inside the directory
        killProcessesIn(dir, logger)
        if (dir.deleteRecursively()) return
        logger.warn("Failed to delete ${dir.absolutePath} (attempt $attempt/$DELETE_MAX_RETRIES)")
        if (attempt < DELETE_MAX_RETRIES) Thread.sleep(DELETE_RETRY_DELAY_MS)
    }
    // Last resort: try once more and throw if it still fails
    if (dir.exists() && !dir.deleteRecursively()) {
        error("Cannot delete ${dir.absolutePath} after $DELETE_MAX_RETRIES attempts. Is a process locking files?")
    }
}

/**
 * On Windows, kills any running processes whose executable path is inside [dir].
 */
private fun killProcessesIn(
    dir: File,
    logger: Logger,
) {
    if (!System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) return
    try {
        val dirPath = dir.absolutePath.lowercase()
        ProcessHandle.allProcesses().forEach { ph ->
            val cmd =
                ph
                    .info()
                    .command()
                    .orElse(null)
                    ?.lowercase() ?: return@forEach
            if (cmd.startsWith(dirPath)) {
                logger.info("Killing process ${ph.pid()} ($cmd)")
                ph.destroyForcibly()
            }
        }
        // Also try taskkill for the app exe name (covers processes launched from installed AppX location)
        dir
            .listFiles()
            ?.filter { it.extension.equals("exe", ignoreCase = true) }
            ?.forEach { exe ->
                try {
                    val rt = Runtime.getRuntime()
                    rt.exec(arrayOf("taskkill", "/F", "/IM", exe.name)).waitFor()
                } catch (_: Exception) {
                    // ignore — process may not be running
                }
            }
        Thread.sleep(DELETE_RETRY_DELAY_MS)
    } catch (e: Exception) {
        logger.warn("Failed to kill locked processes: ${e.message}")
    }
}

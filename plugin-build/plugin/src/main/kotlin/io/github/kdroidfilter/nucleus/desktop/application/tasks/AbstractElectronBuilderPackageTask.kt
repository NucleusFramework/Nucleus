/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.nucleus.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSSigningSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import dev.nucleusframework.nucleus.desktop.application.internal.UpdateYmlGenerator
import dev.nucleusframework.nucleus.desktop.application.internal.MacSigner
import dev.nucleusframework.nucleus.desktop.application.internal.MacSignerImpl
import dev.nucleusframework.nucleus.desktop.application.internal.NoCertificateSigner
import dev.nucleusframework.nucleus.desktop.application.internal.WindowsKitsLocator
import dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder.ElectronBuilderConfigGenerator
import dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder.ElectronBuilderInvocation
import dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder.ElectronBuilderToolManager
import dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder.NodeJsDetector
import dev.nucleusframework.nucleus.desktop.application.internal.files.isDylibPath
import dev.nucleusframework.nucleus.desktop.application.internal.MACOS_DMG_TITLE_BAR_HEIGHT
import dev.nucleusframework.nucleus.desktop.application.internal.padDmgBackgroundForTitleBar
import dev.nucleusframework.nucleus.desktop.application.internal.readImageDimensions
import dev.nucleusframework.nucleus.desktop.application.internal.updateExecutableTypeInAppImage
import dev.nucleusframework.nucleus.desktop.application.internal.validation.ValidatedMacOSSigningSettings
import dev.nucleusframework.nucleus.desktop.application.internal.validation.validate
import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.nucleus.internal.utils.Arch
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentArch
import dev.nucleusframework.nucleus.internal.utils.currentOS
import dev.nucleusframework.nucleus.internal.utils.ioFile
import dev.nucleusframework.nucleus.internal.utils.notNullProperty
import dev.nucleusframework.nucleus.internal.utils.nullableProperty
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
            ensureLinuxExecutableAlias(workingAppDir)
            updateExecutableTypeInAppImage(workingAppDir, targetFormat, logger, packageVersion.orNull)
            ensureMacAdHocSigning(workingAppDir, targetFormat)

            val npx = detectNpx()
            validateNodeVersion()

            val linuxIconOverride = prepareLinuxIconSet(outputDir)
            val windowsIconOverride = resolveWindowsIcon()
            val linuxAfterInstallTemplate = prepareLinuxAfterInstallTemplate(outputDir)
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
            if (!anyProviderEnabled) {
                logger.info("No publish provider enabled, using publish mode: never")
                return "never"
            }

            // Priority: env var > Gradle property > DSL default
            val envValue = System.getenv("NUCLEUS_PUBLISH_MODE")
            if (!envValue.isNullOrBlank()) {
                logger.info("Using publish mode from NUCLEUS_PUBLISH_MODE env var: $envValue")
                return envValue
            }

            val propValue = publishMode.orNull
            if (!propValue.isNullOrBlank()) {
                logger.info("Using publish mode from Gradle property: $propValue")
                return propValue
            }

            val dslValue = publish.publishMode.id
            logger.info("Using publish mode from DSL: $dslValue")
            return dslValue
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
                    executableName = executableName.orNull,
                    dmgBackgroundOverride = dmgBackgroundOverride,
                    dmgWindowOverride = dmgWindowOverride,
                )
            val configFile = File(outputDir, "electron-builder.yml")
            configFile.writeText(configContent)
            logger.info("Generated electron-builder config at: ${configFile.absolutePath}")
            return configFile
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
                    if (!isCommandAvailable("snapcraft")) {
                        logger.lifecycle("Skipping Snap packaging: 'snapcraft' is not available on this runner.")
                        true
                    } else if (currentArch == Arch.Arm64) {
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

        private fun prepareLinuxAfterInstallTemplate(outputDir: File): File? {
            if (currentOS != OS.Linux) return null
            if (targetFormat != TargetFormat.Deb && targetFormat != TargetFormat.Rpm) return null

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

            templateFile.writeText(script)
            logger.info("Generated Linux after-install template at: ${templateFile.absolutePath}")
            return templateFile
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

            val aliasName = executableName.orNull ?: launcherName.toNpmPackageName()
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

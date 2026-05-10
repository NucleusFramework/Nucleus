/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder

import dev.nucleusframework.nucleus.desktop.application.dsl.AppXSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.DmgSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.FileAssociation
import dev.nucleusframework.nucleus.desktop.application.dsl.FlatpakSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.nucleus.desktop.application.dsl.JvmMacOSPlatformSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.NsisSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.PublishSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.SnapSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import dev.nucleusframework.nucleus.internal.utils.Arch
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentOS
import java.io.File

/**
 * Generates an electron-builder YAML configuration from the Gradle DSL settings.
 *
 * Maps Nucleus DSL properties to the electron-builder configuration schema,
 * producing a `electron-builder.yml` file consumed by `electron-builder --prepackaged`.
 *
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class ElectronBuilderConfigGenerator {
    /**
     * Generates the electron-builder config YAML content.
     *
     * @param distributions The JVM application distribution settings from the DSL.
     * @param targetFormat The specific target format being built.
     * @param appImageDir The prepackaged app-image directory from jpackage.
     * @return The YAML configuration content as a string.
     */
    @Suppress("LongParameterList")
    fun generateConfig(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
        @Suppress("unused") appImageDir: File,
        targetArch: Arch,
        startupWMClass: String? = null,
        linuxIconOverride: File? = null,
        windowsIconOverride: File? = null,
        linuxAfterInstallTemplate: File? = null,
        executableName: String? = null,
        dmgBackgroundOverride: File? = null,
        dmgWindowOverride: DmgWindowOverride? = null,
    ): String {
        val yaml = StringBuilder()

        // --- Common settings ---
        val resolvedProductName =
            distributions.appName ?: distributions.packageName ?: executableName
                ?: error(
                    "No appName, packageName, or executableName available for electron-builder config",
                )
        yaml.appendLine("productName: \"${resolvedProductName.escapeForYamlDoubleQuotes()}\"")

        // On macOS, appId must match the CFBundleIdentifier from the app's Info.plist,
        // otherwise productbuild will fail to find the component package during PKG creation.
        val appId =
            if (currentOS == OS.MacOS) {
                distributions.macOS.bundleID?.takeIf { it.isNotBlank() }
                    ?: distributions.packageName?.let { "com.app.$it" }
            } else {
                distributions.packageName?.let { "com.app.$it" }
            }
        appendIfNotNull(yaml, "appId", appId)
        appendIfNotNull(yaml, "copyright", distributions.copyright)

        if (distributions.homepage != null) {
            yaml.appendLine("extraMetadata:")
            yaml.appendLine("  homepage: ${distributions.homepage}")
        }

        yaml.appendLine("directories:")
        yaml.appendLine("  output: .")

        appendIfNotNull(yaml, "compression", distributions.compressionLevel?.id)
        yaml.appendLine("artifactName: ${withTargetSuffix(distributions.artifactName, targetFormat)}")
        generateFileAssociations(yaml, distributions, targetFormat)

        // Use per-platform winCodeSign archives on Windows to avoid extraction failures
        // caused by macOS symlinks in the legacy combo archive (electron-builder#8149).
        if (currentOS == OS.Windows) {
            yaml.appendLine("toolsets:")
            yaml.appendLine("  winCodeSign: \"1.0.0\"")
        }

        // --- Platform-specific config ---
        when (currentOS) {
            OS.MacOS ->
                generateMacConfig(yaml, distributions, targetFormat, targetArch, dmgBackgroundOverride, dmgWindowOverride)
            OS.Windows ->
                generateWindowsConfig(
                    yaml,
                    distributions,
                    targetFormat,
                    targetArch,
                    windowsIconOverride,
                    executableName,
                )
            OS.Linux ->
                generateLinuxConfig(
                    yaml = yaml,
                    distributions = distributions,
                    targetFormat = targetFormat,
                    targetArch = targetArch,
                    startupWMClass = startupWMClass,
                    linuxIconOverride = linuxIconOverride,
                    linuxAfterInstallTemplate = linuxAfterInstallTemplate,
                    executableName = executableName,
                )
        }

        // --- Protocols ---
        if (distributions.protocols.isNotEmpty()) {
            yaml.appendLine("protocols:")
            for (protocol in distributions.protocols) {
                yaml.appendLine("  - name: \"${protocol.name}\"")
                yaml.appendLine("    schemes:")
                for (scheme in protocol.schemes) {
                    yaml.appendLine("      - \"$scheme\"")
                }
            }
        }

        // --- Publishing ---
        generatePublishConfig(yaml, distributions.publish)

        return yaml.toString()
    }

    private fun generateMacConfig(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
        targetArch: Arch,
        dmgBackgroundOverride: File? = null,
        windowOverride: DmgWindowOverride? = null,
    ) {
        yaml.appendLine("mac:")
        yaml.appendLine("  target:")
        yaml.appendLine("    - target: ${targetFormat.id}")
        yaml.appendLine("      arch: ${targetArch.id}")
        appendIfNotNull(yaml, "  category", distributions.macOS.appCategory)
        appendIfNotNull(
            yaml,
            "  icon",
            distributions.macOS.iconFile.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(yaml, "  minimumSystemVersion", distributions.macOS.minimumSystemVersion)

        // When not signing, disable signature-related features
        if (distributions.macOS.signing.sign.orNull != true) {
            yaml.appendLine("  identity: null")
            yaml.appendLine("  hardenedRuntime: false")
            yaml.appendLine("  gatekeeperAssess: false")
        }

        when (targetFormat) {
            TargetFormat.Dmg -> generateDmgConfig(yaml, distributions.macOS.dmg, dmgBackgroundOverride, windowOverride)
            TargetFormat.Pkg -> {
                yaml.appendLine("pkg:")
                appendIfNotNull(yaml, "  installLocation", distributions.macOS.installationPath)
                yaml.appendLine("  isRelocatable: false")
                if (distributions.macOS.signing.sign.orNull != true) {
                    yaml.appendLine("  identity: null")
                } else {
                    val installerIdentity = resolveInstallerIdentity(distributions.macOS)
                    if (installerIdentity != null) {
                        yaml.appendLine("  identity: \"$installerIdentity\"")
                    }
                }
            }
            else -> {}
        }
    }

    private fun generateDmgConfig(
        yaml: StringBuilder,
        dmg: DmgSettings,
        dmgBackgroundOverride: File? = null,
        windowOverride: DmgWindowOverride? = null,
    ) {
        yaml.appendLine("dmg:")
        yaml.appendLine("  sign: ${dmg.sign}")
        val backgroundPath =
            dmgBackgroundOverride?.absolutePath
                ?: dmg.background.orNull
                    ?.asFile
                    ?.absolutePath
        appendIfNotNull(yaml, "  background", backgroundPath)
        appendIfNotNull(yaml, "  backgroundColor", dmg.backgroundColor)
        appendIfNotNull(
            yaml,
            "  badgeIcon",
            dmg.badgeIcon.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "  icon",
            dmg.icon.orNull
                ?.asFile
                ?.absolutePath,
        )
        dmg.iconSize?.let { yaml.appendLine("  iconSize: $it") }
        dmg.iconTextSize?.let { yaml.appendLine("  iconTextSize: $it") }
        appendIfNotNull(yaml, "  title", dmg.title)
        dmg.format?.let { yaml.appendLine("  format: ${it.id}") }
        appendIfNotNull(yaml, "  size", dmg.size)
        dmg.shrink?.let { yaml.appendLine("  shrink: $it") }

        val w = dmg.window
        val hasWindowConfig = w.x != null || w.y != null || w.width != null || w.height != null || windowOverride != null
        if (hasWindowConfig) {
            yaml.appendLine("  window:")
            w.x?.let { yaml.appendLine("    x: $it") }
            w.y?.let { yaml.appendLine("    y: $it") }
            val overrideWidth = windowOverride?.width ?: w.width
            val overrideHeight = windowOverride?.height ?: w.height
            overrideWidth?.let { yaml.appendLine("    width: $it") }
            overrideHeight?.let { yaml.appendLine("    height: $it") }
        }

        if (dmg.contents.isNotEmpty()) {
            yaml.appendLine("  contents:")
            for (entry in dmg.contents) {
                yaml.appendLine("    - x: ${entry.x}")
                yaml.appendLine("      y: ${entry.y}")
                entry.type?.let { yaml.appendLine("      type: ${it.id}") }
                entry.name?.let { appendIfNotNull(yaml, "      name", it) }
                entry.path?.let { appendIfNotNull(yaml, "      path", it) }
            }
        }
    }

    data class DmgWindowOverride(
        val width: Int,
        val height: Int,
    )

    private fun generateWindowsConfig(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
        targetArch: Arch,
        windowsIconOverride: File?,
        executableName: String?,
    ) {
        yaml.appendLine("win:")
        yaml.appendLine("  target:")
        yaml.appendLine("    - target: ${targetFormat.electronBuilderTarget}")
        yaml.appendLine("      arch: ${targetArch.id}")
        appendIfNotNull(yaml, "  executableName", executableName)
        val windowsIcon =
            distributions.windows.iconFile.orNull
                ?.asFile ?: windowsIconOverride
        appendIfNotNull(
            yaml,
            "  icon",
            windowsIcon?.absolutePath,
        )

        generateWindowsSigningConfig(yaml, distributions)

        when (targetFormat) {
            TargetFormat.Nsis, TargetFormat.Exe -> {
                yaml.appendLine("nsis:")
                generateNsisSettings(yaml, distributions.windows.nsis, "  ")
            }
            TargetFormat.NsisWeb -> {
                yaml.appendLine("nsisWeb:")
                generateNsisSettings(yaml, distributions.windows.nsis, "  ")
            }
            TargetFormat.Msi -> {
                yaml.appendLine("msi:")
                appendIfNotNull(yaml, "  upgradeCode", distributions.windows.upgradeUuid)
            }
            TargetFormat.AppX -> generateAppXConfig(yaml, distributions.windows.appx)
            TargetFormat.Portable -> yaml.appendLine("portable: {}")
            else -> {}
        }
    }

    private fun generateWindowsSigningConfig(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
    ) {
        val signing = distributions.windows.signing
        if (!signing.enabled) return

        yaml.appendLine("  signtoolOptions:")
        appendIfNotNull(
            yaml,
            "    certificateFile",
            signing.certificateFile.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(yaml, "    certificatePassword", signing.certificatePassword)
        appendIfNotNull(yaml, "    certificateSha1", signing.certificateSha1)
        appendIfNotNull(yaml, "    certificateSubjectName", signing.certificateSubjectName)
        appendIfNotNull(yaml, "    rfc3161TimeStampServer", signing.timestampServer)
        yaml.appendLine("    signingHashAlgorithms:")
        yaml.appendLine("      - ${signing.algorithm.id}")

        if (signing.azureTenantId != null) {
            yaml.appendLine("  azureSignOptions:")
            appendIfNotNull(yaml, "    endpoint", signing.azureEndpoint)
            appendIfNotNull(yaml, "    certificateProfileName", signing.azureCertificateProfileName)
            appendIfNotNull(yaml, "    codeSigningAccountName", signing.azureCodeSigningAccountName)
        }
    }

    private fun generateFileAssociations(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
    ) {
        val associations =
            when (targetFormat) {
                TargetFormat.Exe,
                TargetFormat.Nsis,
                TargetFormat.NsisWeb,
                TargetFormat.Msi,
                -> distributions.windows.fileAssociations
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                -> distributions.macOS.fileAssociations
                else -> emptySet()
            }
        if (associations.isEmpty()) return

        yaml.appendLine("fileAssociations:")
        for (association in associations) {
            appendFileAssociation(yaml, association)
        }
    }

    private fun appendFileAssociation(
        yaml: StringBuilder,
        association: FileAssociation,
    ) {
        val normalizedExtension = association.extension.trim().removePrefix(".")
        if (normalizedExtension.isBlank()) return

        yaml.appendLine("  - ext: \"$normalizedExtension\"")
        appendIfNotNull(yaml, "    name", association.description)
        appendIfNotNull(yaml, "    description", association.description)
        appendIfNotNull(yaml, "    icon", association.iconFile?.absolutePath)
    }

    private fun withTargetSuffix(
        artifactName: String,
        targetFormat: TargetFormat,
    ): String {
        val template = artifactName
        // Only add a format suffix for formats whose id differs from the file extension,
        // to disambiguate formats sharing .exe (nsis, nsis-web, portable)
        val needsSuffix = targetFormat in setOf(TargetFormat.Nsis, TargetFormat.NsisWeb, TargetFormat.Portable)
        if (!needsSuffix) return template
        val marker = ".\${ext}"
        val suffix = "-${targetFormat.id}"
        return if (template.contains(marker)) {
            template.replace(marker, "$suffix$marker")
        } else {
            "$template$suffix"
        }
    }

    private fun generateNsisSettings(
        yaml: StringBuilder,
        nsis: NsisSettings,
        indent: String,
    ) {
        yaml.appendLine("${indent}oneClick: ${nsis.oneClick}")
        yaml.appendLine("${indent}allowElevation: ${nsis.allowElevation}")
        yaml.appendLine("${indent}perMachine: ${nsis.perMachine}")
        yaml.appendLine("${indent}allowToChangeInstallationDirectory: ${nsis.allowToChangeInstallationDirectory}")
        yaml.appendLine("${indent}createDesktopShortcut: ${nsis.createDesktopShortcut}")
        yaml.appendLine("${indent}createStartMenuShortcut: ${nsis.createStartMenuShortcut}")
        yaml.appendLine("${indent}runAfterFinish: ${nsis.runAfterFinish}")
        yaml.appendLine("${indent}deleteAppDataOnUninstall: ${nsis.deleteAppDataOnUninstall}")
        yaml.appendLine("${indent}warningsAsErrors: false")

        appendNsisFileSettings(yaml, nsis, indent)

        if (nsis.multiLanguageInstaller) {
            yaml.appendLine("${indent}multiLanguageInstaller: true")
        }
        if (nsis.installerLanguages.isNotEmpty()) {
            yaml.appendLine("${indent}installerLanguages:")
            for (lang in nsis.installerLanguages) {
                yaml.appendLine("$indent  - \"$lang\"")
            }
        }
    }

    private fun appendNsisFileSettings(
        yaml: StringBuilder,
        nsis: NsisSettings,
        indent: String,
    ) {
        appendIfNotNull(
            yaml,
            "${indent}installerIcon",
            nsis.installerIcon.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}uninstallerIcon",
            nsis.uninstallerIcon.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}license",
            nsis.license.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}include",
            nsis.includeScript.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}script",
            nsis.script.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}installerHeader",
            nsis.installerHeader.orNull
                ?.asFile
                ?.absolutePath,
        )
        appendIfNotNull(
            yaml,
            "${indent}installerSidebar",
            nsis.installerSidebar.orNull
                ?.asFile
                ?.absolutePath,
        )
    }

    private fun generateAppXConfig(
        yaml: StringBuilder,
        appx: AppXSettings,
    ) {
        yaml.appendLine("appx:")
        appendIfNotNull(yaml, "  applicationId", appx.applicationId)
        appendIfNotNull(yaml, "  displayName", appx.displayName)
        appendIfNotNull(yaml, "  identityName", appx.identityName)
        appendIfNotNull(yaml, "  publisher", appx.publisher)
        appendIfNotNull(yaml, "  publisherDisplayName", appx.publisherDisplayName)
        appx.languages?.let { languages ->
            yaml.appendLine("  languages:")
            for (lang in languages) {
                yaml.appendLine("    - \"$lang\"")
            }
        }
        if (appx.addAutoLaunchExtension) {
            yaml.appendLine("  addAutoLaunchExtension: true")
        }
        appendIfNotNull(yaml, "  backgroundColor", appx.backgroundColor)
        if (appx.showNameOnTiles) {
            yaml.appendLine("  showNameOnTiles: true")
        }
        if (appx.setBuildNumber) {
            yaml.appendLine("  setBuildNumber: true")
        }
        appendIfNotNull(yaml, "  minVersion", appx.minVersion)
        appendIfNotNull(yaml, "  maxVersionTested", appx.maxVersionTested)
        appx.capabilities?.takeIf { it.isNotEmpty() }?.let { caps ->
            yaml.appendLine("  capabilities:")
            for (cap in caps) {
                yaml.appendLine("    - \"$cap\"")
            }
        }
    }

    private fun generateLinuxConfig(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
        targetArch: Arch,
        startupWMClass: String?,
        linuxIconOverride: File?,
        linuxAfterInstallTemplate: File?,
        executableName: String?,
    ) {
        yaml.appendLine("linux:")
        yaml.appendLine("  target:")
        yaml.appendLine("    - target: ${targetFormat.electronBuilderTarget}")
        yaml.appendLine("      arch: ${targetArch.id}")
        appendIfNotNull(yaml, "  executableName", executableName)
        val linuxIcon =
            linuxIconOverride ?: distributions.linux.iconFile.orNull
                ?.asFile
        appendIfNotNull(
            yaml,
            "  icon",
            linuxIcon?.absolutePath,
        )
        appendIfNotNull(yaml, "  category", distributions.linux.appCategory)
        appendIfNotNull(yaml, "  maintainer", distributions.linux.debMaintainer)
        appendIfNotNull(yaml, "  vendor", distributions.vendor)
        appendIfNotNull(yaml, "  description", distributions.description)
        appendLinuxDesktopEntryConfig(yaml, distributions, startupWMClass)

        when (targetFormat) {
            TargetFormat.Deb -> {
                yaml.appendLine("deb:")
                if (distributions.linux.debDepends.isNotEmpty()) {
                    yaml.appendLine("  depends:")
                    for (dep in distributions.linux.debDepends) {
                        yaml.appendLine("    - \"$dep\"")
                    }
                }
                appendIfNotNull(yaml, "  afterInstall", linuxAfterInstallTemplate?.absolutePath)
            }
            TargetFormat.Rpm -> {
                yaml.appendLine("rpm:")
                if (distributions.linux.rpmRequires.isNotEmpty()) {
                    yaml.appendLine("  depends:")
                    for (dep in distributions.linux.rpmRequires) {
                        yaml.appendLine("    - \"$dep\"")
                    }
                }
                appendIfNotNull(yaml, "  afterInstall", linuxAfterInstallTemplate?.absolutePath)
            }
            TargetFormat.Snap -> generateSnapConfig(yaml, distributions.linux.snap)
            TargetFormat.Flatpak -> generateFlatpakConfig(yaml, distributions.linux.flatpak)
            else -> {}
        }
    }

    private fun appendLinuxDesktopEntryConfig(
        yaml: StringBuilder,
        distributions: JvmApplicationDistributions,
        startupWMClass: String?,
    ) {
        val entryOverrides = linkedMapOf<String, String>()
        entryOverrides.putAll(distributions.linux.appImage.desktopEntries)
        val hasStartupWMClassOverride =
            entryOverrides.keys.any { it.equals("StartupWMClass", ignoreCase = true) }
        if (!hasStartupWMClassOverride) {
            startupWMClass?.takeIf { it.isNotBlank() }?.let {
                entryOverrides["StartupWMClass"] = it
            }
        }

        // Auto-inject MimeType from file associations and protocols,
        // mirroring electron-builder's LinuxTargetHelper.computeDesktopEntry behavior.
        val hasMimeTypeOverride =
            entryOverrides.keys.any { it.equals("MimeType", ignoreCase = true) }
        if (!hasMimeTypeOverride) {
            val mimeTypes =
                buildList {
                    for (association in distributions.linux.fileAssociations) {
                        association.mimeType.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    for (protocol in distributions.protocols) {
                        for (scheme in protocol.schemes) {
                            add("x-scheme-handler/$scheme")
                        }
                    }
                }
            if (mimeTypes.isNotEmpty()) {
                entryOverrides["MimeType"] = mimeTypes.joinToString(";", postfix = ";")
            }
        }

        if (entryOverrides.isEmpty()) return

        yaml.appendLine("  desktop:")
        yaml.appendLine("    entry:")
        for ((key, value) in entryOverrides) {
            yaml.appendLine("      \"${key.escapeForYamlDoubleQuotes()}\": \"${value.escapeForYamlDoubleQuotes()}\"")
        }
    }

    private fun generateSnapConfig(
        yaml: StringBuilder,
        snap: SnapSettings,
    ) {
        yaml.appendLine("snap:")
        yaml.appendLine("  confinement: ${snap.confinement.id}")
        yaml.appendLine("  grade: ${snap.grade.id}")
        appendIfNotNull(yaml, "  summary", snap.summary)
        appendIfNotNull(yaml, "  base", snap.base)
        if (snap.autoStart) {
            yaml.appendLine("  autoStart: true")
        }
        appendIfNotNull(yaml, "  compression", snap.compression?.id)
        if (snap.plugs.isNotEmpty()) {
            yaml.appendLine("  plugs:")
            for (plug in snap.plugs) {
                yaml.appendLine("    - \"${plug.id}\"")
            }
        }
    }

    private fun generateFlatpakConfig(
        yaml: StringBuilder,
        flatpak: FlatpakSettings,
    ) {
        yaml.appendLine("flatpak:")
        yaml.appendLine("  runtime: ${flatpak.runtime}")
        yaml.appendLine("  runtimeVersion: \"${flatpak.runtimeVersion}\"")
        yaml.appendLine("  sdk: ${flatpak.sdk}")
        yaml.appendLine("  branch: ${flatpak.branch}")
        appendIfNotNull(
            yaml,
            "  license",
            flatpak.license.orNull
                ?.asFile
                ?.absolutePath,
        )
        if (flatpak.finishArgs.isNotEmpty()) {
            yaml.appendLine("  finishArgs:")
            for (arg in flatpak.finishArgs) {
                yaml.appendLine("    - \"$arg\"")
            }
        }
    }

    private fun generatePublishConfig(
        yaml: StringBuilder,
        publish: PublishSettings,
    ) {
        val github = publish.github
        val s3 = publish.s3
        val generic = publish.generic

        if (!github.enabled && !s3.enabled && !generic.enabled) {
            // Explicitly disable publish to prevent electron-builder from auto-detecting
            // a publish provider via GH_TOKEN/GITHUB_TOKEN env vars and .git/config,
            // which causes a crash in computeChannelNames when resolution fails.
            yaml.appendLine("publish: null")
            return
        }

        yaml.appendLine("publish:")
        if (github.enabled) {
            yaml.appendLine("  - provider: github")
            appendIfNotNull(yaml, "    owner", github.owner)
            appendIfNotNull(yaml, "    repo", github.repo)
            appendIfNotNull(yaml, "    token", github.token)
            yaml.appendLine("    channel: ${github.channel.id}")
            yaml.appendLine("    releaseType: ${github.releaseType.id}")
        }
        if (s3.enabled) {
            yaml.appendLine("  - provider: s3")
            appendIfNotNull(yaml, "    bucket", s3.bucket)
            appendIfNotNull(yaml, "    region", s3.region)
            appendIfNotNull(yaml, "    path", s3.path)
            appendIfNotNull(yaml, "    acl", s3.acl)
        }
        if (generic.enabled) {
            yaml.appendLine("  - provider: generic")
            appendIfNotNull(yaml, "    url", generic.url)
            yaml.appendLine("    channel: ${generic.channel.id}")
            yaml.appendLine("    useMultipleRangeRequest: ${generic.useMultipleRangeRequest}")
        }
    }

    /**
     * Resolves the PKG installer signing identity.
     *
     * PKG is always treated as an App Store format, so signing is handled post-build
     * via `productsign` with the "3rd Party Mac Developer Installer" certificate.
     * This always returns `null` because electron-builder's `pkg.ts` hardcodes
     * `certType = "Developer ID Installer"`, making it impossible to match a
     * "3rd Party Mac Developer Installer" certificate at build time.
     */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    private fun resolveInstallerIdentity(macOS: JvmMacOSPlatformSettings): String? = null

    private fun appendIfNotNull(
        yaml: StringBuilder,
        key: String,
        value: String?,
    ) {
        if (value != null) {
            yaml.appendLine("$key: \"${value.escapeForYamlDoubleQuotes()}\"")
        }
    }

    private fun String.escapeForYamlDoubleQuotes(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

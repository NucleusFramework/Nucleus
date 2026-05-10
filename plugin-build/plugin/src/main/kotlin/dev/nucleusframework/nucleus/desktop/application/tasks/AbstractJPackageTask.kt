/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.application.dsl.FileAssociation
import dev.nucleusframework.nucleus.desktop.application.dsl.LaunchAgentDefinition
import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSSigningSettings
import dev.nucleusframework.nucleus.desktop.application.internal.LaunchAgentPlistGenerator
import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import dev.nucleusframework.nucleus.desktop.application.dsl.UrlProtocol
import dev.nucleusframework.nucleus.desktop.application.internal.APP_RESOURCES_DIR
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import dev.nucleusframework.nucleus.desktop.application.internal.JvmRuntimeProperties
import dev.nucleusframework.nucleus.desktop.application.internal.MacAssetsTool
import dev.nucleusframework.nucleus.desktop.application.internal.MacSigner
import dev.nucleusframework.nucleus.desktop.application.internal.MacSignerImpl
import dev.nucleusframework.nucleus.desktop.application.internal.NoCertificateSigner
import dev.nucleusframework.nucleus.desktop.application.internal.PlistKeys
import dev.nucleusframework.nucleus.desktop.application.internal.SKIKO_LIBRARY_PATH
import dev.nucleusframework.nucleus.desktop.application.internal.cliArg
import dev.nucleusframework.nucleus.desktop.application.internal.files.FileCopyingProcessor
import dev.nucleusframework.nucleus.desktop.application.internal.files.MacJarSignFileCopyingProcessor
import dev.nucleusframework.nucleus.desktop.application.internal.files.SimpleFileCopyingProcessor
import dev.nucleusframework.nucleus.desktop.application.internal.files.copyTo
import dev.nucleusframework.nucleus.desktop.application.internal.files.copyZipEntry
import dev.nucleusframework.nucleus.desktop.application.internal.files.findOutputFileOrDir
import dev.nucleusframework.nucleus.desktop.application.internal.files.isDylibPath
import dev.nucleusframework.nucleus.desktop.application.internal.files.isJarFile
import dev.nucleusframework.nucleus.desktop.application.internal.files.mangledName
import dev.nucleusframework.nucleus.desktop.application.internal.files.normalizedPath
import dev.nucleusframework.nucleus.desktop.application.internal.files.transformJar
import dev.nucleusframework.nucleus.desktop.application.internal.javaOption
import dev.nucleusframework.nucleus.desktop.application.internal.validation.validate
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.clearDirs
import dev.nucleusframework.nucleus.internal.utils.currentArch
import dev.nucleusframework.nucleus.internal.utils.currentOS
import dev.nucleusframework.nucleus.internal.utils.currentTarget
import dev.nucleusframework.nucleus.internal.utils.delete
import dev.nucleusframework.nucleus.internal.utils.ioFile
import dev.nucleusframework.nucleus.internal.utils.ioFileOrNull
import dev.nucleusframework.nucleus.internal.utils.mkdirs
import dev.nucleusframework.nucleus.internal.utils.notNullProperty
import dev.nucleusframework.nucleus.internal.utils.nullableProperty
import dev.nucleusframework.nucleus.internal.utils.stacktraceToString
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecResult
import org.gradle.work.ChangeType
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.InputChanges
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.ArrayList
import java.util.Calendar
import java.util.HashMap
import java.util.HashSet
import javax.inject.Inject
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Creates a jpackage app-image (the self-contained application directory with bundled JVM runtime).
 *
 * This task is now exclusively used for app-image creation (TargetFormat.RawAppImage).
 * All final packaging into installer formats (DMG, DEB, RPM, NSIS, etc.) is handled
 * by electron-builder via [AbstractElectronBuilderPackageTask].
 */
@DisableCachingByDefault(because = "Depends on external jpackage tool")
@Suppress("UnnecessaryAbstractClass", "TooGenericExceptionCaught", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "MaxLineLength")
abstract class AbstractJPackageTask
    @Inject
    constructor(
        @get:Input
        val targetFormat: TargetFormat,
    ) : AbstractJvmToolOperationTask("jpackage") {
        @get:InputFiles
        @get:Classpath
        val files: ConfigurableFileCollection = objects.fileCollection()

        /**
         * A hack to avoid conflicts between jar files in a flat dir.
         * We receive input jar files as a list (FileCollection) of files.
         * At that point we don't have access to jar files' coordinates.
         *
         * Some files can have the same simple names.
         * For example, a project containing two modules:
         *  1. :data:utils
         *  2. :ui:utils
         * produces:
         *  1. <PROJECT>/data/utils/build/../utils.jar
         *  2. <PROJECT>/ui/utils/build/../utils.jar
         *
         *  jpackage expects all files to be in one input directory (not sure),
         *  so the natural workaround to avoid overwrites/conflicts is to add a content hash
         *  to a file name. A better solution would be to preserve coordinates or relative paths,
         *  but it is not straightforward at this point.
         *
         *  The flag is needed for two things:
         *  1. Give users the ability to turn off the mangling, if they need to preserve names;
         *  2. Proguard transformation already flattens jar files & mangles names, so we don't
         *  need to mangle twice.
         */
        @get:Input
        val mangleJarFilesNames: Property<Boolean> = objects.notNullProperty(true)

        /**
         * Indicates that task will get the uber JAR as input.
         */
        @get:Input
        val packageFromUberJar: Property<Boolean> = objects.notNullProperty(false)

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val iconFile: RegularFileProperty = objects.fileProperty()

        @get:Input
        val launcherMainClass: Property<String> = objects.notNullProperty()

        @get:InputFile
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val launcherMainJar: RegularFileProperty = objects.fileProperty()

        @get:Input
        @get:Optional
        val launcherArgs: ListProperty<String> = objects.listProperty(String::class.java)

        @get:Input
        @get:Optional
        val launcherJvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

        @get:Input
        val packageName: Property<String> = objects.notNullProperty()

        @get:Input
        @get:Optional
        val appName: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val packageDescription: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val packageCopyright: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val packageVendor: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val packageVersion: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macPackageName: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macDockName: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macAppStore: Property<Boolean> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macAppCategory: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        val macMinimumSystemVersion: Property<String> = objects.nullableProperty()

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
        val packageBuildVersion: Property<String> = objects.nullableProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val macProvisioningProfile: RegularFileProperty = objects.fileProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.ABSOLUTE)
        val macRuntimeProvisioningProfile: RegularFileProperty = objects.fileProperty()

        @get:Input
        @get:Optional
        val winConsole: Property<Boolean> = objects.nullableProperty()

        @get:InputDirectory
        @get:Optional
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val runtimeImage: DirectoryProperty = objects.directoryProperty()

        @get:Input
        @get:Optional
        internal val nonValidatedMacBundleID: Property<String> = objects.nullableProperty()

        @get:Input
        @get:Optional
        internal val macExtraPlistKeysRawXml: Property<String> = objects.nullableProperty()

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

        @get:Input
        internal val fileAssociations: SetProperty<FileAssociation> = objects.setProperty(FileAssociation::class.java)

        @get:Input
        internal val urlProtocols: ListProperty<UrlProtocol> = objects.listProperty(UrlProtocol::class.java)

        @get:InputDirectory
        @get:Optional
        @get:PathSensitive(PathSensitivity.RELATIVE)
        internal val macLayeredIcons: DirectoryProperty = objects.directoryProperty()

        @get:Input
        internal val macLaunchAgents: ListProperty<LaunchAgentDefinition> =
            objects.listProperty(LaunchAgentDefinition::class.java).convention(emptyList())

        @get:Input
        @get:Optional
        val macOsSdkVersion: Property<String> = objects.nullableProperty()

        @get:Input
        val sandboxingEnabled: Property<Boolean> = objects.notNullProperty(false)

        private val iconMapping by lazy {
            val icons = fileAssociations.get().mapNotNull { it.iconFile }.distinct()
            if (icons.isEmpty()) return@lazy emptyMap()
            val iconTempNames: List<String> =
                mutableListOf<String>().apply {
                    val usedNames = mutableSetOf("${packageName.get()}.icns")
                    for (icon in icons) {
                        if (!icon.exists()) continue
                        if (usedNames.add(icon.name)) {
                            add(icon.name)
                            continue
                        }
                        val nameWithoutExtension = icon.nameWithoutExtension
                        val extension = icon.extension
                        for (n in 1UL..ULong.MAX_VALUE) {
                            val newName = "$nameWithoutExtension ($n).$extension"
                            if (usedNames.add(newName)) {
                                add(newName)
                                break
                            }
                        }
                    }
                }
            val appDir = destinationDir.ioFile.resolve("${packageName.get()}.app")
            val iconsDir = appDir.resolve("Contents").resolve("Resources")
            if (iconsDir.exists()) {
                iconsDir.deleteRecursively()
            }
            icons.zip(iconTempNames) { icon, newName -> icon to iconsDir.resolve(newName) }.toMap()
        }

        private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

        @get:Optional
        @get:Nested
        internal var nonValidatedMacSigningSettings: MacOSSigningSettings? = null

        private val shouldSign: Boolean
            get() = nonValidatedMacSigningSettings?.sign?.get() == true

        private val macSigner: MacSigner? by lazy {
            val nonValidatedSettings = nonValidatedMacSigningSettings
            if (currentOS == OS.MacOS) {
                if (shouldSign) {
                    val validatedSettings =
                        nonValidatedSettings!!.validate(nonValidatedMacBundleID, project, macAppStore)
                    MacSignerImpl(validatedSettings, runExternalTool)
                } else {
                    NoCertificateSigner(runExternalTool)
                }
            } else {
                null
            }
        }

        private val macAssetsTool by lazy { MacAssetsTool(runExternalTool, logger) }

        @get:LocalState
        protected val signDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/sign")

        @get:LocalState
        protected val jpackageResources: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/resources")

        @get:LocalState
        protected val skikoDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/skiko")

        @get:Internal
        private val libsDir: Provider<Directory> =
            workingDir.map {
                it.dir("libs")
            }

        @get:Internal
        private val packagedResourcesDir: Provider<Directory> =
            libsDir.map {
                it.dir("resources")
            }

        @get:Internal
        val appResourcesDir: DirectoryProperty = objects.directoryProperty()

        @get:Internal
        private val libsMappingFile: Provider<RegularFile> =
            workingDir.map {
                it.file("libs-mapping.txt")
            }

        @get:Internal
        private val libsMapping = FilesMapping()

        override fun makeArgs(tmpDir: File): MutableList<String> =
            super.makeArgs(tmpDir).apply {
                fun appDir(vararg pathParts: String): String {
                    /** For windows we need to pass '\\' to jpackage file, each '\' need to be escaped.
                     Otherwise '$APPDIR\resources' is passed to jpackage,
                     and '\r' is treated as a special character at run time.
                     */
                    val separator = if (currentTarget.os == OS.Windows) "\\\\" else "/"
                    return listOf("${'$'}APPDIR", *pathParts).joinToString(separator) { it }
                }

                cliArg("--input", libsDir)
                cliArg("--runtime-image", runtimeImage)
                cliArg("--resource-dir", jpackageResources)

                javaOption("-D$APP_RESOURCES_DIR=${appDir(packagedResourcesDir.ioFile.name)}")

                val mappedJar =
                    libsMapping[launcherMainJar.ioFile]?.singleOrNull { it.isJarFile }
                        ?: error("Main jar was not processed correctly: ${launcherMainJar.ioFile}")
                val mainJarPath = mappedJar.normalizedPath(base = libsDir.ioFile)
                cliArg("--main-jar", mainJarPath)
                cliArg("--main-class", launcherMainClass)

                if (currentOS == OS.Windows) {
                    cliArg("--win-console", winConsole)
                }
                cliArg("--icon", iconFile)
                launcherArgs.orNull?.forEach {
                    cliArg("--arguments", "'$it'")
                }
                launcherJvmArgs.orNull?.forEach {
                    javaOption(it)
                }
                val skikoPath =
                    when {
                        sandboxingEnabled.get() && currentOS == OS.MacOS -> appDir("..", "Frameworks")
                        sandboxingEnabled.get() -> appDir("resources")
                        else -> appDir()
                    }
                javaOption("-D$SKIKO_LIBRARY_PATH=$skikoPath")
                if (currentOS == OS.MacOS) {
                    macDockName.orNull?.let { dockName ->
                        javaOption("-Xdock:name=$dockName")
                    }
                    macProvisioningProfile.orNull?.let { provisioningProfile ->
                        cliArg("--app-content", provisioningProfile)
                    }
                }

                cliArg("--type", targetFormat.id)

                cliArg("--dest", destinationDir)
                cliArg("--verbose", verbose)

                cliArg("--name", packageName)
                cliArg("--description", packageDescription)
                cliArg("--copyright", packageCopyright)
                cliArg("--app-version", packageVersion)
                cliArg("--vendor", packageVendor)

                if (currentOS == OS.MacOS) {
                    cliArg("--mac-package-name", macPackageName)
                    cliArg("--mac-package-identifier", nonValidatedMacBundleID)
                    cliArg("--mac-app-store", macAppStore)
                    cliArg("--mac-app-category", macAppCategory)
                    cliArg("--mac-entitlements", macEntitlementsFile)

                    macSigner?.settings?.let { signingSettings ->
                        cliArg("--mac-sign", true)
                        cliArg("--mac-signing-key-user-name", signingSettings.identity)
                        cliArg("--mac-signing-keychain", signingSettings.keychain)
                        cliArg("--mac-package-signing-prefix", signingSettings.prefix)
                    }
                }
            }

        private fun invalidateMappedLibs(inputChanges: InputChanges): Set<File> {
            val outdatedLibs = HashSet<File>()
            val libsDirFile = libsDir.ioFile

            fun invalidateAllLibs() {
                outdatedLibs.addAll(files.files)

                logger.debug("Clearing all files in working dir: $libsDirFile")
                fileOperations.clearDirs(libsDirFile)
            }

            if (inputChanges.isIncremental) {
                val allChanges = inputChanges.getFileChanges(files).asSequence()

                try {
                    for (change in allChanges) {
                        libsMapping.remove(change.file)?.let { files ->
                            files.forEach { fileOperations.delete(it) }
                        }
                        if (change.changeType != ChangeType.REMOVED) {
                            outdatedLibs.add(change.file)
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Could remove outdated libs incrementally: ${e.stacktraceToString()}")
                    invalidateAllLibs()
                }
            } else {
                invalidateAllLibs()
            }

            return outdatedLibs
        }

        private fun jarCopyingProcessor(): FileCopyingProcessor =
            if (currentOS == OS.MacOS) {
                val tmpDirForSign = signDir.ioFile
                fileOperations.clearDirs(tmpDirForSign)
                MacJarSignFileCopyingProcessor(macSigner!!, tmpDirForSign, jvmRuntimeInfo.majorVersion)
            } else {
                SimpleFileCopyingProcessor
            }

        override fun prepareWorkingDir(inputChanges: InputChanges) {
            val libsDir = libsDir.ioFile
            val fileProcessor = jarCopyingProcessor()

            val mangleJarFilesNames = mangleJarFilesNames.get()

            fun copyFileToLibsDir(sourceFile: File): File {
                val targetName =
                    if (mangleJarFilesNames && sourceFile.isJarFile) {
                        sourceFile.mangledName()
                    } else {
                        sourceFile.name
                    }
                val targetFile = libsDir.resolve(targetName)
                fileProcessor.copy(sourceFile, targetFile)
                return targetFile
            }

            // skiko can be bundled to the main uber jar by proguard
            fun File.isMainUberJar() = packageFromUberJar.get() && name == launcherMainJar.ioFile.name

            val outdatedLibs = invalidateMappedLibs(inputChanges)
            for (sourceFile in outdatedLibs) {
                assert(sourceFile.exists()) { "Lib file does not exist: $sourceFile" }

                libsMapping[sourceFile] =
                    if (isSkikoForCurrentOS(sourceFile) || sourceFile.isMainUberJar()) {
                        val unpackedFiles = unpackSkikoForCurrentOS(sourceFile, skikoDir.ioFile, fileOperations)
                        unpackedFiles.map { copyFileToLibsDir(it) }
                    } else {
                        listOf(copyFileToLibsDir(sourceFile))
                    }
            }

            // todo: incremental copy
            fileOperations.clearDirs(packagedResourcesDir)
            val destResourcesDir = packagedResourcesDir.ioFile
            val appResourcesDir = appResourcesDir.ioFileOrNull
            if (appResourcesDir != null) {
                for (file in appResourcesDir.walk()) {
                    val relPath = file.relativeTo(appResourcesDir).path
                    val destFile = destResourcesDir.resolve(relPath)
                    if (file.isDirectory) {
                        fileOperations.mkdirs(destFile)
                    } else {
                        file.copyTo(destFile)
                    }
                }
            }

            // When sandboxing is enabled, Skiko's native DLL is in resources/ but its companion
            // data file (icudtl.dat) is extracted by unpackSkikoForCurrentOS to libsDir.
            // Copy it next to the DLL so SkLoadICU can find it.
            if (sandboxingEnabled.get()) {
                val icudtl = libsDir.resolve("icudtl.dat")
                if (icudtl.exists()) {
                    icudtl.copyTo(destResourcesDir.resolve("icudtl.dat"), overwrite = true)
                }
            }

            fileOperations.clearDirs(jpackageResources)
            if (currentOS == OS.MacOS) {
                val systemVersion = macMinimumSystemVersion.orNull ?: "10.13"

                macLayeredIcons.ioFileOrNull?.let { layeredIcon ->
                    if (layeredIcon.exists()) {
                        try {
                            macAssetsTool.compileAssets(
                                layeredIcon,
                                workingDir.ioFile,
                                systemVersion,
                            )
                        } catch (e: Exception) {
                            logger.warn("Can not compile layered icon: ${e.message}")
                        }
                    }
                }

                InfoPlistBuilder(macExtraPlistKeysRawXml.orNull)
                    .also { setInfoPlistValues(it) }
                    .writeToFile(jpackageResources.ioFile.resolve("Info.plist"))

                if (macAppStore.orNull == true) {
                    val productDefPlistXml =
                        """
                        <key>os</key>
                        <array>
                        <string>$systemVersion</string>
                        </array>
                        """.trimIndent()
                    InfoPlistBuilder(productDefPlistXml)
                        .writeToFile(jpackageResources.ioFile.resolve("product-def.plist"))
                }
            }
        }

        override fun checkResult(result: ExecResult) {
            super.checkResult(result)
            modifyRuntimeOnMacOsIfNeeded()
            val outputFile = findOutputFileOrDir(destinationDir.ioFile, targetFormat)
            logger.lifecycle("The distribution is written to ${outputFile.canonicalPath}")
        }

        @Suppress("NestedBlockDepth")
        private fun modifyRuntimeOnMacOsIfNeeded() {
            if (currentOS != OS.MacOS || targetFormat != TargetFormat.RawAppImage) return

            val appDir = destinationDir.ioFile.resolve("${packageName.get()}.app")
            val runtimeDir = appDir.resolve("Contents/runtime")

            macAssetsTool.assetsFile(workingDir.ioFile).apply {
                if (exists()) {
                    copyTo(appDir.resolve("Contents/Resources/Assets.car"))
                }
            }

            // Add the provisioning profile
            macRuntimeProvisioningProfile.ioFileOrNull?.copyTo(
                target = runtimeDir.resolve("Contents/embedded.provisionprofile"),
                overwrite = true,
            )
            // Patch the jpackage launcher's LC_BUILD_VERSION to claim the configured
            // macOS SDK version, enabling SDK-gated AppKit features (e.g. Liquid Glass).
            macOsSdkVersion.orNull?.let { sdkVersion ->
                val minVersion = macMinimumSystemVersion.orNull ?: "10.13"
                val launcher = appDir.resolve("Contents/MacOS/${packageName.get()}")
                if (launcher.exists()) {
                    patchMachOSdkVersion(launcher, minVersion, sdkVersion)
                }
            }

            val appEntitlementsFile = macEntitlementsFile.ioFileOrNull
            val runtimeEntitlementsFile = macRuntimeEntitlementsFile.ioFileOrNull

            val macSigner = macSigner!!
            // Resign the runtime completely (and also the app dir only)
            // Sign all libs and executables in runtime
            runtimeDir.walk().forEach { file ->
                val path = file.toPath()
                if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && (path.isExecutable() || file.name.isDylibPath)) {
                    macSigner.sign(file, runtimeEntitlementsFile)
                }
            }

            if (sandboxingEnabled.get()) {
                moveNativeLibsToFrameworks(appDir, macSigner, appEntitlementsFile)
            }

            // Generate and embed launch agent plists before signing
            val launchAgentDefs = macLaunchAgents.get()
            if (launchAgentDefs.isNotEmpty()) {
                val destDir = appDir.resolve("Contents/Library/LaunchAgents")
                for (agent in launchAgentDefs) {
                    LaunchAgentPlistGenerator.generate(agent, destDir, packageName.get())
                }
            }

            macSigner.sign(runtimeDir, runtimeEntitlementsFile, forceEntitlements = true)
            macSigner.sign(appDir, appEntitlementsFile, forceEntitlements = true)

            if (iconMapping.isNotEmpty()) {
                for ((originalIcon, newIcon) in iconMapping) {
                    if (originalIcon.exists()) {
                        newIcon.ensureParentDirsCreated()
                        originalIcon.copyTo(newIcon)
                    }
                }
            }
        }

        /**
         * Moves native libraries from `Contents/app/resources/` to `Contents/Frameworks/`
         * (Apple convention for sandboxed apps) and signs them.
         */
        private fun moveNativeLibsToFrameworks(
            appDir: File,
            macSigner: MacSigner,
            entitlementsFile: File?,
        ) {
            val resourcesDir = appDir.resolve("Contents/app/resources")
            val frameworksDir = appDir.resolve("Contents/Frameworks")
            if (resourcesDir.exists()) {
                frameworksDir.mkdirs()
                resourcesDir.walk().forEach { file ->
                    if (file == resourcesDir) return@forEach
                    if (file.isDirectory) return@forEach
                    if (file.name.isDylibPath || file.name == "icudtl.dat") {
                        val target = frameworksDir.resolve(file.relativeTo(resourcesDir).path)
                        target.parentFile.mkdirs()
                        Files.move(file.toPath(), target.toPath())
                    }
                }
                // Clean up empty directories left in resources/
                resourcesDir
                    .walk()
                    .sortedDescending()
                    .filter { it.isDirectory && it != resourcesDir && it.listFiles()?.isEmpty() == true }
                    .forEach { it.delete() }
            }
            // Sign native libs in Frameworks/
            if (frameworksDir.exists()) {
                frameworksDir.walk().forEach { file ->
                    val path = file.toPath()
                    if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && file.name.isDylibPath) {
                        macSigner.sign(file, entitlementsFile)
                    }
                }
            }
        }

        private fun patchMachOSdkVersion(
            binary: File,
            minVersion: String,
            sdkVersion: String,
        ) {
            val vtool = File("/usr/bin/vtool")
            if (!vtool.exists()) {
                logger.warn(
                    "vtool not found at /usr/bin/vtool — skipping macOS SDK version patch. " +
                        "Install Xcode Command Line Tools to enable this feature.",
                )
                return
            }
            logger.lifecycle("Patching ${binary.name} LC_BUILD_VERSION: minos=$minVersion sdk=$sdkVersion")
            // Remove existing code signature (vtool cannot modify signed binaries)
            runExternalTool(
                tool = File("/usr/bin/codesign"),
                args = listOf("--remove-signature", binary.absolutePath),
                checkExitCodeIsNormal = false,
            )
            runExternalTool(
                tool = vtool,
                args =
                    listOf(
                        "-set-build-version",
                        "macos",
                        minVersion,
                        sdkVersion,
                        "-tool",
                        "ld",
                        "0.0",
                        "-replace",
                        "-output",
                        binary.absolutePath,
                        binary.absolutePath,
                    ),
            )
            // No need to re-sign here — the existing signing code runs right after
        }

        override fun initState() {
            jvmRuntimeInfo = JvmRuntimeProperties.readFromFile(javaRuntimePropertiesFile.ioFile)

            val mappingFile = libsMappingFile.ioFile
            if (mappingFile.exists()) {
                try {
                    libsMapping.loadFrom(mappingFile)
                } catch (e: Exception) {
                    fileOperations.delete(mappingFile)
                    throw e
                }
                logger.debug("Loaded libs mapping from $mappingFile")
            }
        }

        override fun saveStateAfterFinish() {
            val mappingFile = libsMappingFile.ioFile
            libsMapping.saveTo(mappingFile)
            logger.debug("Saved libs mapping to $mappingFile")
        }

        private fun setInfoPlistValues(plist: InfoPlistBuilder) {
            check(currentOS == OS.MacOS) { "Current OS is not macOS: $currentOS" }

            val systemVersion = macMinimumSystemVersion.orNull ?: "10.13"
            plist[PlistKeys.LSMinimumSystemVersion] = systemVersion
            plist[PlistKeys.CFBundleDevelopmentRegion] = "English"
            plist[PlistKeys.CFBundleAllowMixedLocalizations] = "true"
            val packageName = packageName.get()
            plist[PlistKeys.CFBundleExecutable] = packageName
            plist[PlistKeys.CFBundleIconFile] = "$packageName.icns"
            val bundleId =
                nonValidatedMacBundleID.orNull
                    ?: launcherMainClass.get().substringBeforeLast(".")
            plist[PlistKeys.CFBundleIdentifier] = bundleId
            plist[PlistKeys.CFBundleInfoDictionaryVersion] = "6.0"
            val displayName = appName.orNull ?: packageName
            plist[PlistKeys.CFBundleName] = displayName
            plist[PlistKeys.CFBundleDisplayName] = displayName
            plist[PlistKeys.CFBundlePackageType] = "APPL"
            val packageVersion = packageVersion.get()
            plist[PlistKeys.CFBundleShortVersionString] = packageVersion
            // If building for the App Store, use "utilities" as default just like jpackage.
            val category = macAppCategory.orNull ?: (if (macAppStore.orNull == true) "public.app-category.utilities" else null)
            plist[PlistKeys.LSApplicationCategoryType] = category ?: "Unknown"
            val packageBuildVersion = packageBuildVersion.orNull ?: packageVersion
            plist[PlistKeys.CFBundleVersion] = packageBuildVersion
            val year = Calendar.getInstance().get(Calendar.YEAR)
            plist[PlistKeys.NSHumanReadableCopyright] = packageCopyright.orNull
                ?: "Copyright (C) $year"
            plist[PlistKeys.NSSupportsAutomaticGraphicsSwitching] = "true"
            plist[PlistKeys.NSHighResolutionCapable] = "true"
            if (macAppStore.orNull == true) {
                plist[PlistKeys.ITSAppUsesNonExemptEncryption] = false
            }
            val fileAssociationMutableSet = fileAssociations.get()
            if (fileAssociationMutableSet.isNotEmpty()) {
                plist[PlistKeys.CFBundleDocumentTypes] =
                    fileAssociationMutableSet
                        .groupBy { it.mimeType to it.description }
                        .map { (key, extensions) ->
                            val (mimeType, description) = key
                            val iconPath = extensions.firstNotNullOfOrNull { it.iconFile }?.let { iconMapping[it]?.name }
                            InfoPlistMapValue(
                                PlistKeys.CFBundleTypeRole to InfoPlistStringValue("Editor"),
                                PlistKeys.CFBundleTypeExtensions to
                                    InfoPlistListValue(extensions.map { InfoPlistStringValue(it.extension) }),
                                PlistKeys.CFBundleTypeIconFile to InfoPlistStringValue(iconPath ?: "$packageName.icns"),
                                PlistKeys.CFBundleTypeMIMETypes to InfoPlistStringValue(mimeType),
                                PlistKeys.CFBundleTypeName to InfoPlistStringValue(description),
                                PlistKeys.CFBundleTypeOSTypes to InfoPlistListValue(InfoPlistStringValue("****")),
                            )
                        }
            }

            val protocols = urlProtocols.get()
            if (protocols.isNotEmpty()) {
                plist[PlistKeys.CFBundleURLTypes] =
                    protocols.map { protocol ->
                        InfoPlistMapValue(
                            PlistKeys.CFBundleURLName to InfoPlistStringValue(protocol.name),
                            PlistKeys.CFBundleURLSchemes to
                                InfoPlistListValue(protocol.schemes.map { InfoPlistStringValue(it) }),
                        )
                    }
            }

            if (macAssetsTool.assetsFile(workingDir.ioFile).exists()) {
                macLayeredIcons.orNull?.let { plist[PlistKeys.CFBundleIconName] = it.asFile.name.removeSuffix(".icon") }
            }
        }
    }

// Serializable is only needed to avoid breaking configuration cache:
// https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements
private class FilesMapping : Serializable {
    companion object {
        private const val serialVersionUID: Long = 0L
    }

    private var mapping = HashMap<File, List<File>>()

    operator fun get(key: File): List<File>? = mapping[key]

    operator fun set(
        key: File,
        value: List<File>,
    ) {
        mapping[key] = value
    }

    fun remove(key: File): List<File>? = mapping.remove(key)

    fun loadFrom(mappingFile: File) {
        mappingFile.readLines().forEach { line ->
            if (line.isNotBlank()) {
                val paths = line.splitToSequence(File.pathSeparatorChar)
                val lib = File(paths.first())
                val mappedFiles = paths.drop(1).mapTo(ArrayList()) { File(it) }
                mapping[lib] = mappedFiles
            }
        }
    }

    fun saveTo(mappingFile: File) {
        mappingFile.parentFile.mkdirs()
        mappingFile.bufferedWriter().use { writer ->
            mapping.entries
                .sortedBy { (k, _) -> k.absolutePath }
                .forEach { (k, values) ->
                    (sequenceOf(k) + values.asSequence())
                        .joinTo(writer, separator = File.pathSeparator, transform = { it.absolutePath })
                }
        }
    }

    private fun writeObject(stream: ObjectOutputStream) {
        stream.writeObject(mapping)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readObject(stream: ObjectInputStream) {
        mapping = stream.readObject() as HashMap<File, List<File>>
    }
}

private fun isSkikoForCurrentOS(lib: File): Boolean =
    lib.name.contains("skiko-awt-runtime-${currentOS.id}-${currentArch.id}") &&
        lib.name.endsWith(".jar")

private fun unpackSkikoForCurrentOS(
    sourceJar: File,
    skikoDir: File,
    fileOperations: FileSystemOperations,
): List<File> {
    val entriesToUnpack =
        when (currentOS) {
            OS.MacOS -> setOf("libskiko-macos-${currentArch.id}.dylib")
            OS.Windows -> setOf("skiko-windows-${currentArch.id}.dll", "icudtl.dat")
            OS.Linux -> setOf("libskiko-linux-${currentArch.id}.so")
        }

    // output files: unpacked libs, corresponding .sha256 files, and target jar
    val outputFiles = ArrayList<File>(entriesToUnpack.size * 2 + 1)
    val targetJar = skikoDir.resolve(sourceJar.name)
    outputFiles.add(targetJar)

    fileOperations.clearDirs(skikoDir)
    transformJar(sourceJar, targetJar) { entry, zin, zout ->
        // check both entry or entry.sha256, using filename part to handle subdirectory paths
        val entryFileName = entry.name.substringAfterLast("/").removeSuffix(".sha256")
        if (entryFileName in entriesToUnpack) {
            val unpackedFile = skikoDir.resolve(entry.name.substringAfterLast("/"))
            zin.copyTo(unpackedFile)
            outputFiles.add(unpackedFile)
        } else {
            copyZipEntry(entry, zin, zout)
        }
    }
    return outputFiles
}

/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.tasks

import dev.nucleusframework.nucleus.NucleusBuildConfig
import dev.nucleusframework.nucleus.internal.utils.clearDirs
import dev.nucleusframework.nucleus.internal.utils.ioFile
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private const val DEFAULT_COMPOSE_PROGUARD_RULES_FILE_NAME = "default-compose-desktop-rules.pro"
private const val DEFAULT_ENTITLEMENTS_FILE_NAME = "default-entitlements.plist"
private const val DEFAULT_SANDBOX_ENTITLEMENTS_FILE_NAME = "default-sandbox-entitlements.plist"
private const val DEFAULT_SANDBOX_RUNTIME_ENTITLEMENTS_FILE_NAME = "default-sandbox-runtime-entitlements.plist"

@DisableCachingByDefault(because = "Unpacks bundled resources; fast and not worth caching")
abstract class AbstractUnpackDefaultApplicationResourcesTask : AbstractNucleusTask() {
    internal class DefaultResourcesProvider(
        resourcesRootDir: Provider<Directory>,
    ) {
        val macIcon: Provider<RegularFile> = resourcesRootDir.map { it.file("default-icon-mac.icns") }
        val windowsIcon: Provider<RegularFile> = resourcesRootDir.map { it.file("default-icon-windows.ico") }
        val linuxIcon: Provider<RegularFile> = resourcesRootDir.map { it.file("default-icon-linux.png") }
        val defaultComposeProguardRules: Provider<RegularFile> =
            resourcesRootDir.map { it.file(DEFAULT_COMPOSE_PROGUARD_RULES_FILE_NAME) }
        val defaultEntitlements: Provider<RegularFile> =
            resourcesRootDir.map { it.file(DEFAULT_ENTITLEMENTS_FILE_NAME) }
        val defaultSandboxEntitlements: Provider<RegularFile> =
            resourcesRootDir.map { it.file(DEFAULT_SANDBOX_ENTITLEMENTS_FILE_NAME) }
        val defaultSandboxRuntimeEntitlements: Provider<RegularFile> =
            resourcesRootDir.map { it.file(DEFAULT_SANDBOX_RUNTIME_ENTITLEMENTS_FILE_NAME) }
    }

    @OutputDirectory
    val destinationDir: Provider<Directory> =
        project.layout.buildDirectory.dir(
            "compose/default-resources/${NucleusBuildConfig.composeGradlePluginVersion}",
        )

    @get:Internal
    internal val resources = DefaultResourcesProvider(destinationDir)

    @TaskAction
    fun run() {
        fileOperations.clearDirs(destinationDir)

        unpack(iconSourcePath("mac", "icns"), resources.macIcon)
        unpack(iconSourcePath("windows", "ico"), resources.windowsIcon)
        unpack(iconSourcePath("linux", "png"), resources.linuxIcon)
        unpack(DEFAULT_COMPOSE_PROGUARD_RULES_FILE_NAME, resources.defaultComposeProguardRules)
        unpack(DEFAULT_ENTITLEMENTS_FILE_NAME, resources.defaultEntitlements)
        unpack(DEFAULT_SANDBOX_ENTITLEMENTS_FILE_NAME, resources.defaultSandboxEntitlements)
        unpack(DEFAULT_SANDBOX_RUNTIME_ENTITLEMENTS_FILE_NAME, resources.defaultSandboxRuntimeEntitlements)
    }

    private fun iconSourcePath(
        platformName: String,
        iconExt: String,
    ): String = "default-compose-desktop-icon-$platformName.$iconExt"

    private fun unpack(
        from: String,
        to: Provider<out FileSystemLocation>,
    ) {
        val targetIoFile =
            to.ioFile.apply {
                if (exists()) {
                    delete()
                } else {
                    parentFile.mkdirs()
                }
                createNewFile()
            }

        val iconResourceStream =
            javaClass.classLoader.getResourceAsStream(from)
                ?: error("Could not find default resource: $from")
        iconResourceStream.use { input ->
            targetIoFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
    }
}

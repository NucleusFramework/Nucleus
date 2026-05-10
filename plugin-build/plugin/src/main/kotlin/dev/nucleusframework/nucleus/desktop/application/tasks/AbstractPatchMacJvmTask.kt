/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Produces a vtool-patched copy of the source JDK's `java` binary with
 * `LC_BUILD_VERSION` set to the given SDK version, enabling Liquid Glass on
 * macOS without requiring a JDK built with Xcode 26. Runs at execution time
 * (not configuration time) so the build is compatible with Gradle's
 * configuration cache.
 */
@DisableCachingByDefault(because = "Output is platform- and JDK-binary-specific; local cache is cheaper than remote")
abstract class AbstractPatchMacJvmTask : AbstractNucleusTask() {
    @get:Input
    abstract val sourceJavaHome: Property<String>

    @get:Input
    abstract val minimumSystemVersion: Property<String>

    @get:Input
    abstract val sdkVersion: Property<String>

    @get:OutputDirectory
    abstract val outputJavaHome: DirectoryProperty

    @get:Internal
    val patchedJavaBinary: Provider<RegularFile>
        get() = outputJavaHome.file("bin/java")

    @TaskAction
    fun patch() {
        val sourceHome = File(sourceJavaHome.get())
        val sourceBin = File(sourceHome, "bin/java")
        if (!sourceBin.exists()) {
            logger.warn("Source java binary not found at ${sourceBin.absolutePath} — skipping patch.")
            return
        }
        val vtool = File("/usr/bin/vtool")
        if (!vtool.exists()) {
            logger.warn(
                "vtool not found at /usr/bin/vtool — skipping macOS SDK version patch. " +
                    "Install Xcode Command Line Tools to enable Liquid Glass.",
            )
            return
        }

        val outHome = outputJavaHome.get().asFile
        outHome.mkdirs()
        val binDir = File(outHome, "bin").apply { mkdirs() }
        val patchedBin = File(binDir, "java")

        // Mirror JAVA_HOME/lib via symlink so @loader_path/../lib resolves
        val libLink = File(outHome, "lib")
        if (Files.isSymbolicLink(libLink.toPath()) || libLink.exists()) libLink.delete()
        Files.createSymbolicLink(libLink.toPath(), File(sourceHome, "lib").toPath())

        Files.copy(sourceBin.toPath(), patchedBin.toPath(), StandardCopyOption.REPLACE_EXISTING)
        patchedBin.setExecutable(true)

        logger.lifecycle(
            "Patching JVM binary: minos=${minimumSystemVersion.get()} sdk=${sdkVersion.get()} (Liquid Glass)...",
        )

        execOperations.exec {
            it.commandLine("/usr/bin/codesign", "--remove-signature", patchedBin.absolutePath)
            it.isIgnoreExitValue = true
        }
        val vtoolResult = execOperations.exec {
            it.commandLine(
                "/usr/bin/vtool",
                "-set-build-version",
                "macos",
                minimumSystemVersion.get(),
                sdkVersion.get(),
                "-tool",
                "ld",
                "0.0",
                "-replace",
                "-output",
                patchedBin.absolutePath,
                patchedBin.absolutePath,
            )
            it.isIgnoreExitValue = true
        }
        if (vtoolResult.exitValue != 0) {
            logger.warn("vtool exited with code ${vtoolResult.exitValue} — Liquid Glass patch may have failed")
            return
        }
        execOperations.exec {
            it.commandLine("/usr/bin/codesign", "-s", "-", "-f", patchedBin.absolutePath)
            it.isIgnoreExitValue = true
        }

        logger.lifecycle("Patched binary cached at ${patchedBin.absolutePath}")
    }
}

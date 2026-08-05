/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File

/**
 * [JavaLauncher] for a JDK that Gradle's toolchain machinery does not manage —
 * a vtool-patched copy of the app JDK, or a GraalVM provisioned by Nucleus
 * itself. Metadata is derived from the `release` file of [metadataJavaHome]
 * (the installation itself, unless [javaBinary] lives in a partial copy).
 *
 * [org.gradle.api.tasks.JavaExec] rejects an `executable` that does not resolve
 * to the same file as its `javaLauncher`, so a foreign JDK has to be handed over
 * as a launcher rather than as a raw path. Using a real [JavaLauncher] — instead
 * of forking via [ProcessBuilder] — also keeps `JavaExec` in charge of the
 * process, so IntelliJ's Gradle debugger can inject JDWP and manage the
 * lifecycle (breakpoints, stop button).
 */
internal class ExternalJavaLauncher(
    private val javaBinary: File,
    private val javaHome: File,
    private val objects: ObjectFactory,
    private val metadataJavaHome: File = javaHome,
) : JavaLauncher {
    private val lazyMetadata by lazy { buildMetadata() }

    override fun getMetadata(): JavaInstallationMetadata = lazyMetadata

    override fun getExecutablePath(): RegularFile =
        objects.fileProperty().also { it.set(javaBinary) }.get()

    private fun buildMetadata(): JavaInstallationMetadata {
        val releaseProps = readReleaseFile(metadataJavaHome)
        val rawJavaVersion = releaseProps["JAVA_VERSION"] ?: "0"
        val languageMajor = rawJavaVersion.substringBefore('.').toIntOrNull() ?: 0
        val runtimeVersion = releaseProps["JAVA_RUNTIME_VERSION"] ?: rawJavaVersion
        val jvmVersion = releaseProps["JAVA_VERSION"] ?: rawJavaVersion
        val vendor = releaseProps["IMPLEMENTOR"] ?: "Unknown"
        val installation: Directory = objects.directoryProperty().also { it.set(javaHome) }.get()
        return object : JavaInstallationMetadata {
            override fun getLanguageVersion(): JavaLanguageVersion = JavaLanguageVersion.of(languageMajor.coerceAtLeast(1))
            override fun getJavaRuntimeVersion(): String = runtimeVersion
            override fun getJvmVersion(): String = jvmVersion
            override fun getVendor(): String = vendor
            override fun getInstallationPath(): Directory = installation
            override fun isCurrentJvm(): Boolean = false
        }
    }

    private fun readReleaseFile(javaHome: File): Map<String, String> {
        val release = File(javaHome, "release")
        if (!release.exists()) return emptyMap()
        return release.readLines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1).trim('"')
            }
            .toMap()
    }
}

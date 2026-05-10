/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import java.io.File

/**
 * [JavaLauncher] pointing to a vtool-patched copy of the source JDK's `java`
 * binary. Metadata is derived from the source JDK's `release` file so Gradle's
 * toolchain validation accepts the launcher alongside `executable`. Using a
 * real [JavaLauncher] — instead of launching the patched binary via
 * [ProcessBuilder] — keeps Gradle's [org.gradle.api.tasks.JavaExec] in charge
 * of the fork, so IntelliJ's Gradle debugger can inject JDWP and manage the
 * process lifecycle (breakpoints, stop button).
 */
internal class PatchedJavaLauncher(
    private val patchedJavaBinary: File,
    private val patchedJavaHome: File,
    private val sourceJavaHome: File,
    private val objects: ObjectFactory,
) : JavaLauncher {
    private val lazyMetadata by lazy { buildMetadata() }

    override fun getMetadata(): JavaInstallationMetadata = lazyMetadata

    override fun getExecutablePath(): RegularFile =
        objects.fileProperty().also { it.set(patchedJavaBinary) }.get()

    private fun buildMetadata(): JavaInstallationMetadata {
        val releaseProps = readReleaseFile(sourceJavaHome)
        val rawJavaVersion = releaseProps["JAVA_VERSION"] ?: "0"
        val languageMajor = rawJavaVersion.substringBefore('.').toIntOrNull() ?: 0
        val runtimeVersion = releaseProps["JAVA_RUNTIME_VERSION"] ?: rawJavaVersion
        val jvmVersion = releaseProps["JAVA_VERSION"] ?: rawJavaVersion
        val vendor = releaseProps["IMPLEMENTOR"] ?: "Unknown"
        val installation: Directory = objects.directoryProperty().also { it.set(patchedJavaHome) }.get()
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

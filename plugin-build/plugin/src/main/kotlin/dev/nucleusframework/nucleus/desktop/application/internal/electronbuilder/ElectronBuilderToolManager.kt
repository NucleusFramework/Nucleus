/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Parameters for invoking electron-builder.
 */
internal data class ElectronBuilderInvocation(
    val configFile: File,
    val prepackagedDir: File,
    val outputDir: File,
    val targets: List<String>,
    val extraConfigArgs: List<String> = emptyList(),
    val npx: File,
    val environment: Map<String, String> = emptyMap(),
    val publishFlag: String = "never",
)

/**
 * Manages electron-builder installation and invocation.
 *
 * electron-builder is invoked via npx to avoid global installation requirements.
 * It uses the `--prepackaged` flag to package a pre-built app directory (from jpackage).
 */
internal class ElectronBuilderToolManager(
    private val execOperations: ExecOperations,
    private val logger: Logger,
) {
    companion object {
        private const val ELECTRON_BUILDER_PACKAGE = "electron-builder"
        private const val PREPACKAGED_ELECTRON_VERSION = "33.0.0"
    }

    /**
     * Invokes electron-builder with the given invocation parameters.
     *
     * @param invocation The parameters for the electron-builder invocation.
     */
    fun invoke(invocation: ElectronBuilderInvocation) {
        require(invocation.configFile.exists()) {
            "electron-builder config not found: ${invocation.configFile.absolutePath}"
        }
        require(invocation.prepackagedDir.exists()) {
            "Prepackaged app directory not found: ${invocation.prepackagedDir.absolutePath}"
        }

        invocation.outputDir.mkdirs()

        val args =
            buildList {
                add("--yes")
                add(ELECTRON_BUILDER_PACKAGE)
                add("--prepackaged")
                add(invocation.prepackagedDir.absolutePath)
                add("--config")
                add(invocation.configFile.absolutePath)
                add("--config.electronVersion=$PREPACKAGED_ELECTRON_VERSION")
                addAll(invocation.extraConfigArgs)
                add("--publish")
                add(invocation.publishFlag)
                addAll(invocation.targets)
                add("--project")
                add(invocation.outputDir.absolutePath)
            }

        logger.info("Running electron-builder: ${invocation.npx.absolutePath} ${args.joinToString(" ")}")

        invokeWithRetry(invocation, args, maxAttempts = 3)
    }

    /**
     * Executes electron-builder, retrying on npm ECOMPROMISED errors.
     * npm 11+ on Windows ARM64 intermittently fails with "Lock compromised"
     * due to internal cache integrity race conditions. Cleaning the npm cache
     * and retrying resolves the issue.
     */
    private fun invokeWithRetry(
        invocation: ElectronBuilderInvocation,
        args: List<String>,
        maxAttempts: Int,
    ) {
        for (attempt in 1..maxAttempts) {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val result =
                execOperations.exec { spec ->
                    spec.executable = invocation.npx.absolutePath
                    spec.args = args
                    spec.environment(invocation.environment)
                    spec.workingDir = invocation.outputDir
                    spec.isIgnoreExitValue = true
                    spec.standardOutput = stdout
                    spec.errorOutput = stderr
                }

            val stdoutStr = stdout.toString()
            val stderrStr = stderr.toString()

            if (stdoutStr.isNotBlank()) {
                logger.info(stdoutStr)
            }

            if (result.exitValue == 0) return

            val isCompromised = stderrStr.contains("ECOMPROMISED")
            if (isCompromised && attempt < maxAttempts) {
                logger.lifecycle(
                    "npm ECOMPROMISED error on attempt $attempt/$maxAttempts, " +
                        "cleaning npm cache and retrying...",
                )
                cleanNpmCache(invocation)
                continue
            }

            val errMsg =
                buildString {
                    appendLine("electron-builder failed with exit code ${result.exitValue}")
                    appendLine("Command: ${invocation.npx.absolutePath} ${args.joinToString(" ")}")
                    if (stderrStr.isNotBlank()) {
                        appendLine("Stderr:")
                        appendLine(stderrStr)
                    }
                    if (stdoutStr.isNotBlank()) {
                        appendLine("Stdout:")
                        appendLine(stdoutStr)
                    }
                }
            error(errMsg)
        }
    }

    private fun cleanNpmCache(invocation: ElectronBuilderInvocation) {
        val cacheDir = invocation.environment["NPM_CONFIG_CACHE"] ?: return
        val dir = File(cacheDir)
        if (dir.isDirectory) {
            dir.deleteRecursively()
            dir.mkdirs()
        }
    }

    /**
     * Checks if electron-builder is available via npx.
     */
    fun isAvailable(npx: File): Boolean =
        try {
            val result =
                execOperations.exec { spec ->
                    spec.executable = npx.absolutePath
                    spec.args = listOf("--yes", ELECTRON_BUILDER_PACKAGE, "--version")
                    spec.isIgnoreExitValue = true
                    spec.standardOutput = ByteArrayOutputStream()
                    spec.errorOutput = ByteArrayOutputStream()
                }
            result.exitValue == 0
        } catch (e: IOException) {
            logger.warn("Failed to check electron-builder availability: ${e.message}")
            false
        }
}

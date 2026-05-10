/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal.electronbuilder

import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentOS
import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException

internal object NodeJsDetector {
    private const val MIN_NODE_MAJOR_VERSION = 18

    /**
     * Locates the Node.js executable on the system.
     *
     * @param customNodePath Optional override path provided by the user via Gradle property.
     * @param logger Gradle logger for warnings/info.
     * @return The File pointing to the node executable, or null if not found.
     */
    fun detectNode(
        customNodePath: String? = null,
        logger: Logger? = null,
    ): File? {
        if (customNodePath != null) {
            val custom = File(customNodePath)
            if (custom.exists() && custom.canExecute()) {
                logger?.info("Using custom Node.js path: ${custom.absolutePath}")
                return custom
            }
            logger?.warn("Custom Node.js path not found or not executable: $customNodePath")
        }

        val nodeName = if (currentOS == OS.Windows) "node.exe" else "node"
        return findInPath(nodeName, logger)
    }

    /**
     * Locates npx on the system.
     *
     * @param customNodePath Optional override directory containing npx.
     * @param logger Gradle logger.
     * @return The File pointing to the npx executable, or null if not found.
     */
    fun detectNpx(
        customNodePath: String? = null,
        logger: Logger? = null,
    ): File? {
        if (customNodePath != null) {
            val npxName = if (currentOS == OS.Windows) "npx.cmd" else "npx"
            val dir = File(customNodePath)
            val parent = if (dir.isFile) dir.parentFile else dir
            val npx = parent.resolve(npxName)
            if (npx.exists() && npx.canExecute()) {
                return npx
            }
        }

        val npxName = if (currentOS == OS.Windows) "npx.cmd" else "npx"
        return findInPath(npxName, logger)
    }

    /**
     * Checks the installed Node.js version.
     *
     * @return The version string (e.g., "v18.17.0") or null if node is not found.
     */
    fun getNodeVersion(node: File): String? =
        try {
            val process =
                ProcessBuilder(node.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
            val version =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()
            if (process.exitValue() == 0) version else null
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            null
        }

    /**
     * Validates that Node.js version is 18+.
     */
    fun isNodeVersionSupported(versionString: String): Boolean {
        val version = versionString.removePrefix("v")
        val major = version.split(".").firstOrNull()?.toIntOrNull() ?: return false
        return major >= MIN_NODE_MAJOR_VERSION
    }

    private fun findInPath(
        executableName: String,
        logger: Logger? = null,
    ): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        val separator = if (currentOS == OS.Windows) ";" else ":"
        val result =
            pathEnv.split(separator).firstNotNullOfOrNull { dir ->
                File(dir, executableName).takeIf { it.exists() && it.canExecute() }
            }
        if (result != null) {
            logger?.info("Found $executableName at: ${result.absolutePath}")
        } else {
            logger?.warn("$executableName not found in PATH")
        }
        return result
    }
}

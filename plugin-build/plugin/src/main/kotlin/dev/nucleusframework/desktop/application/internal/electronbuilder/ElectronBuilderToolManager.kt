/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal.electronbuilder

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Parameters for invoking electron-builder.
 */
internal data class ElectronBuilderInvocation(
    val configFile: File,
    val prepackagedDir: File,
    val outputDir: File,
    val targets: List<String>,
    val extraConfigArgs: List<String> = emptyList(),
    val node: File,
    val npm: File,
    val toolDir: File,
    val environment: Map<String, String> = emptyMap(),
    val publishFlag: String = "never",
)

/**
 * Provisions and invokes the pinned electron-builder toolchain.
 *
 * The toolchain is installed from a lock file that ships inside this plugin
 * (`src/main/resources/nucleus/electron-builder/package{,-lock}.json`) with
 * `npm ci --ignore-scripts`, into a build-local directory. electron-builder is then run directly
 * through `node <toolDir>/node_modules/electron-builder/cli.js`, with `--prepackaged` pointing at
 * the app image jpackage produced.
 *
 * This replaces `npx --yes electron-builder@<version>`. `npx` pinned only the top-level version:
 * the ~275 transitive packages were resolved fresh from the registry on every build, with no lock
 * state to check them against and with install scripts enabled — on the same machine that holds the
 * code-signing certificates and notarization credentials. `npm ci` fails unless every tarball
 * matches the `integrity` hash recorded in the committed lock file, and `--ignore-scripts` keeps
 * `preinstall`/`postinstall` hooks in that tree from executing at all.
 *
 * Not covered by the lock file: electron-builder downloads a few helper binaries at run time
 * (`app-builder`, 7-Zip, NSIS, the snap/AppImage templates) from GitHub into `ELECTRON_BUILDER_CACHE`,
 * verified by its own checksums rather than by npm's. Regenerate the lock file with
 * `scripts/update-electron-builder-lock.sh`.
 */
internal class ElectronBuilderToolManager(
    private val execOperations: ExecOperations,
    private val logger: Logger,
) {
    internal companion object {
        /**
         * Pinned electron-builder version. It must match the `electron-builder` entry of the
         * embedded `package.json` / `package-lock.json` (asserted by
         * `ElectronBuilderToolchainLockTest`).
         *
         * Pinned so the packaged output (and the generated AppImage AppRun) is reproducible across
         * builds: left unpinned, the same plugin + sources produce different artifacts on different
         * days. See #266.
         */
        internal const val ELECTRON_BUILDER_VERSION = "26.15.5"

        /** Classpath directory holding the pinned toolchain manifest and its lock file. */
        internal const val TOOLCHAIN_RESOURCE_DIR = "/nucleus/electron-builder"

        internal const val PACKAGE_JSON = "package.json"
        internal const val PACKAGE_LOCK_JSON = "package-lock.json"

        /** electron-builder's declared `bin` entry, relative to the provisioned tool directory. */
        private const val CLI_RELATIVE_PATH = "node_modules/electron-builder/cli.js"

        private const val PREPACKAGED_ELECTRON_VERSION = "33.0.0"

        private const val MAX_INSTALL_ATTEMPTS = 3

        /** Reads one of the embedded toolchain files. Absence is a packaging bug, not user error. */
        internal fun readToolchainResource(name: String): String {
            val path = "$TOOLCHAIN_RESOURCE_DIR/$name"
            val stream =
                ElectronBuilderToolManager::class.java.getResourceAsStream(path)
                    ?: error("Embedded electron-builder toolchain file is missing from the plugin JAR: $path")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    /**
     * Invokes electron-builder with the given invocation parameters, provisioning the pinned
     * toolchain first when it is not already installed in [ElectronBuilderInvocation.toolDir].
     */
    fun invoke(invocation: ElectronBuilderInvocation) {
        require(invocation.configFile.exists()) {
            "electron-builder config not found: ${invocation.configFile.absolutePath}"
        }
        require(invocation.prepackagedDir.exists()) {
            "Prepackaged app directory not found: ${invocation.prepackagedDir.absolutePath}"
        }

        invocation.outputDir.mkdirs()

        val cli = provisionCli(invocation)

        val args =
            buildList {
                add(cli.absolutePath)
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

        logger.info("Running electron-builder: ${invocation.node.absolutePath} ${args.joinToString(" ")}")

        val result =
            run(
                executable = invocation.node,
                args = args,
                workingDir = invocation.outputDir,
                environment = invocation.environment,
            )
        if (result.exitValue != 0) {
            error(
                failureMessage(
                    what = "electron-builder",
                    exitValue = result.exitValue,
                    command = "${invocation.node.absolutePath} ${args.joinToString(" ")}",
                    result = result,
                ),
            )
        }
    }

    /**
     * Installs the pinned toolchain into [ElectronBuilderInvocation.toolDir] if needed and returns
     * electron-builder's CLI entry point.
     *
     * The install is skipped when the CLI is already present and the staged manifest still matches
     * the embedded one — so parallel format tasks that share a tool directory pay for it once.
     */
    private fun provisionCli(invocation: ElectronBuilderInvocation): File {
        val toolDir = invocation.toolDir
        val cli = File(toolDir, CLI_RELATIVE_PATH)

        val manifestChanged = stageToolchainManifest(toolDir)
        if (cli.isFile && !manifestChanged) {
            logger.info("Reusing provisioned electron-builder toolchain at ${toolDir.absolutePath}")
            return cli
        }

        logger.lifecycle(
            "Provisioning electron-builder $ELECTRON_BUILDER_VERSION from the pinned lock file " +
                "(npm ci --ignore-scripts) into ${toolDir.absolutePath}",
        )
        installToolchain(invocation)

        if (!cli.isFile) {
            error(
                "electron-builder was installed but its CLI is missing at ${cli.absolutePath}. " +
                    "The embedded package-lock.json may no longer match electron-builder " +
                    "$ELECTRON_BUILDER_VERSION — regenerate it with " +
                    "scripts/update-electron-builder-lock.sh.",
            )
        }
        return cli
    }

    /**
     * Writes the embedded `package.json` / `package-lock.json` into [toolDir].
     *
     * @return true when either file changed, meaning `npm ci` must run again.
     */
    private fun stageToolchainManifest(toolDir: File): Boolean {
        toolDir.mkdirs()
        var changed = false
        for (name in listOf(PACKAGE_JSON, PACKAGE_LOCK_JSON)) {
            val target = File(toolDir, name)
            val embedded = readToolchainResource(name)
            if (!target.isFile || target.readText() != embedded) {
                target.writeText(embedded)
                changed = true
            }
        }
        return changed
    }

    /**
     * Runs `npm ci` in the tool directory, retrying on npm ECOMPROMISED errors.
     *
     * npm 11+ on Windows ARM64 intermittently fails with "Lock compromised" due to internal cache
     * integrity race conditions. Cleaning the npm cache and retrying resolves the issue.
     */
    private fun installToolchain(invocation: ElectronBuilderInvocation) {
        val args =
            listOf(
                "ci",
                // Nothing in the electron-builder tree needs an install hook, and this is the
                // machine that holds the signing certificates. Keep them from running.
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                "--no-progress",
                "--loglevel=error",
            )

        for (attempt in 1..MAX_INSTALL_ATTEMPTS) {
            val result =
                run(
                    executable = invocation.npm,
                    args = args,
                    workingDir = invocation.toolDir,
                    environment = invocation.environment,
                )
            if (result.exitValue == 0) return

            if (result.stderr.contains("ECOMPROMISED") && attempt < MAX_INSTALL_ATTEMPTS) {
                logger.lifecycle(
                    "npm ECOMPROMISED error on attempt $attempt/$MAX_INSTALL_ATTEMPTS, " +
                        "cleaning npm cache and retrying...",
                )
                cleanNpmCache(invocation)
                continue
            }

            error(
                failureMessage(
                    what =
                        "npm ci for the pinned electron-builder toolchain " +
                            "(a mismatch against package-lock.json integrity hashes also fails here)",
                    exitValue = result.exitValue,
                    command = "${invocation.npm.absolutePath} ${args.joinToString(" ")}",
                    result = result,
                ),
            )
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

    private class ProcessResult(
        val exitValue: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        executable: File,
        args: List<String>,
        workingDir: File,
        environment: Map<String, String>,
    ): ProcessResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result =
            execOperations.exec { spec ->
                spec.executable = executable.absolutePath
                spec.args = args
                spec.environment(environment)
                spec.workingDir = workingDir
                spec.isIgnoreExitValue = true
                spec.standardOutput = stdout
                spec.errorOutput = stderr
            }
        val out = stdout.toString()
        if (out.isNotBlank()) {
            logger.info(out)
        }
        return ProcessResult(result.exitValue, out, stderr.toString())
    }

    private fun failureMessage(
        what: String,
        exitValue: Int,
        command: String,
        result: ProcessResult,
    ): String =
        buildString {
            appendLine("$what failed with exit code $exitValue")
            appendLine("Command: $command")
            if (result.stderr.isNotBlank()) {
                appendLine("Stderr:")
                appendLine(result.stderr)
            }
            if (result.stdout.isNotBlank()) {
                appendLine("Stdout:")
                appendLine(result.stdout)
            }
        }
}

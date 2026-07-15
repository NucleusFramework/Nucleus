/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.MacOSNotarizationSettings
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.internal.NOTARIZATION_REQUEST_INFO_FILE_NAME
import dev.nucleusframework.desktop.application.internal.NotarizationRequestInfo
import dev.nucleusframework.desktop.application.internal.UpdateYmlChecksums
import dev.nucleusframework.desktop.application.internal.files.checkExistingFile
import dev.nucleusframework.desktop.application.internal.files.findOutputFileOrDir
import dev.nucleusframework.desktop.application.internal.validation.ValidatedMacOSNotarizationSettings
import dev.nucleusframework.desktop.application.internal.validation.toNotaryToolArgs
import dev.nucleusframework.desktop.application.internal.validation.validate
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.MacUtils
import dev.nucleusframework.internal.utils.ioFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Depends on external Apple notarization service")
abstract class AbstractNotarizationTask
    @Inject
    constructor(
        @get:Input
        val targetFormat: TargetFormat,
    ) : AbstractNucleusTask() {
        @get:Nested
        @get:Optional
        internal var nonValidatedNotarizationSettings: MacOSNotarizationSettings? = null

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val inputDir: DirectoryProperty = objects.directoryProperty()

        init {
            check(targetFormat != TargetFormat.RawAppImage) { "${TargetFormat.RawAppImage} cannot be notarized!" }
        }

        @TaskAction
        fun run() {
            val notarization = nonValidatedNotarizationSettings.validate()
            val packageFile = findOutputFileOrDir(inputDir.ioFile, targetFormat).checkExistingFile()

            notarize(notarization, packageFile)
            staple(packageFile)
            updateMetadataFiles(packageFile)
        }

        private fun notarize(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
        ) {
            logger.lifecycle("Uploading '${packageFile.name}' for notarization")
            val (authArgs, stdin) = notarization.auth.toNotaryToolArgs()
            val args =
                buildList {
                    add("notarytool")
                    add("submit")
                    add("--wait")
                    addAll(authArgs)
                    add(packageFile.absolutePath)
                }

            var submissionId: String? = null
            var stdout = ""

            val result =
                runExternalTool(
                    tool = MacUtils.xcrun,
                    args = args,
                    stdinStr = stdin,
                    checkExitCodeIsNormal = false,
                    processStdout = { output ->
                        stdout = output
                        submissionId = SUBMISSION_ID_REGEX.find(output)?.groupValues?.get(1)
                    },
                )

            if (submissionId != null) {
                logger.lifecycle("Notarization submission ID: $submissionId (file: ${packageFile.name})")
                saveNotarizationRequestInfo(submissionId!!)
            }

            if (result.exitValue != 0 || stdout.contains("status: Invalid")) {
                val appleLog = fetchNotarizationLog(notarization, submissionId)
                val errMsg =
                    buildString {
                        appendLine("Notarization failed for '${packageFile.name}'")
                        if (submissionId != null) {
                            appendLine("Submission ID: $submissionId")
                        }
                        appendLine("Exit code: ${result.exitValue}")
                        if (appleLog != null) {
                            appendLine("Apple notarization log:")
                            appendLine(appleLog)
                        } else if (submissionId != null) {
                            appendLine("To fetch the log manually run:")
                            appendLine("  xcrun notarytool log $submissionId ${authArgs.joinToString(" ")}")
                        }
                    }
                error(errMsg)
            }
        }

        private fun saveNotarizationRequestInfo(submissionId: String) {
            val info = NotarizationRequestInfo(uuid = submissionId)
            val propsFile = temporaryDir.resolve(NOTARIZATION_REQUEST_INFO_FILE_NAME)
            info.saveTo(propsFile)
            logger.info("Saved notarization request info to ${propsFile.absolutePath}")
        }

        /**
         * Attempts to fetch the notarization log from Apple.
         * Returns the log content on success, or null if it cannot be retrieved.
         */
        private fun fetchNotarizationLog(
            notarization: ValidatedMacOSNotarizationSettings,
            submissionId: String?,
        ): String? {
            if (submissionId == null) return null

            val (authArgs, stdin) = notarization.auth.toNotaryToolArgs()
            return try {
                var logContent = ""
                runExternalTool(
                    tool = MacUtils.xcrun,
                    args =
                        buildList {
                            add("notarytool")
                            add("log")
                            add(submissionId)
                            addAll(authArgs)
                        },
                    stdinStr = stdin,
                    processStdout = { logContent = it },
                )
                logContent.ifEmpty { null }
            } catch (e: IllegalStateException) {
                logger.warn("Could not fetch notarization log: ${e.message}")
                null
            }
        }

        private fun staple(packageFile: File) {
            if (packageFile.extension.equals("zip", ignoreCase = true)) {
                // ZIP files used for auto-update are not stapled: re-zipping after stapling
                // would invalidate the blockmap and break differential updates.
                // Notarization is still verified online by Gatekeeper without stapling.
                logger.lifecycle("Skipping staple for ${packageFile.name} (ZIP auto-update artifact)")
                return
            }
            runExternalTool(
                tool = MacUtils.xcrun,
                args = listOf("stapler", "staple", packageFile.absolutePath),
            )
        }

        private fun updateMetadataFiles(packageFile: File) {
            val dir = packageFile.parentFile ?: return
            val fileName = packageFile.name
            val newSize = packageFile.length()
            val newHash = UpdateYmlChecksums.sha512Base64(packageFile)

            val ymlFiles = dir.listFiles { f -> f.extension == "yml" || f.extension == "yaml" } ?: return
            for (ymlFile in ymlFiles) {
                val content = ymlFile.readText()
                if (!content.contains(fileName)) continue

                val updated = UpdateYmlChecksums.updateYamlEntry(content, fileName, newHash, newSize)
                if (updated != content) {
                    ymlFile.writeText(updated)
                    logger.lifecycle("Updated checksums in ${ymlFile.name} for $fileName")
                }
            }
        }

        companion object {
            private val SUBMISSION_ID_REGEX = Regex("""^\s*id:\s*([0-9a-fA-F-]+)\s*$""", RegexOption.MULTILINE)
        }
    }

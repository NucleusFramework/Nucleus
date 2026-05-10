/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSNotarizationSettings
import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import dev.nucleusframework.nucleus.desktop.application.internal.NOTARIZATION_REQUEST_INFO_FILE_NAME
import dev.nucleusframework.nucleus.desktop.application.internal.NotarizationRequestInfo
import dev.nucleusframework.nucleus.desktop.application.internal.files.checkExistingFile
import dev.nucleusframework.nucleus.desktop.application.internal.files.findOutputFileOrDir
import dev.nucleusframework.nucleus.desktop.application.internal.validation.ValidatedMacOSNotarizationSettings
import dev.nucleusframework.nucleus.desktop.application.internal.validation.toNotaryToolArgs
import dev.nucleusframework.nucleus.desktop.application.internal.validation.validate
import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.nucleus.internal.utils.MacUtils
import dev.nucleusframework.nucleus.internal.utils.ioFile
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
import java.security.MessageDigest
import java.util.Base64
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

            submissionId?.let { id ->
                logger.lifecycle("Notarization submission ID: $id (file: ${packageFile.name})")
                saveNotarizationRequestInfo(id)
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
            val newHash = sha512Base64(packageFile)

            val ymlFiles = dir.listFiles { f -> f.extension == "yml" || f.extension == "yaml" } ?: return
            for (ymlFile in ymlFiles) {
                val content = ymlFile.readText()
                if (!content.contains(fileName)) continue

                val updated = updateYamlEntry(content, fileName, newHash, newSize)
                if (updated != content) {
                    ymlFile.writeText(updated)
                    logger.lifecycle("Updated checksums in ${ymlFile.name} for $fileName")
                }
            }
        }

        private fun sha512Base64(file: File): String {
            val digest = MessageDigest.getInstance("SHA-512")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest())
        }

        companion object {
            private const val DEFAULT_BUFFER_SIZE = 8192
            private val SUBMISSION_ID_REGEX = Regex("""^\s*id:\s*([0-9a-fA-F-]+)\s*$""", RegexOption.MULTILINE)

            internal fun updateYamlEntry(
                yaml: String,
                fileName: String,
                newHash: String,
                newSize: Long,
            ): String {
                val lines = yaml.lines().toMutableList()
                var i = 0
                var topLevelPath: String? = null

                while (i < lines.size) {
                    val line = lines[i]
                    val trimmed = line.trimStart()

                    if (isUrlEntry(trimmed) && extractUrl(trimmed) == fileName) {
                        i = updateFileEntryFields(lines, i + 1, newHash, newSize)
                        continue
                    }

                    val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")
                    if (isTopLevel && trimmed.startsWith("path:")) {
                        topLevelPath = trimmed.removePrefix("path:").trim()
                    }
                    if (isTopLevel && trimmed.startsWith("sha512:") && topLevelPath == fileName) {
                        lines[i] = "sha512: $newHash"
                    }

                    i++
                }

                return lines.joinToString("\n")
            }

            private fun isUrlEntry(trimmed: String): Boolean =
                trimmed.startsWith("- url:") || trimmed.startsWith("-url:")

            private fun extractUrl(trimmed: String): String =
                trimmed
                    .removePrefix("-")
                    .trimStart()
                    .removePrefix("url:")
                    .trim()

            private fun isEndOfFileEntry(entryLine: String): Boolean {
                if (isUrlEntry(entryLine)) return true
                if (entryLine.startsWith("blockMapSize:")) return false
                return !entryLine.startsWith(" ") && entryLine.contains(":")
            }

            private fun updateFileEntryFields(
                lines: MutableList<String>,
                startIndex: Int,
                newHash: String,
                newSize: Long,
            ): Int {
                var i = startIndex
                while (i < lines.size) {
                    val entryLine = lines[i].trimStart()
                    if (entryLine.startsWith("sha512:")) {
                        val indent = lines[i].length - lines[i].trimStart().length
                        lines[i] = " ".repeat(indent) + "sha512: $newHash"
                    } else if (entryLine.startsWith("size:")) {
                        val indent = lines[i].length - lines[i].trimStart().length
                        lines[i] = " ".repeat(indent) + "size: $newSize"
                    } else if (isEndOfFileEntry(entryLine)) {
                        break
                    }
                    i++
                }
                return i
            }
        }
    }

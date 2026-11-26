package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class MachOCommandResult(
    val exitCode: Int,
    val output: String = "",
)

internal fun interface MachOCommandRunner {
    fun run(command: List<String>): MachOCommandResult
}

// Any failure to launch the tool is reported as a failed run so the caller keeps the original file.
@Suppress("TooGenericExceptionCaught")
internal val defaultMachOCommandRunner =
    MachOCommandRunner { command ->
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            MachOCommandResult(exitCode, output)
        } catch (e: Exception) {
            val message = e.message ?: e::class.java.simpleName
            MachOCommandResult(exitCode = -1, output = message)
        }
    }

internal fun stripMachOFileSafely(
    binary: File,
    logger: Logger,
    commandRunner: MachOCommandRunner = defaultMachOCommandRunner,
): Boolean =
    mutateMachOFileSafely(
        binary = binary,
        operation = "strip -x",
        logger = logger,
        commandRunner = commandRunner,
        mutate = { copy, runner ->
            runner.run(listOf("/usr/bin/strip", "-x", copy.absolutePath))
        },
    )

internal fun mutateMachOFileSafely(
    binary: File,
    operation: String,
    logger: Logger,
    commandRunner: MachOCommandRunner = defaultMachOCommandRunner,
    mutate: (copy: File, commandRunner: MachOCommandRunner) -> MachOCommandResult,
    validateExtra: ((copy: File, commandRunner: MachOCommandRunner) -> String?)? = null,
): Boolean {
    val parent = binary.parentFile?.toPath()
        ?: throw IOException("Cannot create temporary copy for ${binary.absolutePath}: missing parent directory")
    val binaryPath = binary.toPath()
    val tempPath = createSiblingTempCopy(binaryPath, parent)

    try {
        val tempFile = tempPath.toFile()
        runCodesignRemoveSignatureBestEffort(tempFile, commandRunner, logger, operation)

        val mutationResult = runCatching { mutate(tempFile, commandRunner) }
            .getOrElse { error ->
                logger.warn(
                    "Skipping $operation for ${binary.name}: mutation step failed unexpectedly: ${error.message}",
                )
                return false
            }
        if (mutationResult.exitCode != 0) {
            logger.warn(
                "Skipping $operation for ${binary.name}: tool exit=${mutationResult.exitCode}. " +
                    "Original file kept. Output: ${mutationResult.output.asCompactLogLine()}",
            )
            return false
        }

        val otoolResult = commandRunner.run(listOf("/usr/bin/otool", "-l", tempFile.absolutePath))
        if (otoolResult.exitCode != 0) {
            logger.warn(
                "Skipping $operation for ${binary.name}: otool validation failed (exit=${otoolResult.exitCode}). " +
                    "Original file kept. Output: ${otoolResult.output.asCompactLogLine()}",
            )
            return false
        }

        validateExtra?.let { validator ->
            val validationError = runCatching { validator(tempFile, commandRunner) }
                .getOrElse { error ->
                    "extra validation threw ${error::class.java.simpleName}: ${error.message}"
                }
            if (validationError != null) {
                logger.warn(
                    "Skipping $operation for ${binary.name}: $validationError. Original file kept.",
                )
                return false
            }
        }

        replaceOriginalWithTemp(tempPath, binaryPath)
        return true
    } finally {
        Files.deleteIfExists(tempPath)
    }
}

private fun createSiblingTempCopy(
    binaryPath: Path,
    parent: Path,
): Path {
    val tempPath = Files.createTempFile(parent, "${binaryPath.fileName}.nucleus-", ".tmp")
    Files.copy(
        binaryPath,
        tempPath,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
    return tempPath
}

private fun runCodesignRemoveSignatureBestEffort(
    binary: File,
    commandRunner: MachOCommandRunner,
    logger: Logger,
    operation: String,
) {
    val result = commandRunner.run(listOf("/usr/bin/codesign", "--remove-signature", binary.absolutePath))
    if (result.exitCode == 0) return

    logger.warn(
        "codesign --remove-signature failed before $operation for ${binary.name} (exit=${result.exitCode}); " +
            "continuing. Output: ${result.output.asCompactLogLine()}",
    )
}

private fun replaceOriginalWithTemp(
    tempPath: Path,
    originalPath: Path,
) {
    try {
        Files.move(
            tempPath,
            originalPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tempPath, originalPath, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun String.asCompactLogLine(maxLength: Int = 300): String {
    val compact = replace('\n', ' ').replace('\r', ' ').trim()
    if (compact.length <= maxLength) return compact
    return compact.take(maxLength) + "..."
}

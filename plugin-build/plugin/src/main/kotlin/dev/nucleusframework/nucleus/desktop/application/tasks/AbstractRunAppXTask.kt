package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sideloads and launches an AppX package for local testing.
 *
 * Workflow:
 * 1. Finds the `.appx` file in the package output directory
 * 2. Removes any existing installation of the app
 * 3. Installs the new package via `Add-AppxPackage`
 * 4. Launches the app exe directly from the install location so stdout/stderr
 *    are visible in the Gradle console
 */
@DisableCachingByDefault(because = "Runs the application, not a cacheable build step")
abstract class AbstractRunAppXTask : AbstractNucleusTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appxDir: DirectoryProperty

    @get:Input
    abstract val identityName: Property<String>

    @get:Input
    @get:Optional
    abstract val applicationId: Property<String>

    @TaskAction
    fun run() {
        val dir = appxDir.get().asFile
        val appxFile =
            dir.walkTopDown().firstOrNull { it.extension == "appx" }
                ?: error("No .appx file found in $dir")

        val identity = identityName.get()

        logger.lifecycle("AppX file: ${appxFile.absolutePath}")
        logger.lifecycle("Identity: $identity")

        // Remove previous installation (ignore errors if not installed)
        logger.lifecycle("Removing previous installation...")
        runPowerShell(
            "Get-AppxPackage -Name '$identity' | Remove-AppxPackage -ErrorAction SilentlyContinue",
        )

        // Install the new package
        logger.lifecycle("Installing ${appxFile.name}...")
        val installResult =
            runPowerShell(
                "Add-AppxPackage -Path '${appxFile.absolutePath}' -ForceTargetApplicationShutdown",
            )
        if (installResult.exitCode != 0) {
            error(
                "Failed to install AppX package (exit code ${installResult.exitCode}):\n${installResult.output}",
            )
        }

        // Get the install location
        logger.lifecycle("Resolving install location...")
        val locationResult =
            runPowerShell(
                "(Get-AppxPackage -Name '$identity').InstallLocation",
            )
        val installLocation = locationResult.output.trim()
        if (installLocation.isEmpty()) {
            error("Could not resolve install location for '$identity'. Is the app installed?")
        }
        logger.lifecycle("Install location: $installLocation")

        // Find the app executable
        val installDir = File(installLocation)
        val exe =
            findAppExecutable(installDir)
                ?: error("No .exe found in $installLocation")
        logger.lifecycle("Launching: ${exe.absolutePath}")

        // Run the exe directly — stdout/stderr go to Gradle console
        execOperations.exec { spec ->
            spec.workingDir(exe.parentFile)
            spec.executable(exe.absolutePath)
            spec.isIgnoreExitValue = true
        }
    }

    private fun findAppExecutable(installDir: File): File? {
        // Look for the main exe: typically at the root or in an 'app' subdirectory.
        // Exclude runtime/jre executables.
        val candidates =
            installDir
                .walkTopDown()
                .filter { it.extension.equals("exe", ignoreCase = true) }
                .filter { !it.path.contains("\\runtime\\", ignoreCase = true) }
                .filter { !it.path.contains("\\jre\\", ignoreCase = true) }
                .toList()

        // Prefer exe at root level
        return candidates.minByOrNull { it.relativeTo(installDir).path.count { c -> c == '\\' } }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private fun runPowerShell(command: String): ProcessResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result =
            execOperations.exec { spec ->
                spec.commandLine(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    command,
                )
                spec.standardOutput = stdout
                spec.errorOutput = stderr
                spec.isIgnoreExitValue = true
            }
        val combined = stdout.toString(Charsets.UTF_8) + stderr.toString(Charsets.UTF_8)
        if (verbose.get() || result.exitValue != 0) {
            logger.lifecycle(combined)
        }
        return ProcessResult(result.exitValue, combined)
    }
}

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.patchMachOBuildVersion
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Builds the sandboxed (Mac App Store) GraalVM `.app` from the regular one.
 *
 * The bundles differ in four places only, so everything else — Info.plist, icons, the AWT, Skiko
 * and Nucleus libraries, app extensions — comes from the regular bundle as built:
 * - the executable is the sandboxed image ([sandboxedBinary]): compiled from JARs whose
 *   extract-and-load call sites go through `NucleusSandboxLoader`, and without Substrate's signal
 *   handling, whose named POSIX semaphore the sandbox denies (the image would die at start-up);
 * - the third-party native libraries the JARs carried ([nativeLibsDir]) ship in
 *   `Contents/Frameworks`, signed, since a library extracted at run time is quarantined inside
 *   the sandbox and refused by Gatekeeper;
 * - the redirect manifest ([sandboxManifest]) ships in `Contents/Resources`, a data file kept out
 *   of `Contents/MacOS` and `Contents/Frameworks` where Gatekeeper expects only code;
 * - the app is signed (ad hoc) with the sandbox [entitlementsFile], and the
 *   [provisioningProfile], when set, is embedded. The App Store PKG re-signs it with the real
 *   identity.
 *
 * `GraalVmInitializer` recognizes the bundle by the manifest and wires the rest at run time.
 */
@DisableCachingByDefault(because = "Copies and signs an app bundle")
abstract class AbstractAssembleSandboxedGraalvmMacAppTask : AbstractNucleusTask() {
    /** The directory holding the regular `.app`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val regularAppRoot: DirectoryProperty

    /** `<name>.app`. */
    @get:Input
    abstract val bundleName: Property<String>

    @get:Input
    abstract val executableName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sandboxedBinary: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibsDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sandboxManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val entitlementsFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val provisioningProfile: RegularFileProperty

    @get:Input
    abstract val minimumSystemVersion: Property<String>

    @get:Input
    abstract val sdkVersion: Property<String>

    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    /** Copies the regular bundle, swaps in the sandboxed pieces and signs the result. */
    @TaskAction
    fun assemble() {
        val outRoot = destinationDir.get().asFile
        if (outRoot.exists()) outRoot.deleteRecursively()
        outRoot.mkdirs()
        val source = regularAppRoot.get().asFile.resolve(bundleName.get())
        check(source.isDirectory) { "No regular GraalVM app bundle at $source" }
        val app = outRoot.resolve(bundleName.get())
        // ditto keeps symlinks, extended attributes and the nested signatures (app extensions).
        run("/usr/bin/ditto", source.absolutePath, app.absolutePath)
        val contents = app.resolve("Contents")

        val binary = contents.resolve("MacOS/${executableName.get()}")
        Files.copy(sandboxedBinary.get().asFile.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING)
        binary.setExecutable(true)
        // The same post-processing the regular binary gets, in the same order.
        patchMachOBuildVersion(binary, minimumSystemVersion.get(), sdkVersion.get(), logger)
        run("/usr/bin/install_name_tool", "-add_rpath", "@executable_path/.", binary.absolutePath, ignoreFailure = true)
        run("/usr/bin/strip", "-x", binary.absolutePath)

        val frameworks = contents.resolve("Frameworks").apply { mkdirs() }
        val libs = nativeLibsDir.get().asFile.listFiles { file -> file.isFile }.orEmpty().sortedBy { it.name }
        for (lib in libs) {
            val target = frameworks.resolve(lib.name)
            lib.copyTo(target, overwrite = true)
            run("/usr/bin/codesign", "--force", "--sign", "-", target.absolutePath)
        }
        val manifest = sandboxManifest.get().asFile
        manifest.copyTo(contents.resolve("Resources/${manifest.name}"), overwrite = true)
        provisioningProfile.orNull?.asFile?.copyTo(contents.resolve("embedded.provisionprofile"), overwrite = true)

        // Seal the bundle: the main executable gets the sandbox entitlements; nested code keeps
        // its own signature (dylibs above, app extensions as copied).
        run(
            "/usr/bin/codesign",
            "--force",
            "--sign",
            "-",
            "--entitlements",
            entitlementsFile.get().asFile.absolutePath,
            app.absolutePath,
        )
        logger.lifecycle("Sandboxed GraalVM app: $app (${libs.size} native lib(s) in Contents/Frameworks)")
    }

    private fun run(
        vararg command: String,
        ignoreFailure: Boolean = false,
    ) {
        val result = execOperations.exec { it.commandLine(*command).isIgnoreExitValue = true }
        if (result.exitValue != 0 && !ignoreFailure) {
            error("${File(command.first()).name} failed (exit ${result.exitValue}): ${command.joinToString(" ")}")
        }
    }
}

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.files.nucleusNativeEntries
import dev.nucleusframework.desktop.application.internal.files.unpackNucleusNativeLibs
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Splits the uber JAR the GraalVM native image is compiled from: the Nucleus JNI libraries of
 * [platformDir] go to [libsDir], to be shipped next to the executable, and [strippedJar] is the
 * same JAR without any library a Nucleus module lists, so native-image embeds none of them.
 * Everything else — including other `nucleus/native/` entries — is left as it is.
 *
 * Embedded libraries could only be loaded by extracting them to the user's cache on first launch;
 * next to the executable, `GraalVmInitializer`'s `java.library.path` resolves them directly.
 */
@DisableCachingByDefault(because = "Rewrites a local JAR; fast and not worth caching")
abstract class AbstractUnpackNucleusNativesTask : AbstractNucleusTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val uberJar: RegularFileProperty

    /** The `nucleus/native/<dir>/` of the image's platform, e.g. `win32-x64`. */
    @get:Input
    abstract val platformDir: Property<String>

    @get:OutputFile
    abstract val strippedJar: RegularFileProperty

    @get:OutputDirectory
    abstract val libsDir: DirectoryProperty

    /** Writes [strippedJar] and refills [libsDir] from [uberJar]. */
    @TaskAction
    fun unpack() {
        val libs = libsDir.get().asFile
        libs.deleteRecursively()
        val source = uberJar.get().asFile
        unpackNucleusNativeLibs(
            sourceJar = source,
            targetJar = strippedJar.get().asFile,
            libsDir = libs,
            platformDir = platformDir.get(),
            nucleusEntries = source.nucleusNativeEntries(),
        )
    }
}

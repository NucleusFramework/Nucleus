package dev.nucleusframework.desktop.application.internal.files

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import java.io.File
import java.util.zip.ZipFile

/** Resource root the Nucleus runtime modules ship their JNI libraries under. */
private const val NUCLEUS_NATIVE_ROOT = "nucleus/native/"

/**
 * Resource shipped by `core-runtime` once its `NativeLibraryLoader` reads
 * [NUCLEUS_NATIVE_LIBRARY_PATH]. An older runtime can only extract its libraries from the JARs,
 * so without this marker on the classpath they must stay there.
 */
internal const val NUCLEUS_BUNDLED_NATIVES_MARKER = "META-INF/nucleus/bundled-native-libraries"

/** System property naming the directory the packaged application's Nucleus libraries sit in. */
internal const val NUCLEUS_NATIVE_LIBRARY_PATH = "nucleus.native.libraryPath"

/** The `nucleus/native/<dir>/` a runtime module stores [os]/[arch]'s libraries in. */
internal fun nucleusNativeDir(
    os: OS,
    arch: Arch,
): String {
    val osDir =
        when (os) {
            OS.Windows -> "win32"
            OS.MacOS -> "darwin"
            OS.Linux -> "linux"
        }
    val archDir =
        when (arch) {
            Arch.X64 -> "x64"
            Arch.Arm64 -> "aarch64"
        }
    return "$osDir-$archDir"
}

/** Reads the central directory only, so scanning every runtime JAR stays cheap. */
internal fun File.hasZipEntry(predicate: (String) -> Boolean): Boolean =
    ZipFile(this).use { zip -> zip.entries().asSequence().any { predicate(it.name) } }

internal fun File.containsNucleusNativeLibs(): Boolean = hasZipEntry { it.startsWith(NUCLEUS_NATIVE_ROOT) }

/**
 * Rewrites [sourceJar] to [targetJar], moving the [platformDir] libraries into [libsDir] and
 * dropping every other platform's, so the application ships each library once, loose, instead of
 * six copies inside the JAR that the runtime would extract to the user's cache on first use.
 *
 * Only the files directly under the platform directory are moved, since those are the only ones
 * `NativeLibraryLoader` resolves; anything nested deeper stays in the JAR untouched.
 *
 * @return [targetJar] followed by the extracted libraries
 */
internal fun unpackNucleusNativeLibs(
    sourceJar: File,
    targetJar: File,
    libsDir: File,
    platformDir: String,
): List<File> {
    val platformRoot = "$NUCLEUS_NATIVE_ROOT$platformDir/"
    val outputFiles = mutableListOf(targetJar)

    targetJar.parentFile.mkdirs()
    libsDir.mkdirs()
    transformJar(sourceJar, targetJar) { entry, zin, zout ->
        val name = entry.name
        val platformEntry = name.removePrefix(platformRoot).takeIf { name.startsWith(platformRoot) }
        when {
            !name.startsWith(NUCLEUS_NATIVE_ROOT) -> copyZipEntry(entry, zin, zout)
            entry.isDirectory -> Unit
            platformEntry != null && '/' !in platformEntry -> {
                val lib = libsDir.resolve(platformEntry)
                zin.copyTo(lib)
                outputFiles += lib
            }
            platformEntry != null -> copyZipEntry(entry, zin, zout)
            // Another platform's library: never loaded by this application
            else -> Unit
        }
    }
    return outputFiles
}

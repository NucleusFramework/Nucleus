package dev.nucleusframework.desktop.application.internal.files

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import java.io.File
import java.util.zip.ZipFile

/**
 * Directory each Nucleus runtime module lists its JNI libraries in, one file per module holding
 * one `nucleus/native/<dir>/<file>` JAR entry per line. Only listed entries are moved out of the
 * JARs: anything else under `nucleus/native/` (an application's own libraries, a third-party
 * library's) may be read as a resource and stays untouched.
 */
private const val NUCLEUS_NATIVE_LIBRARIES_DIR = "META-INF/nucleus/native-libraries/"

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

/** The JAR entries the Nucleus modules packed into this JAR declare as their JNI libraries. */
internal fun File.nucleusNativeEntries(): Set<String> =
    ZipFile(this).use { zip ->
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory && it.name.startsWith(NUCLEUS_NATIVE_LIBRARIES_DIR) }
            .flatMap { entry -> zip.getInputStream(entry).bufferedReader().use { it.readLines() } }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

/**
 * Rewrites [sourceJar] to [targetJar], moving the [platformDir] libraries listed in
 * [nucleusEntries] into [libsDir] and dropping the other platforms' listed ones, so the
 * application ships each Nucleus library once, loose, instead of six copies inside the JAR that
 * the runtime would extract to the user's cache on first use. Every other entry is copied as is.
 *
 * @return [targetJar] followed by the extracted libraries
 */
internal fun unpackNucleusNativeLibs(
    sourceJar: File,
    targetJar: File,
    libsDir: File,
    platformDir: String,
    nucleusEntries: Set<String>,
): List<File> {
    val platformRoot = "nucleus/native/$platformDir/"
    val outputFiles = mutableListOf(targetJar)

    targetJar.parentFile.mkdirs()
    libsDir.mkdirs()
    transformJar(sourceJar, targetJar) { entry, zin, zout ->
        val name = entry.name
        when {
            entry.isDirectory || name !in nucleusEntries -> copyZipEntry(entry, zin, zout)
            name.startsWith(platformRoot) -> {
                val lib = libsDir.resolve(name.removePrefix(platformRoot))
                zin.copyTo(lib)
                outputFiles += lib
            }
            // Another platform's library: never loaded by this application
            else -> Unit
        }
    }
    return outputFiles
}

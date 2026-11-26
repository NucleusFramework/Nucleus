package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.currentArch
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Locates the MSVC C/C++ runtime redistributable of the newest Visual Studio install
 * (`VC\Redist\MSVC\<version>\<arch>\Microsoft.VC14x.CRT`).
 *
 * native-image needs MSVC on Windows, so a machine building a native image always has it. Unlike
 * the GraalVM toolchain's `bin` (msvcp140, vcruntime140, vcruntime140_1 only), it holds the whole
 * C++ runtime, e.g. msvcp140_1.dll, which ONNX Runtime imports.
 */
internal object MsvcRedistLocator {
    /**
     * The whole C++ runtime (~1.4 MB), not only what the executable imports: JNI libraries
     * loaded at run time import the rest (e.g. ONNX Runtime needs msvcp140_1.dll).
     */
    val CPP_RUNTIME_DLLS =
        listOf(
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "msvcp140.dll",
            "msvcp140_1.dll",
            "msvcp140_2.dll",
            "concrt140.dll",
        )

    private val crtDirRegex = Regex("""Microsoft\.VC14\d\.CRT""")

    /** [architecture] is the redist folder name: `x64` or `arm64`. */
    fun locateCrtDir(architecture: String): File? {
        // Set by the VS developer prompt (vcvars), it names the exact toolset in use
        System.getenv("VCToolsRedistDir")?.let { redist ->
            findCrtDir(File(redist, architecture))?.let { return it }
        }
        val visualStudioRoots =
            listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
                .map { File(it, "Microsoft Visual Studio") }
        return locateCrtDir(visualStudioRoots, architecture)
    }

    /**
     * Completes the C++ runtime of the jpackage app image(s) under [destinationDir] with [dlls]
     * from the redistributable, so the process loads a single copy of each, all of one version:
     * - the DLLs the JDK ships in `runtime\bin` (msvcp140, vcruntime140*) are loaded from there
     *   by `jvm.dll`, so they are replaced there, unless the JDK's are newer (a binary needs a
     *   runtime at least as new as its toolset);
     * - the others (msvcp140_1, ...) go next to the executable: Windows doesn't search
     *   `runtime\bin` for a library's dependencies, but searches the executable's directory.
     *   A copy of the JDK's there would be loaded too, beside the one in `runtime\bin`.
     */
    fun bundleIntoAppImage(
        destinationDir: File,
        dlls: List<String>,
        logger: Logger,
        crtDir: File? = if (dlls.isEmpty()) null else locateCrtDir(currentArch.id),
    ) {
        if (dlls.isEmpty()) return
        if (crtDir == null) {
            logger.warn(
                "[windows] MSVC redistributable not found (no Visual Studio with the C++ tools): " +
                    "the app may fail to load native libraries on machines without the Visual C++ " +
                    "Redistributable. Set nativeDistributions.windows.bundleCRuntime = false to silence this.",
            )
            return
        }
        val missing = dlls.filterNot { dll -> File(crtDir, dll).isFile }
        if (missing.isNotEmpty()) {
            logger.warn("[windows] C runtime DLLs not found in $crtDir: ${missing.joinToString()}")
        }
        // jpackage's app image: <destinationDir>/<name>/{<name>.exe, runtime/, app/}
        val appImageRoots =
            destinationDir
                .walkTopDown()
                .maxDepth(2)
                .filter { dir -> dir.isDirectory && File(dir, "runtime/bin").isDirectory }
                .filter { dir -> dir.children().any { it.extension.equals("exe", ignoreCase = true) } }
                .toList()
        for (root in appImageRoots) {
            val runtimeBin = File(root, "runtime/bin")
            val (jdkShipped, notShipped) = (dlls - missing.toSet()).partition { File(runtimeBin, it).isFile }
            val redistVersion = peFileVersion(File(crtDir, REFERENCE_DLL))
            val jdkVersion = peFileVersion(File(runtimeBin, REFERENCE_DLL))
            if (jdkVersion != null && redistVersion != null && compareVersions(jdkVersion, redistVersion) > 0) {
                logger.warn(
                    "[windows] The JDK's C++ runtime (${jdkVersion.joinToString(".")}) is newer than the MSVC " +
                        "redistributable (${redistVersion.joinToString(".")}, $crtDir): keeping the JDK's and adding " +
                        "${notShipped.joinToString()} from the older redistributable. " +
                        "Update Visual Studio to align them.",
                )
            } else {
                jdkShipped.forEach { File(crtDir, it).copyTo(File(runtimeBin, it), overwrite = true) }
            }
            notShipped.forEach { File(crtDir, it).copyTo(File(root, it), overwrite = true) }
            logger.info("Bundled the MSVC C/C++ runtime from $crtDir into $root")
        }
    }

    /**
     * The file version of a PE file, from its `VS_FIXEDFILEINFO` (found by its signature), e.g.
     * `[14, 42, 34438, 0]`; null when absent.
     */
    internal fun peFileVersion(file: File): List<Int>? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // dwSignature, dwStrucVersion, dwFileVersionMS, dwFileVersionLS
        for (offset in 0..bytes.size - FIXED_FILE_INFO_VERSION_END) {
            if (buffer.getInt(offset) == FIXED_FILE_INFO_SIGNATURE) {
                val ms = buffer.getInt(offset + FILE_VERSION_MS_OFFSET)
                val ls = buffer.getInt(offset + FILE_VERSION_LS_OFFSET)
                return listOf(ms ushr WORD_BITS, ms and WORD_MASK, ls ushr WORD_BITS, ls and WORD_MASK)
            }
        }
        return null
    }

    private const val REFERENCE_DLL = "msvcp140.dll"
    private const val FIXED_FILE_INFO_SIGNATURE = 0xFEEF04BD.toInt()
    private const val FIXED_FILE_INFO_VERSION_END = 16
    private const val FILE_VERSION_MS_OFFSET = 8
    private const val FILE_VERSION_LS_OFFSET = 12
    private const val WORD_BITS = 16
    private const val WORD_MASK = 0xFFFF

    /** Searches `<root>\<year>\<edition>\VC\Redist\MSVC\<version>`, newest version first. */
    internal fun locateCrtDir(
        visualStudioRoots: List<File>,
        architecture: String,
    ): File? =
        visualStudioRoots
            .asSequence()
            .flatMap { it.children() } // years: 2019, 2022, 18, ...
            .flatMap { it.children() } // editions: Community, Enterprise, BuildTools, ...
            .flatMap { File(it, "VC/Redist/MSVC").children() }
            .filter { it.name.first().isDigit() } // skips e.g. "v143"
            .sortedWith { left, right -> compareVersions(parseVersion(right.name), parseVersion(left.name)) }
            .mapNotNull { findCrtDir(File(it, architecture)) }
            .firstOrNull()

    private fun findCrtDir(archDir: File): File? =
        archDir.children().firstOrNull { it.isDirectory && crtDirRegex.matches(it.name) }

    private fun File.children(): Sequence<File> = listFiles()?.asSequence().orEmpty()

    private fun parseVersion(version: String): List<Int> = version.split('.').mapNotNull { it.toIntOrNull() }

    private fun compareVersions(
        left: List<Int>,
        right: List<Int>,
    ): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val leftValue = left.getOrElse(index) { 0 }
            val rightValue = right.getOrElse(index) { 0 }
            if (leftValue != rightValue) return leftValue.compareTo(rightValue)
        }
        return 0
    }
}

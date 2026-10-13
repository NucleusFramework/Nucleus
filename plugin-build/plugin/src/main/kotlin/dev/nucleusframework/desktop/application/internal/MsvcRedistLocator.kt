package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.currentArch
import org.gradle.api.logging.Logger
import java.io.File

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
     * Copies [dlls] from the redistributable next to the executable of the jpackage app image(s)
     * under [destinationDir]: unlike `runtime\bin`, the executable's directory is searched for a
     * library's dependencies. The whole set goes there, so every runtime DLL the process loads
     * comes from one version.
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
        // jpackage's app image: <destinationDir>/<name>/{<name>.exe, runtime/, app/}
        val appImageRoots =
            destinationDir
                .walkTopDown()
                .maxDepth(2)
                .filter { dir -> dir.isDirectory && File(dir, "runtime").isDirectory }
                .filter { dir -> dir.children().any { it.extension.equals("exe", ignoreCase = true) } }
                .toList()
        for (root in appImageRoots) {
            val missing = dlls.filterNot { dll -> File(crtDir, dll).isFile }
            dlls
                .map { File(crtDir, it) }
                .filter { it.isFile }
                .forEach { it.copyTo(File(root, it.name), overwrite = true) }
            if (missing.isNotEmpty()) {
                logger.warn("[windows] C runtime DLLs not found in $crtDir: ${missing.joinToString()}")
            }
            logger.info("Bundled the MSVC C/C++ runtime from $crtDir into $root")
        }
    }

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

package dev.nucleusframework.desktop.application.internal

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

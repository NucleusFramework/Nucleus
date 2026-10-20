package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One copy of ANGLE's libGLESv2 in the process, the one beside libEGL.
 *
 * ANGLE's libEGL loads libGLESv2 from its own directory at eglInitialize, and
 * only that copy sees the contexts libEGL makes current. Loaded from JARs, the
 * content-addressed extraction used to load libGLESv2 on its own first, from a
 * directory of its own: a second copy, which by-name lookups bind instead
 * (nucleus_tao_gl's glClear/glViewport, delay-loaded imports of other
 * libraries such as Filament's), their GL calls then running with no current
 * context (glGetString returning null).
 */
internal object AngleModulesHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(singleGlesBesideEgl())

    private fun singleGlesBesideEgl(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "windows ANGLE loads one libGLESv2, beside libEGL",
            skip = { "Windows only: ANGLE backs the Direct3D 11 path".takeIf { Platform.Current != Platform.Windows } },
        ) {
            // A painted window: eglInitialize has loaded libEGL's libGLESv2.
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle()
            val modules = loadedModules()
            val gles = modules.filter { it.name.equals("libGLESv2.dll", ignoreCase = true) }
            val egl = modules.filter { it.name.equals("libEGL.dll", ignoreCase = true) }
            check(egl.size == 1) { "expected one libEGL.dll, got $egl" }
            check(gles.size == 1) { "expected one libGLESv2.dll, got $gles" }
            check(gles.single().parentFile == egl.single().parentFile) {
                "libGLESv2.dll ${gles.single()} isn't beside libEGL.dll ${egl.single()}"
            }
        }

    /** The modules loaded in this process, by path (PowerShell: no native module enumeration on the JVM). */
    private fun loadedModules(): List<File> {
        val pid = ProcessHandle.current().pid()
        val process =
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-Process -Id $pid).Modules | ForEach-Object { \$_.FileName }",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        check(process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "listing the process's modules failed: $output"
        }
        return output.map { it.trim() }.filter { it.isNotEmpty() }.map(::File)
    }

    private const val POWERSHELL_TIMEOUT_SECONDS = 30L
}

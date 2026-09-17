package dev.nucleusframework.sampletao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * E2E regression test for issue #366 — the Tao Linux widget helper
 * must load GTK with RTLD_LOCAL. With RTLD_GLOBAL, any distro
 * where GTK's dependency closure includes libsqlite3 (NixOS: gtk3 ->
 * tinysparql -> sqlite) gets the system sqlite interposed over the copy
 * bundled in androidx/Room's JNI library, and the first write bound after
 * a window opened segfaults the JVM.
 *
 * The NixOS closure is recreated on any distro by copying the system
 * libgtk-3.so.0 and adding libsqlite3.so.0 to its DT_NEEDED with
 * patchelf, then pointing LD_LIBRARY_PATH at the copy. [main] (the repro
 * scenario) runs in a forked JVM because the failure mode is a SIGSEGV.
 */
class GtkSqliteInterpositionTest {
    @Test(timeout = 180_000)
    fun `bundled sqlite write survives a Tao window pulling system sqlite via GTK`() {
        assumeTrue("Linux only", System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(
            "needs a display",
            !System.getenv("DISPLAY").isNullOrEmpty() || !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty(),
        )
        assumeTrue("needs patchelf", runCatching { exec("patchelf", "--version") }.isSuccess)
        val gtk = findLibrary("libgtk-3.so.0")
        assumeTrue("needs system libgtk-3", gtk != null)
        assumeTrue("needs system libsqlite3", findLibrary("libsqlite3.so.0") != null)

        val shimDir = createTempDir("gtk-sqlite-shim")
        try {
            val patched = File(shimDir, "libgtk-3.so.0")
            gtk!!.copyTo(patched)
            exec("patchelf", "--add-needed", "libsqlite3.so.0", patched.absolutePath)

            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.nucleusframework.sampletao.SqliteReproMainKt",
                ).redirectErrorStream(true)
                    .apply { environment()["LD_LIBRARY_PATH"] = shimDir.absolutePath }
                    .start()
            val output = StringBuilder()
            val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            reader.start()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(5_000)

            assertTrue("repro app timed out\n$output", finished)
            assertEquals(
                "forked JVM died — system sqlite interposed over the bundled one (RTLD_GLOBAL regression?)\n$output",
                0,
                process.exitValue(),
            )
            // Guard against a vacuous pass: GTK (the patched copy) must have
            // actually been dlopen-ed and functional in that process.
            val stamp = output.lineSequence().firstOrNull { "gtk probe version = " in it }?.substringAfter("= ")
            assertTrue(
                "GTK dlopen probe not functional (version=$stamp)\n$output",
                !stamp.isNullOrEmpty() && stamp != "null" && !stamp.startsWith("error"),
            )
            assertTrue("write did not complete\n$output", output.contains("write OK"))
        } finally {
            shimDir.deleteRecursively()
        }
    }

    /** Resolves a library through `ldconfig -p`, honoring the current JVM arch. */
    private fun findLibrary(name: String): File? {
        val wantX64 = System.getProperty("os.arch") == "amd64"
        return exec("ldconfig", "-p")
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("$name ") && (!wantX64 || "x86-64" in it) }
            .mapNotNull { line ->
                line
                    .substringAfter("=> ", "")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::File)
            }.firstOrNull(File::exists)
    }

    private fun exec(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
        return output
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files
            .createTempDirectory(prefix)
            .toFile()
}

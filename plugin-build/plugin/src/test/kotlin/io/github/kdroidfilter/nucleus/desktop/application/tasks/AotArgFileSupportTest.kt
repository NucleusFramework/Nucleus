package io.github.kdroidfilter.nucleus.desktop.application.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AotArgFileSupportTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun `buildAotJavaArgs preserves expected argument order`() {
        val aotCacheFile = tmpDir.newFile("app.aot")
        val args =
            buildAotJavaArgs(
                classpath = "/tmp/lib/a.jar:/tmp/lib/b.jar",
                javaOptions = listOf("-Xmx1g", "-Dfoo=bar"),
                mainClass = "com.example.Main",
                aotCacheFile = aotCacheFile,
            )

        assertEquals("-XX:AOTCacheOutput=${aotCacheFile.absolutePath}", args[0])
        assertEquals("-Dnucleus.aot.mode=training", args[1])
        assertEquals("-cp", args[2])
        assertEquals("/tmp/lib/a.jar:/tmp/lib/b.jar", args[3])
        assertEquals("-Xmx1g", args[4])
        assertEquals("-Dfoo=bar", args[5])
        assertEquals("com.example.Main", args[6])
    }

    @Test
    fun `escapeArgForArgFile quotes and escapes whitespace quotes and backslashes`() {
        val escaped = escapeArgForArgFile("-Dfoo=\"C:\\Program Files\\Nucleus\"")

        assertEquals("\"-Dfoo=\\\"C:\\\\Program Files\\\\Nucleus\\\"\"", escaped)
    }

    @Test
    fun `escapeArgForArgFile quotes empty string`() {
        assertEquals("\"\"", escapeArgForArgFile(""))
    }

    @Test
    fun `writeJavaArgFile writes utf8 escaped args one per line`() {
        val file = tmpDir.newFile("nucleus-aot.args")
        val args =
            listOf(
                "-Xmx1g",
                "-cp",
                "C:\\Program Files\\Nucleus\\lib\\a.jar;D:\\项目\\b.jar",
                "-Dfoo=\"a b\"",
                "com.example.Main",
            )

        writeJavaArgFile(file, args)

        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(args.size, lines.size)
        assertEquals("-Xmx1g", lines[0])
        assertEquals("-cp", lines[1])
        assertEquals("\"C:\\\\Program Files\\\\Nucleus\\\\lib\\\\a.jar;D:\\\\项目\\\\b.jar\"", lines[2])
        assertEquals("\"-Dfoo=\\\"a b\\\"\"", lines[3])
        assertEquals("com.example.Main", lines[4])
    }

    @Test
    fun `writeJavaArgFile escapes windows path with backslashes but no spaces`() {
        val file = tmpDir.newFile("nucleus-aot-nospace.args")

        writeJavaArgFile(file, listOf("C:\\Windows\\System32"))

        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(1, lines.size)
        assertEquals("\"C:\\\\Windows\\\\System32\"", lines[0])
    }

    @Test
    fun `escapeArgForArgFile rejects newline characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            escapeArgForArgFile("line1\nline2")
        }
    }

    @Test
    fun `buildAotTempFileCandidateDirs keeps deterministic order and de-duplicates`() {
        val appDir = tmpDir.newFolder("app")
        val aotDir = tmpDir.newFolder("aot")
        val aotFile = File(aotDir, "app.aot")

        val dirs =
            buildAotTempFileCandidateDirs(
                appDir = appDir,
                aotCacheFile = aotFile,
                tmpDirPath = appDir.absolutePath,
            )

        assertEquals(2, dirs.size)
        assertEquals(appDir.absoluteFile, dirs[0])
        assertEquals(aotDir.absoluteFile, dirs[1])
    }

    @Test
    fun `createAotTempFileWithFallback falls back when earlier directory is not writable`() {
        val writableDir = tmpDir.newFolder("writable")
        val impossibleDir = File(writableDir, "missing-parent/child")

        val tempFile =
            createAotTempFileWithFallback(
                prefix = "nucleus-aot-",
                suffix = ".args",
                candidateDirs = listOf(impossibleDir, writableDir),
            )

        assertTrue(tempFile.exists())
        assertEquals(writableDir.absoluteFile, tempFile.parentFile.absoluteFile)
    }
}

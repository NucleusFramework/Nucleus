package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AotJarTimestampsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the pinned instant converts to the matching FILETIME`() {
        // (315532802 s since 1970 + 11644473600 s from 1601 to 1970) in 100-ns ticks.
        assertEquals(119_600_064_020_000_000L, AotJarTimestamps.fileTime())
    }

    @Test
    fun `normalize pins the JARs only`() {
        val dir = tmp.newFolder("app")
        val jar = dir.resolve("lib.jar").apply { writeText("jar") }
        val cfg = dir.resolve("App.cfg").apply { writeText("cfg") }
        val cfgTime = Files.getLastModifiedTime(cfg.toPath())

        AotJarTimestamps.normalize(dir)

        assertEquals(AotJarTimestamps.INSTANT, Files.getLastModifiedTime(jar.toPath()).toInstant())
        assertEquals(cfgTime, Files.getLastModifiedTime(cfg.toPath()))
    }

    @Test
    fun `the NSIS macro sets the FILETIME halves on the JARs of the given directory`() {
        val fileTime = AotJarTimestamps.fileTime()
        val macro = AotJarTimestamps.nsisMacros("""versions\1.0.0\app""")
        assertTrue(macro.startsWith("!macro ${AotJarTimestamps.NSIS_MACRO}\n"))
        assertTrue("*(i ${fileTime and 0xFFFFFFFFL}, i ${fileTime ushr 32}) p .r2" in macro)
        assertTrue("""FindFirst ${'$'}0 ${'$'}1 "${'$'}INSTDIR\versions\1.0.0\app\*.jar"""" in macro)
        assertTrue("""CreateFileW(w "${'$'}INSTDIR\versions\1.0.0\app\${'$'}1"""" in macro)
        assertTrue(macro.trimEnd().endsWith("!macroend"))
    }
}

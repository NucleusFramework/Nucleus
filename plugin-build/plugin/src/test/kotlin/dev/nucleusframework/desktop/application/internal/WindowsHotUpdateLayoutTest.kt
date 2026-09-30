package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsHotUpdateLayoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpackageImage(): File {
        val image = tmp.newFolder("App")
        File(image, "App.exe").writeText("launcher")
        File(image, "runtime/bin").mkdirs()
        File(image, "runtime/bin/jvm.dll").writeText("jvm")
        File(image, "app/resources").mkdirs()
        File(image, "app/app.jar").writeText("jar")
        File(image, "app/nucleus_tao.dll").writeText("dll")
        File(image, "app/App.cfg").writeText(
            "[Application]\r\napp.classpath=\$APPDIR\\app.jar\r\napp.mainclass=MainKt\r\n\r\n" +
                "[JavaOptions]\r\njava-options=-Dnucleus.native.libraryPath=\$APPDIR\r\n",
        )
        return image
    }

    @Test
    fun `app image is moved under versions and the cfg points into it`() {
        val image = jpackageImage()

        assertTrue(WindowsHotUpdateLayout.apply(image, "1.2.0"))

        assertTrue(File(image, "App.exe").isFile)
        assertEquals(listOf("App.cfg"), File(image, "app").list()!!.toList())
        assertTrue(File(image, "versions/1.2.0/runtime/bin/jvm.dll").isFile)
        assertTrue(File(image, "versions/1.2.0/app/app.jar").isFile)
        assertTrue(File(image, "versions/1.2.0/app/nucleus_tao.dll").isFile)
        assertTrue(File(image, "versions/1.2.0/app/resources").isDirectory)
        assertFalse(File(image, "runtime").exists())
        assertEquals(
            "[Application]\r\n" +
                "app.runtime=\$ROOTDIR\\versions\\1.2.0\\runtime\r\n" +
                "app.classpath=\$ROOTDIR\\versions\\1.2.0\\app\\app.jar\r\n" +
                "app.mainclass=MainKt\r\n\r\n" +
                "[JavaOptions]\r\n" +
                "java-options=-Dnucleus.native.libraryPath=\$ROOTDIR\\versions\\1.2.0\\app\r\n",
            File(image, "app/App.cfg").readText(),
        )
    }

    @Test
    fun `an image without cfg or runtime is left untouched`() {
        val image = tmp.newFolder("Native")
        File(image, "App.exe").writeText("native image")

        assertFalse(WindowsHotUpdateLayout.apply(image, "1.2.0"))
        assertFalse(File(image, "versions").exists())
    }

    @Test
    fun `an already versioned image is left untouched`() {
        val image = jpackageImage()
        WindowsHotUpdateLayout.apply(image, "1.2.0")
        val cfg = File(image, "app/App.cfg").readText()

        assertFalse(WindowsHotUpdateLayout.apply(image, "1.3.0"))
        assertEquals(cfg, File(image, "app/App.cfg").readText())
    }

    @Test
    fun `an existing app runtime entry is replaced`() {
        val cfg = "[Application]\napp.runtime=\$APPDIR\\..\\runtime\napp.mainclass=MainKt\n"

        val rewritten = WindowsHotUpdateLayout.rewriteCfg(cfg, "\$ROOTDIR\\versions\\2.0.0")

        assertEquals(
            "[Application]\napp.runtime=\$ROOTDIR\\versions\\2.0.0\\runtime\napp.mainclass=MainKt\n",
            rewritten,
        )
    }

    @Test
    fun `version directory names are sanitized`() {
        assertEquals("1.2.0-beta.1", WindowsHotUpdateLayout.versionDirName("1.2.0-beta.1"))
        assertEquals("1.2.0_build_7", WindowsHotUpdateLayout.versionDirName("1.2.0 build/7"))
        assertEquals("1.2", WindowsHotUpdateLayout.versionDirName("1.2."))
        assertEquals("current", WindowsHotUpdateLayout.versionDirName("  "))
    }
}

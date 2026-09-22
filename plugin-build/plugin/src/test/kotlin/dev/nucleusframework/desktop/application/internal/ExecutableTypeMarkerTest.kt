package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutableTypeMarkerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val logger = Logging.getLogger(ExecutableTypeMarkerTest::class.java)

    @Test
    fun `macOS bundle marker is written to Contents Resources instead of Contents MacOS`() {
        val appImageDir = tmpDir.newFolder("appImage")
        val macOsDir = appImageDir.resolve("App.app/Contents/MacOS")
        assertTrue(macOsDir.mkdirs())
        macOsDir.resolve("app").writeText("binary")

        updateExecutableTypeInAppImage(appImageDir, TargetFormat.Dmg, logger, "1.2.3")

        val marker = appImageDir.resolve("App.app/Contents/Resources/$EXECUTABLE_TYPE_MARKER")
        assertTrue(marker.isFile)
        assertEquals("dmg\n1.2.3\n", marker.readText())
        assertFalse(macOsDir.resolve(EXECUTABLE_TYPE_MARKER).exists())
    }

    @Test
    fun `linux and windows layout writes the marker next to the binary`() {
        val appImageDir = tmpDir.newFolder("appImage")
        appImageDir.resolve("app").writeText("binary")

        updateExecutableTypeInAppImage(appImageDir, TargetFormat.Deb, logger, "2.0.0")

        val marker = appImageDir.resolve(EXECUTABLE_TYPE_MARKER)
        assertTrue(marker.isFile)
        assertEquals("deb\n2.0.0\n", marker.readText())
    }

    @Test
    fun `jvm launcher with cfg keeps the java-options flow and writes no marker`() {
        val appImageDir = tmpDir.newFolder("appImage")
        val macOsDir = appImageDir.resolve("App.app/Contents/MacOS")
        assertTrue(macOsDir.mkdirs())
        val cfgFile = appImageDir.resolve("App.app/Contents/app/App.cfg")
        assertTrue(cfgFile.parentFile.mkdirs())
        cfgFile.writeText("[JavaOptions]\n")

        updateExecutableTypeInAppImage(appImageDir, TargetFormat.Dmg, logger, "1.2.3")

        assertTrue(cfgFile.readText().contains("java-options=-D$APP_EXECUTABLE_TYPE=dmg"))
        assertFalse(macOsDir.resolve(EXECUTABLE_TYPE_MARKER).exists())
        assertFalse(appImageDir.resolve("App.app/Contents/Resources/$EXECUTABLE_TYPE_MARKER").exists())
    }
}

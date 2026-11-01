package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * jpackage on Windows and macOS rejects SemVer pre-release and build metadata, while electron-builder
 * needs the full version to publish and compare pre-releases. The app image gets the numeric core on
 * those platforms only; Linux jpackage accepts the full version and keeps it.
 */
class JpackageAppVersionTest {
    @Test
    fun `pre-release suffix is dropped on Windows and macOS`() {
        for (os in listOf(OS.Windows, OS.MacOS)) {
            assertEquals("2.3.5", jpackageAppVersion("2.3.5-beta.7", os))
        }
    }

    @Test
    fun `build metadata is dropped on Windows and macOS`() {
        for (os in listOf(OS.Windows, OS.MacOS)) {
            assertEquals("2.3.5", jpackageAppVersion("2.3.5+build.42", os))
            assertEquals("2.3.5", jpackageAppVersion("2.3.5-rc.1+build.42", os))
        }
    }

    @Test
    fun `plain versions are unchanged everywhere`() {
        for (os in OS.entries) {
            assertEquals("2.3.5", jpackageAppVersion("2.3.5", os))
        }
    }

    @Test
    fun `Linux keeps the full version`() {
        assertEquals("2.3.5-beta.7", jpackageAppVersion("2.3.5-beta.7", OS.Linux))
        assertEquals("2.3.5+build.42", jpackageAppVersion("2.3.5+build.42", OS.Linux))
    }
}

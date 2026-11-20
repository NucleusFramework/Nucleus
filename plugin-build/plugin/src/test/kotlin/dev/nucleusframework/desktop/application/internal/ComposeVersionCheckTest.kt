package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeVersionCheckTest {
    private val tao = ResolvedModule("dev.nucleusframework", "nucleus.decorated-window-tao", "3.0.0")

    private fun composeUi(version: String) = ResolvedModule("org.jetbrains.compose.ui", "ui-desktop", version)

    @Test
    fun `classifies release and prerelease versions by major and minor`() {
        assertEquals(ComposeLine.TOO_OLD, composeLine("1.12.1"))
        assertEquals(ComposeLine.TOO_OLD, composeLine("1.9.3"))
        assertEquals(ComposeLine.TOO_OLD, composeLine("0.99.0"))
        assertEquals(ComposeLine.TESTED, composeLine("1.13.0"))
        assertEquals(ComposeLine.TESTED, composeLine("1.13.0-beta02"))
        assertEquals(ComposeLine.TESTED, composeLine("1.13.5"))
        assertEquals(ComposeLine.UNTESTED, composeLine("1.14.0-alpha01"))
        assertEquals(ComposeLine.UNTESTED, composeLine("2.0.0"))
    }

    @Test
    fun `an unreadable version is not reported`() {
        assertNull(composeLine("main-SNAPSHOT"))
        assertNull(composeRuntimeVersionFinding(listOf(tao, composeUi("dev-SNAPSHOT"))))
        assertNull(composePluginVersionWarning("dev-SNAPSHOT"))
    }

    @Test
    fun `the Tao runtime on an old Compose fails the build and names both versions`() {
        val finding = composeRuntimeVersionFinding(listOf(tao, composeUi("1.12.1")))
        assertNotNull(finding)
        assertTrue(finding!!.fatal)
        assertTrue(finding.message, "3.0.0" in finding.message && "1.12.1" in finding.message)
    }

    @Test
    fun `the Tao runtime on the tested line passes`() {
        assertNull(composeRuntimeVersionFinding(listOf(tao, composeUi("1.13.0-beta02"))))
        assertNull(composeRuntimeVersionFinding(listOf(tao, composeUi("1.13.2"))))
    }

    @Test
    fun `the Tao runtime on a newer minor line is only a warning`() {
        val finding = composeRuntimeVersionFinding(listOf(tao, composeUi("1.14.0-alpha01")))
        assertNotNull(finding)
        assertFalse(finding!!.fatal)
        assertTrue(finding.message, "1.14.0-alpha01" in finding.message && "not been tested" in finding.message)
    }

    @Test
    fun `any Compose without the Tao runtime is not Nucleus's concern`() {
        val other = ResolvedModule("dev.nucleusframework", "nucleus.core-runtime", "3.0.0")
        assertNull(composeRuntimeVersionFinding(listOf(other, composeUi("1.12.1"))))
        assertNull(composeRuntimeVersionFinding(listOf(other, composeUi("1.14.0"))))
    }

    @Test
    fun `the JetBrains plugin outside the tested line is a warning either way`() {
        assertNotNull(composePluginVersionWarning("1.12.1"))
        assertNotNull(composePluginVersionWarning("1.14.0"))
        assertNull(composePluginVersionWarning("1.13.0-beta02"))
    }
}

package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeVersionCheckTest {
    private val tao = ResolvedModule("dev.nucleusframework", "nucleus.decorated-window-tao", "3.0.0")

    private fun composeUi(version: String) = ResolvedModule("org.jetbrains.compose.ui", "ui-desktop", version)

    @Test
    fun `reads major and minor of release and prerelease versions`() {
        assertEquals(true, isAtLeastMinCompose("1.13.0"))
        assertEquals(true, isAtLeastMinCompose("1.13.0-beta02"))
        assertEquals(true, isAtLeastMinCompose("1.14.0-alpha01"))
        assertEquals(true, isAtLeastMinCompose("2.0.0"))
        assertEquals(false, isAtLeastMinCompose("1.12.1"))
        assertEquals(false, isAtLeastMinCompose("1.9.3"))
        assertEquals(false, isAtLeastMinCompose("0.99.0"))
    }

    @Test
    fun `an unreadable version is not reported`() {
        assertNull(isAtLeastMinCompose("main-SNAPSHOT"))
        assertNull(composeRuntimeVersionProblem(listOf(tao, composeUi("dev-SNAPSHOT"))))
        assertNull(composePluginVersionProblem("dev-SNAPSHOT"))
    }

    @Test
    fun `the Tao runtime on an old Compose is an error that names both versions`() {
        val problem = composeRuntimeVersionProblem(listOf(tao, composeUi("1.12.1")))
        assertNotNull(problem)
        assertTrue(problem!!, "3.0.0" in problem && "1.12.1" in problem && "1.13" in problem)
    }

    @Test
    fun `the Tao runtime on Compose 1_13 passes`() {
        assertNull(composeRuntimeVersionProblem(listOf(tao, composeUi("1.13.0-beta02"))))
    }

    @Test
    fun `an old Compose without the Tao runtime is not Nucleus's concern`() {
        val other = ResolvedModule("dev.nucleusframework", "nucleus.core-runtime", "3.0.0")
        assertNull(composeRuntimeVersionProblem(listOf(other, composeUi("1.12.1"))))
    }

    @Test
    fun `an old JetBrains Compose plugin is a warning`() {
        assertNotNull(composePluginVersionProblem("1.12.1"))
        assertNull(composePluginVersionProblem("1.13.0-beta02"))
    }
}

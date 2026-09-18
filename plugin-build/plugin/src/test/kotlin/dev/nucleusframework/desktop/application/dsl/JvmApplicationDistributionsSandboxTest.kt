package dev.nucleusframework.desktop.application.dsl

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a format goes through the sandboxed (store) pipeline is a DSL decision for PKG:
 * `macOS { pkg { appStore } }` selects the Mac App Store (default) or Developer ID distribution.
 */
class JvmApplicationDistributionsSandboxTest {
    private fun newDistributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    @Test
    fun `pkg targets the app store by default`() {
        val distributions = newDistributions()
        assertTrue(distributions.macOS.pkg.appStore)
        assertTrue(distributions.isSandboxed(TargetFormat.Pkg))
    }

    @Test
    fun `developer id pkg is not sandboxed`() {
        val distributions = newDistributions()
        distributions.macOS.pkg { it.appStore = false }
        assertFalse(distributions.isSandboxed(TargetFormat.Pkg))
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `deprecated appStore flag aliases pkg appStore`() {
        val distributions = newDistributions()
        distributions.macOS.appStore = false
        assertFalse(distributions.macOS.pkg.appStore)
        assertFalse(distributions.macOS.appStore)
        assertFalse(distributions.isSandboxed(TargetFormat.Pkg))
    }

    @Test
    fun `appx and flatpak are always sandboxed`() {
        val distributions = newDistributions()
        distributions.macOS.pkg.appStore = false
        assertTrue(distributions.isSandboxed(TargetFormat.AppX))
        assertTrue(distributions.isSandboxed(TargetFormat.Flatpak))
    }

    @Test
    fun `direct distribution formats are never sandboxed`() {
        val distributions = newDistributions()
        for (format in listOf(TargetFormat.Dmg, TargetFormat.Zip, TargetFormat.Msi, TargetFormat.Nsis, TargetFormat.Deb)) {
            assertFalse(format.name, distributions.isSandboxed(format))
        }
    }
}

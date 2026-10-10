package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.PkgSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Mac App Store rejects installer packages carrying install scripts (error 90254), so the
 * contradiction must surface at configuration time. [MacPkgScripts] repeats the check when the
 * scripts are staged, but a build should never get that far.
 */
class PkgScriptValidationTest {
    private fun pkgWithScript(appStore: Boolean): PkgSettings =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)
            .macOS
            .pkg
            .apply {
                this.appStore = appStore
                postInstall.set(File("postinstall"))
            }

    @Test
    fun `scripts on an app store pkg are a configuration error`() {
        val error = assertThrows(IllegalStateException::class.java) { validatePkgScripts(pkgWithScript(appStore = true)) }
        assertTrue(error.message, error.message!!.contains("appStore = false"))
    }

    @Test
    fun `scripts on a developer id pkg are accepted`() {
        validatePkgScripts(pkgWithScript(appStore = false))
    }

    @Test
    fun `an app store pkg without scripts is accepted`() {
        val project = ProjectBuilder.builder().build()
        val distributions = project.objects.newInstance(JvmApplicationDistributions::class.java)
        assertTrue(distributions.macOS.pkg.appStore)
        validatePkgScripts(distributions.macOS.pkg)
    }
}

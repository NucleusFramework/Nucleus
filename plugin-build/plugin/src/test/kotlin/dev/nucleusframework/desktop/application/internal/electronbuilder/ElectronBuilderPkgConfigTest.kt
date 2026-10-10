package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PKG target serves two channels. App Store: electron-builder gets no installer identity (its
 * `pkg.ts` only knows "Developer ID Installer") and the package task re-signs with `productsign`;
 * its binaries are not Developer ID, so notarytool would reject them as `Invalid` (#650) and the
 * config must opt out of electron-builder's own notarization. Developer ID: electron-builder signs
 * the installer itself from the bare identity, and `notarizePkg` notarizes the `.pkg`.
 */
class ElectronBuilderPkgConfigTest {
    private fun newDistributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    private fun renderMac(
        targetFormat: TargetFormat,
        distributions: JvmApplicationDistributions = newDistributions(),
    ): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateMacConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormat = targetFormat,
            targetArch = Arch.Arm64,
        )
        return yaml.toString()
    }

    private fun signedDistributions(appStore: Boolean): JvmApplicationDistributions =
        newDistributions().apply {
            macOS.signing.sign.set(true)
            macOS.signing.identity.set("Developer ID Application: Acme Corp (TEAM1234)")
            macOS.pkg.appStore = appStore
        }

    // missingDelimiterValue keeps the negative assertions honest: without it a dropped `pkg:` block
    // would return the whole document and every "does not contain" assertion would pass vacuously.
    private fun String.pkgSection(): String = substringAfter("\npkg:\n", missingDelimiterValue = "")

    @Test
    fun `pkg disables electron-builder notarization for both channels`() {
        assertTrue(renderMac(TargetFormat.Pkg).contains("  notarize: false"))
        assertTrue(renderMac(TargetFormat.Pkg, signedDistributions(appStore = false)).contains("  notarize: false"))
    }

    @Test
    fun `dmg keeps electron-builder notarization`() {
        val yaml = renderMac(TargetFormat.Dmg)
        assertFalse(yaml, yaml.contains("notarize:"))
    }

    @Test
    fun `unsigned pkg disables installer signing`() {
        val yaml = renderMac(TargetFormat.Pkg)
        assertTrue(yaml, yaml.pkgSection().contains("  identity: null"))
    }

    @Test
    fun `app store pkg leaves installer signing to productsign`() {
        val yaml = renderMac(TargetFormat.Pkg, signedDistributions(appStore = true))
        assertFalse(yaml, yaml.pkgSection().contains("identity:"))
    }

    @Test
    fun `developer id pkg hands the bare installer identity to electron-builder`() {
        val yaml = renderMac(TargetFormat.Pkg, signedDistributions(appStore = false))
        assertTrue(yaml, yaml.pkgSection().contains("  identity: \"Acme Corp (TEAM1234)\""))
        assertFalse(yaml, yaml.pkgSection().contains("Developer ID"))
    }

    @Test
    fun `pkg declares the staged scripts directory only when a script is configured`() {
        val distributions = newDistributions().apply { macOS.pkg.appStore = false }
        assertFalse(renderMac(TargetFormat.Pkg, distributions).contains("scripts:"))

        distributions.macOS.pkg.postInstall.set(File("postinstall"))
        val yaml = renderMac(TargetFormat.Pkg, distributions)
        assertTrue(yaml, yaml.pkgSection().contains("  scripts: \"pkg-scripts\""))
    }
}

package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * electron-builder notarizes the packaged `.app` on its own whenever the notary credentials
 * (`APPLE_ID`, `APPLE_API_KEY`, `APPLE_KEYCHAIN_PROFILE`, …) are in the environment. The PKG
 * target is App Store, whose binaries notarytool rejects as `Invalid` (#650), so its config must
 * opt out explicitly; the Developer ID targets keep electron-builder's default.
 */
class ElectronBuilderPkgConfigTest {
    private fun renderMac(targetFormat: TargetFormat): String {
        val distributions =
            ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateMacConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormat = targetFormat,
            targetArch = Arch.Arm64,
        )
        return yaml.toString()
    }

    @Test
    fun `pkg disables electron-builder notarization`() {
        val yaml = renderMac(TargetFormat.Pkg)
        assertTrue(yaml, yaml.contains("  notarize: false"))
    }

    @Test
    fun `dmg keeps electron-builder notarization`() {
        val yaml = renderMac(TargetFormat.Dmg)
        assertFalse(yaml, yaml.contains("notarize:"))
    }
}

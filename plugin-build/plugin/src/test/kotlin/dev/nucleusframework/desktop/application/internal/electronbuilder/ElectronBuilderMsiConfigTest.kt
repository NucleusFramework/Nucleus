package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MSI target only forwarded `upgradeCode` and `perMachine` to electron-builder, so every other
 * Windows installer setting fell back to an electron-builder default: the shortcut landed in the
 * start menu root instead of `windows.menuGroup`, the installer ran without a wizard and the app
 * was launched as soon as it finished. These assert the settings actually reach the config.
 */
class ElectronBuilderMsiConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    private fun renderWindows(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat = TargetFormat.Msi,
    ): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateWindowsConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormat = targetFormat,
            targetArch = Arch.X64,
            windowsIconOverride = null,
            executableName = "nucleusdemo",
            nsisProtocolInclude = null,
        )
        return yaml.toString()
    }

    @Test
    fun `msi keeps the electron-builder defaults when nothing is configured`() {
        val yaml = renderWindows(distributions())

        assertTrue(yaml, yaml.contains("msi:"))
        assertTrue(yaml, yaml.contains("oneClick: true"))
        assertTrue(yaml, yaml.contains("runAfterFinish: true"))
        assertTrue(yaml, yaml.contains("createDesktopShortcut: true"))
        assertTrue(yaml, yaml.contains("createStartMenuShortcut: true"))
        assertFalse(yaml, yaml.contains("menuCategory"))
        assertFalse(yaml, yaml.contains("shortcutName"))
    }

    @Test
    fun `msi settings are forwarded`() {
        val distributions = distributions()
        distributions.windows.msi.oneClick = false
        distributions.windows.msi.runAfterFinish = false
        distributions.windows.msi.createDesktopShortcut = false
        distributions.windows.msi.createStartMenuShortcut = false
        distributions.windows.msi.menuCategory = "Acme Apps"
        distributions.windows.msi.shortcutName = "Acme"

        val yaml = renderWindows(distributions)

        assertTrue(yaml, yaml.contains("oneClick: false"))
        assertTrue(yaml, yaml.contains("runAfterFinish: false"))
        assertTrue(yaml, yaml.contains("createDesktopShortcut: false"))
        assertTrue(yaml, yaml.contains("createStartMenuShortcut: false"))
        assertTrue(yaml, yaml.contains("menuCategory: \"Acme Apps\""))
        assertTrue(yaml, yaml.contains("shortcutName: \"Acme\""))
    }

    @Test
    fun `windows menuGroup is used as the menu category`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "Acme Apps"

        val yaml = renderWindows(distributions)

        assertTrue(yaml, yaml.contains("menuCategory: \"Acme Apps\""))
    }

    @Test
    fun `explicit menu category wins over menuGroup`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "From menuGroup"
        distributions.windows.msi.menuCategory = "From msi"

        val yaml = renderWindows(distributions)

        assertTrue(yaml, yaml.contains("menuCategory: \"From msi\""))
        assertFalse(yaml, yaml.contains("From menuGroup"))
    }

    @Test
    fun `nsis target is unaffected by the msi settings`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "Acme Apps"

        val yaml = renderWindows(distributions, TargetFormat.Nsis)

        assertTrue(yaml, yaml.contains("nsis:"))
        assertFalse(yaml, yaml.contains("msi:"))
        assertFalse(yaml, yaml.contains("menuCategory"))
    }
}

package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NSIS targets exposed most installer settings but not `menuCategory` / `shortcutName`, so a
 * start menu group could not be declared at all: the shortcut always landed in the start menu
 * root. These assert the two options reach the config, including `windows.menuGroup` acting as
 * the default for the group, mirroring the MSI target (see ElectronBuilderMsiConfigTest).
 */
class ElectronBuilderNsisConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    private fun renderWindows(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat = TargetFormat.Nsis,
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
    fun `nsis omits the options when nothing is configured`() {
        val yaml = renderWindows(distributions())

        assertTrue(yaml, yaml.contains("nsis:"))
        assertFalse(yaml, yaml.contains("menuCategory"))
        assertFalse(yaml, yaml.contains("shortcutName"))
    }

    @Test
    fun `nsis settings are forwarded`() {
        val distributions = distributions()
        distributions.windows.nsis.menuCategory = "Acme Apps"
        distributions.windows.nsis.shortcutName = "Acme"

        val yaml = renderWindows(distributions)

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
        distributions.windows.nsis.menuCategory = "From nsis"

        val yaml = renderWindows(distributions)

        assertTrue(yaml, yaml.contains("menuCategory: \"From nsis\""))
        assertFalse(yaml, yaml.contains("From menuGroup"))
    }

    @Test
    fun `nsis-web gets the same options`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "Acme Apps"
        distributions.windows.nsis.shortcutName = "Acme"

        val yaml = renderWindows(distributions, TargetFormat.NsisWeb)

        assertTrue(yaml, yaml.contains("nsisWeb:"))
        assertTrue(yaml, yaml.contains("menuCategory: \"Acme Apps\""))
        assertTrue(yaml, yaml.contains("shortcutName: \"Acme\""))
    }
}

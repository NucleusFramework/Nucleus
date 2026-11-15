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
 * `fileAssociation(...)` reached every Windows format but AppX (#922), so a Store install could
 * never open its own documents. electron-builder writes the `windows.fileTypeAssociation`
 * manifest extensions itself once the associations are in the config.
 */
class ElectronBuilderAppXFileAssociationsConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)
            .also {
                it.appName = "Demo"
                it.packageName = "Demo"
            }

    private fun render(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
    ): String =
        ElectronBuilderConfigGenerator().generateConfig(
            distributions = distributions,
            targetFormat = targetFormat,
            appImageDir = File("."),
            targetArch = Arch.X64,
            executableName = "demo",
        )

    @Test
    fun `appx gets the windows file associations`() {
        val distributions = distributions()
        distributions.fileAssociation("application/pdf", "pdf", "PDF Document")

        val yaml = render(distributions, TargetFormat.AppX)

        assertTrue(yaml, yaml.contains("fileAssociations:"))
        assertTrue(yaml, yaml.contains("  - ext: \"pdf\""))
        assertTrue(yaml, yaml.contains("    description: \"PDF Document\""))
    }

    @Test
    fun `appx lowercases the extension the manifest uses as a name`() {
        val distributions = distributions()
        distributions.windows.fileAssociation("application/x-demo", ".DemoDoc", "Demo document")

        val appx = render(distributions, TargetFormat.AppX)
        val msi = render(distributions, TargetFormat.Msi)

        assertTrue(appx, appx.contains("  - ext: \"demodoc\""))
        assertTrue(msi, msi.contains("  - ext: \"DemoDoc\""))
    }

    @Test
    fun `appx without association writes no block`() {
        assertFalse(render(distributions(), TargetFormat.AppX).contains("fileAssociations:"))
    }
}

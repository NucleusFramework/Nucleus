package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppImages built on electron-builder's legacy toolset embed the old runtime, which links the
 * system's libfuse2 and fails to start where it is not installed (Ubuntu 22.04+, Fedora 36+ by
 * default). The generated config must select the static type2-runtime toolset.
 */
class ElectronBuilderAppImageConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)
            .also {
                it.appName = "AppImageDemo"
                it.packageName = "AppImageDemo"
            }

    private fun render(targetFormat: TargetFormat): String =
        ElectronBuilderConfigGenerator().generateConfig(
            distributions = distributions(),
            targetFormat = targetFormat,
            appImageDir = java.io.File("."),
            targetArch = Arch.X64,
            executableName = "appimagedemo",
        )

    @Test
    fun `appimage config selects the static runtime toolset`() {
        val yaml = render(TargetFormat.AppImage)

        assertTrue(yaml, yaml.contains("toolsets:\n"))
        assertTrue(yaml, yaml.contains("\n  appimage: \"$APPIMAGE_TOOLSET_VERSION\"\n"))
        assertEquals(yaml, 1, Regex("^toolsets:", RegexOption.MULTILINE).findAll(yaml).count())
    }

    @Test
    fun `other formats leave the appimage toolset unset`() {
        for (format in listOf(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Nsis, TargetFormat.Dmg)) {
            val yaml = render(format)

            assertFalse(yaml, yaml.contains("appimage:"))
        }
    }
}

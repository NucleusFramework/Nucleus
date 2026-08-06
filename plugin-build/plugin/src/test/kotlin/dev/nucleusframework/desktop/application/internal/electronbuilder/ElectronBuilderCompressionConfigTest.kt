package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check that the electron-builder YAML emits the effective compression for the
 * format under build (AppImage override, portable override, global fallback).
 *
 * Full packaging is too heavy for unit tests; this validates the config that electron-builder
 * actually receives.
 */
class ElectronBuilderCompressionConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)
            .also {
                it.appName = "CompressionDemo"
                it.packageName = "CompressionDemo"
            }

    private fun render(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
    ): String {
        // generateConfig branches on currentOS for platform blocks, but top-level `compression`
        // is always written. Use formats that match the host when possible; AppImage/Portable
        // still get the shared compression line either way.
        return ElectronBuilderConfigGenerator().generateConfig(
            distributions = distributions,
            targetFormat = targetFormat,
            appImageDir = java.io.File("."),
            targetArch = Arch.X64,
            executableName = "compressiondemo",
        )
    }

    @Test
    fun `appImage yaml uses format override not global Ultra`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.linux.appImage.compressionLevel = CompressionLevel.Normal

        val yaml = render(dist, TargetFormat.AppImage)

        assertTrue(yaml, yaml.contains("compression: \"normal\""))
        assertFalse(yaml, yaml.contains("compression: \"maximum\""))
    }

    @Test
    fun `portable yaml uses format override not global Ultra`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.windows.portable.compressionLevel = CompressionLevel.Store

        val yaml = render(dist, TargetFormat.Portable)

        assertTrue(yaml, yaml.contains("compression: \"store\""))
        assertFalse(yaml, yaml.contains("compression: \"maximum\""))
    }

    @Test
    fun `deb yaml keeps global Ultra when only appImage is overridden`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.linux.appImage.compressionLevel = CompressionLevel.Normal

        val yaml = render(dist, TargetFormat.Deb)

        assertTrue(yaml, yaml.contains("compression: \"maximum\""))
    }

    @Test
    fun `nsis yaml keeps global Ultra when only portable is overridden`() {
        val dist = distributions()
        dist.compressionLevel = CompressionLevel.Ultra
        dist.windows.portable.compressionLevel = CompressionLevel.Normal

        val yaml = render(dist, TargetFormat.Nsis)

        assertTrue(yaml, yaml.contains("compression: \"maximum\""))
    }

    @Test
    fun `no compression line when unset`() {
        val yaml = render(distributions(), TargetFormat.AppImage)
        assertFalse(yaml, yaml.lines().any { it.startsWith("compression:") })
    }
}

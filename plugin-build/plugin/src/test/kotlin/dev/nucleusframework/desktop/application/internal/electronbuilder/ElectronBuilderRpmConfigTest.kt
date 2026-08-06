package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for issue #251: fpm-generated RPMs omit `%dir` entries for the app's own
 * directory tree, so the jpackage launcher — which discovers the app/runtime dirs by scanning
 * `rpm -ql` for paths ending in /app and /runtime — cannot find its .cfg and fails on Fedora/RHEL.
 * The generated RPM config must pass `--rpm-auto-add-directories` to fpm so it owns those dirs.
 */
class ElectronBuilderRpmConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    private fun renderLinux(
        distributions: JvmApplicationDistributions,
        targetFormat: TargetFormat,
    ): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateLinuxConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormat = targetFormat,
            targetArch = Arch.X64,
            startupWMClass = null,
            linuxIconOverride = null,
            linuxAfterInstallTemplate = null,
            linuxAfterRemoveTemplate = null,
            executableName = "nucleusdemo",
        )
        return yaml.toString()
    }

    @Test
    fun `rpm config passes --rpm-auto-add-directories to fpm`() {
        val yaml = renderLinux(distributions(), TargetFormat.Rpm)

        assertTrue(yaml, yaml.contains("rpm:"))
        assertTrue(yaml, yaml.contains("fpm:"))
        assertTrue(yaml, yaml.contains("--rpm-auto-add-directories"))
    }

    @Test
    fun `rpm auto-add coexists with rpm depends`() {
        val distributions = distributions()
        distributions.linux.rpmRequires = listOf("libX11")

        val yaml = renderLinux(distributions, TargetFormat.Rpm)

        assertTrue(yaml, yaml.contains("- \"libX11\""))
        assertTrue(yaml, yaml.contains("--rpm-auto-add-directories"))
    }

    @Test
    fun `deb config does not emit the rpm-only fpm flag`() {
        val yaml = renderLinux(distributions(), TargetFormat.Deb)

        assertTrue(yaml, yaml.contains("deb:"))
        assertFalse(yaml, yaml.contains("--rpm-auto-add-directories"))
    }
}

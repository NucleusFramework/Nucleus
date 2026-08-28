package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.LinuxSystemJava
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
        systemJava: LinuxSystemJava? = null,
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
            systemJava = systemJava,
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

    @Test
    fun `systemJava injects a deb Depends and keeps user extras`() {
        val distributions = distributions()
        distributions.linux.debDepends = listOf("libgtk-3-0")

        val yaml = renderLinux(distributions, TargetFormat.Deb, LinuxSystemJava.Java21)

        assertTrue(yaml, yaml.contains("- \"java21-runtime | java-runtime (>= 21)\""))
        assertTrue(yaml, yaml.contains("- \"libgtk-3-0\""))
    }

    @Test
    fun `systemJava injects an rpm Requires boolean dep`() {
        val yaml = renderLinux(distributions(), TargetFormat.Rpm, LinuxSystemJava.Java21)

        assertTrue(yaml, yaml.contains("- \"(java-21-openjdk or java-25-openjdk)\""))
        assertTrue(yaml, yaml.contains("--rpm-auto-add-directories"))
    }

    @Test
    fun `systemJava is not added to AppImage config`() {
        val yaml = renderLinux(distributions(), TargetFormat.AppImage, LinuxSystemJava.Java21)

        assertFalse(yaml, yaml.contains("java21-runtime"))
        assertFalse(yaml, yaml.contains("java-21-openjdk"))
    }
}

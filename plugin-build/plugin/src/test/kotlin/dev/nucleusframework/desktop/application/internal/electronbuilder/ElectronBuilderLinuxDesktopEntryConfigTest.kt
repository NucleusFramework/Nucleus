package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * electron-builder recomputes the desktop entry's `Comment` (from linux.description) and
 * `Categories` (from linux.category) after merging linux.desktop.entry, so explicit
 * `desktopEntries` values for those keys must be routed to those options to survive.
 */
class ElectronBuilderLinuxDesktopEntryConfigTest {
    private fun render(configure: JvmApplicationDistributions.() -> Unit): String {
        val distributions =
            ProjectBuilder
                .builder()
                .build()
                .objects
                .newInstance(JvmApplicationDistributions::class.java)
                .also {
                    it.appName = "Zayit"
                    it.packageName = "zayit"
                    it.description = "Package description"
                    it.configure()
                }
        return ElectronBuilderConfigGenerator().generateConfig(
            distributions = distributions,
            targetFormat = TargetFormat.Deb,
            appImageDir = java.io.File("."),
            targetArch = Arch.X64,
            executableName = "zayit",
        )
    }

    @Test
    fun `localized entries are passed through`() {
        val yaml =
            render {
                linux.appImage.desktopEntries =
                    mapOf("Name" to "Zayit", "Name[he]" to "Hebrew name", "Comment[he]" to "Hebrew comment")
            }

        assertTrue(yaml, yaml.contains("\n      \"Name\": \"Zayit\"\n"))
        assertTrue(yaml, yaml.contains("\n      \"Name[he]\": \"Hebrew name\"\n"))
        assertTrue(yaml, yaml.contains("\n      \"Comment[he]\": \"Hebrew comment\"\n"))
    }

    @Test
    fun `comment entry becomes the linux description`() {
        val yaml = render { linux.appImage.desktopEntries = mapOf("Comment" to "Desktop comment") }

        assertTrue(yaml, yaml.contains("\n  description: \"Desktop comment\"\n"))
        assertFalse(yaml, yaml.contains("Package description"))
        assertFalse(yaml, yaml.contains("\"Comment\":"))
    }

    @Test
    fun `description is used when no comment entry is set`() {
        val yaml = render { }

        assertTrue(yaml, yaml.contains("\n  description: \"Package description\"\n"))
    }

    @Test
    fun `categories entry wins over appCategory`() {
        val yaml =
            render {
                linux.appCategory = "Utility"
                linux.appImage.desktopEntries = mapOf("Categories" to "Education;X-Kodesh")
            }

        assertTrue(yaml, yaml.contains("\n  category: \"Education;X-Kodesh\"\n"))
        assertFalse(yaml, yaml.contains("\"Categories\":"))
    }

    @Test
    fun `linux menuGroup is the fallback category`() {
        assertTrue(render { linux.menuGroup = "Education" }.contains("\n  category: \"Education\"\n"))

        val yaml =
            render {
                linux.menuGroup = "Education"
                linux.appCategory = "Office"
            }
        assertTrue(yaml, yaml.contains("\n  category: \"Office\"\n"))
    }
}

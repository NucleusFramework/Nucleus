package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.InfoPlistSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Localized `Info.plist` values land in `Contents/Resources/<lang>.lproj/InfoPlist.strings` (#833). */
class MacInfoPlistLocalizationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `renders escaped strings entries`() {
        val rendered =
            renderInfoPlistStrings(
                mapOf(
                    "NSCameraUsageDescription" to "Scan \"documents\"\nwith C:\\path\ttab",
                    "CFBundleDisplayName" to "Démo",
                ),
            )

        assertEquals(
            "\"NSCameraUsageDescription\" = \"Scan \\\"documents\\\"\\nwith C:\\\\path\\ttab\";\n" +
                "\"CFBundleDisplayName\" = \"Démo\";\n",
            rendered,
        )
    }

    @Test
    fun `dsl merges repeated languages and rejects invalid ones`() {
        val settings = InfoPlistSettings()
        settings.localization("pt-BR", mapOf("A" to "1"))
        settings.localization("pt-BR", mapOf("B" to "2", "A" to "3"))

        assertEquals(mapOf("pt-BR" to mapOf("A" to "3", "B" to "2")), settings.localizedStrings)
        assertThrows(IllegalArgumentException::class.java) { settings.localization("../de", emptyMap()) }
    }

    @Test
    fun `copies lproj directories and writes declared languages`() {
        val dir = tmp.newFolder("localizations")
        dir.resolve("fr.lproj/InfoPlist.strings").write("\"K\" = \"fr\";\n")
        dir.resolve(".DS_Store").writeText("")
        val resources = tmp.newFolder("Resources")

        installInfoPlistLocalizations(resources, dir, mapOf("de" to "\"K\" = \"de\";\n"))

        assertEquals("\"K\" = \"fr\";\n", resources.resolve("fr.lproj/InfoPlist.strings").readText())
        assertEquals("\"K\" = \"de\";\n", resources.resolve("de.lproj/InfoPlist.strings").readText())
        assertFalse(resources.resolve(".DS_Store").exists())
    }

    @Test
    fun `rejects non lproj entries and duplicated languages`() {
        val resources = tmp.newFolder("Resources")
        val stray = tmp.newFolder("stray").apply { resolve("de.strings").write("") }
        assertThrows(IllegalStateException::class.java) { installInfoPlistLocalizations(resources, stray, emptyMap()) }

        val dup = tmp.newFolder("dup").apply { resolve("de.lproj/InfoPlist.strings").write("") }
        assertThrows(IllegalStateException::class.java) {
            installInfoPlistLocalizations(resources, dup, mapOf("de" to ""))
        }
        assertThrows(IllegalStateException::class.java) {
            installInfoPlistLocalizations(resources, File(tmp.root, "missing"), emptyMap())
        }
    }

    @Test
    fun `nothing configured leaves resources untouched`() {
        val resources = tmp.newFolder("Resources")
        installInfoPlistLocalizations(resources, null, emptyMap())
        assertTrue(resources.list().orEmpty().isEmpty())
    }

    private fun File.write(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }
}

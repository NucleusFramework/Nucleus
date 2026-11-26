package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.FileAssociation
import dev.nucleusframework.desktop.application.dsl.MacOSDocumentRole
import dev.nucleusframework.desktop.application.dsl.MacOSHandlerRank
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CFBundleDocumentTypes` always carries `LSHandlerRank` and a configurable role (#881, ITMS-90788). */
class MacDocumentTypesTest {
    @Test
    fun `defaults to Editor role and Default rank`() {
        val types = plistDocumentTypes(setOf(association("txt")), emptyMap(), "App.icns")

        assertEquals(1, types.size)
        assertEquals("Editor", types.single().string(PlistKeys.CFBundleTypeRole))
        assertEquals("Default", types.single().string(PlistKeys.LSHandlerRank))
    }

    @Test
    fun `writes the declared role and rank`() {
        val types =
            plistDocumentTypes(
                setOf(association("png", MacOSDocumentRole.Viewer, MacOSHandlerRank.Alternate)),
                emptyMap(),
                "App.icns",
            )

        assertEquals("Viewer", types.single().string(PlistKeys.CFBundleTypeRole))
        assertEquals("Alternate", types.single().string(PlistKeys.LSHandlerRank))
    }

    @Test
    fun `splits a MIME group whose extensions disagree on role or rank`() {
        val types =
            plistDocumentTypes(
                setOf(
                    association("a"),
                    association("b"),
                    association("c", rank = MacOSHandlerRank.Owner),
                ),
                emptyMap(),
                "App.icns",
            )

        assertEquals(listOf("Default", "Owner"), types.map { it.string(PlistKeys.LSHandlerRank) })
        assertEquals(
            InfoPlistListValue(InfoPlistStringValue("a"), InfoPlistStringValue("b")),
            types.first().elements[PlistKeys.CFBundleTypeExtensions],
        )
    }

    @Test
    fun `renders LSHandlerRank into the plist entry`() {
        val xml =
            plistDocumentTypes(setOf(association("txt", rank = MacOSHandlerRank.Owner)), emptyMap(), "App.icns")
                .single()
                .asPlistEntry(0)

        assertTrue(xml, "<key>LSHandlerRank</key>\n  <string>Owner</string>" in xml)
    }

    private fun association(
        extension: String,
        role: MacOSDocumentRole = MacOSDocumentRole.Editor,
        rank: MacOSHandlerRank = MacOSHandlerRank.Default,
    ) = FileAssociation("text/plain", extension, "Text", null, role, rank)

    private fun InfoPlistMapValue.string(key: InfoPlistKey): String? = (elements[key] as? InfoPlistStringValue)?.value
}

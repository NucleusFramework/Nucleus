package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `appLinks(…)` hosts become macOS Universal Links (Associated Domains). */
class MacAppLinksTest {
    private fun decodedProfile(entitlements: String) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>AppIDName</key>
            <string>Demo</string>
            <key>Entitlements</key>
            <dict>
                $entitlements
            </dict>
            <key>Name</key>
            <string>Demo Developer ID</string>
        </dict>
        </plist>
        """.trimIndent()

    @Test
    fun `a Developer ID profile with Associated Domains grants them`() {
        val grant =
            MacAppLinks.parseProfile(
                decodedProfile(
                    """
                    <key>com.apple.application-identifier</key>
                    <string>TEAMID1234.com.example.app</string>
                    <key>keychain-access-groups</key>
                    <array><string>TEAMID1234.*</string></array>
                    <key>com.apple.developer.associated-domains</key>
                    <string>*</string>
                    <key>com.apple.developer.team-identifier</key>
                    <string>TEAMID1234</string>
                    """,
                ),
            )

        assertEquals(
            MacAppLinks.ProfileGrant(
                associatedDomains = true,
                applicationIdentifier = "TEAMID1234.com.example.app",
                teamIdentifier = "TEAMID1234",
            ),
            grant,
        )
    }

    @Test
    fun `a profile without the capability does not grant it`() {
        val grant =
            MacAppLinks.parseProfile(
                decodedProfile(
                    """
                    <key>com.apple.application-identifier</key>
                    <string>TEAMID1234.com.example.app</string>
                    """,
                ),
            )

        assertFalse(grant.associatedDomains)
    }

    @Test
    fun `entitlements keep their keys and gain applinks and identifiers`() {
        val base =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>com.apple.security.cs.allow-jit</key>
                <true/>
            </dict>
            </plist>
            """.trimIndent()

        val xml =
            MacAppLinks.entitlementsXml(
                base,
                listOf("example.com", "*.example.com"),
                applicationIdentifier = "TEAMID1234.com.example.app",
                teamIdentifier = "TEAMID1234",
            )

        assertTrue(xml, xml.contains("<key>com.apple.security.cs.allow-jit</key>"))
        assertTrue(xml, xml.contains("<key>com.apple.developer.associated-domains</key>"))
        assertTrue(xml, xml.contains("<string>applinks:example.com</string>"))
        assertTrue(xml, xml.contains("<string>applinks:*.example.com</string>"))
        assertTrue(xml, xml.contains("<string>TEAMID1234.com.example.app</string>"))
        assertTrue(xml, xml.contains("<key>com.apple.developer.team-identifier</key>"))
        assertTrue(xml, xml.trimEnd().endsWith("</dict>\n</plist>"))
    }

    @Test
    fun `identifiers already in the entitlements are not duplicated`() {
        val base =
            """
            <plist version="1.0">
            <dict>
                <key>com.apple.application-identifier</key>
                <string>TEAMID1234.com.example.app</string>
            </dict>
            </plist>
            """.trimIndent()

        val xml = MacAppLinks.entitlementsXml(base, listOf("example.com"), "TEAMID1234.com.example.app", null)

        assertEquals(1, Regex("com.apple.application-identifier").findAll(xml).count())
    }

    @Test
    fun `no base entitlements yields a standalone plist`() {
        val xml = MacAppLinks.entitlementsXml(null, listOf("example.com"), null, null)

        assertTrue(xml, xml.contains("<plist version=\"1.0\">"))
        assertTrue(xml, xml.contains("<string>applinks:example.com</string>"))
    }
}

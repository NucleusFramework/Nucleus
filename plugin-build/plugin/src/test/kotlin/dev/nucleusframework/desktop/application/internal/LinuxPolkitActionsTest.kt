package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.PolkitActionDefinition
import dev.nucleusframework.desktop.application.dsl.PolkitActionSettings
import dev.nucleusframework.desktop.application.dsl.PolkitAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LinuxPolkitActionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun actions(vararg ids: String): List<PolkitActionDefinition> {
        val settings = PolkitActionSettings()
        ids.forEach { id -> settings.action(id) { it.message = "Unlock $id" } }
        return settings.actions
    }

    @Test
    fun `defaults ask an active user for their own password and deny everyone else`() {
        val xml = LinuxPolkitActions.policyXml(actions("com.example.unlock").single())
        assertTrue(xml.contains("<allow_active>auth_self</allow_active>"))
        assertTrue(xml.contains("<allow_inactive>no</allow_inactive>"))
        assertTrue(xml.contains("<allow_any>no</allow_any>"))
        assertTrue(
            "description falls back to the message",
            xml.contains("<description><![CDATA[Unlock com.example.unlock]]></description>"),
        )
    }

    @Test
    fun `texts are one CDATA without entities, so polkitd reads them whole`() {
        // polkitd keeps the last chunk expat hands it, and expat splits at every entity:
        // an escaped `A &amp; B` was installed and read back by pkaction as ` B`.
        val settings = PolkitActionSettings()
        settings.action("com.example.unlock") { it.message = "Déverrouiller A & <B> pour \$5" }
        val xml = LinuxPolkitActions.policyXml(settings.actions.single())
        assertTrue(xml.contains("<message><![CDATA[Déverrouiller A & <B> pour \$5]]></message>"))
    }

    @Test
    fun `the DSL rejects unusable actions when they are declared`() {
        val settings = PolkitActionSettings()
        assertThrows(IllegalArgumentException::class.java) { settings.action("../evil") { it.message = "x" } }
        assertThrows(IllegalArgumentException::class.java) { settings.action("a b") { it.message = "x" } }
        assertThrows(IllegalArgumentException::class.java) { settings.action("com.example.none") { } }
        assertThrows(IllegalArgumentException::class.java) { settings.action("com.example.n") { it.message = "a\nb" } }
        assertThrows(IllegalArgumentException::class.java) { settings.action("com.example.c") { it.message = "a]]>b" } }
        settings.action("com.example.once") { it.message = "x" }
        assertThrows(IllegalArgumentException::class.java) { settings.action("com.example.once") { it.message = "y" } }
    }

    @Test
    fun `user text never reaches electron-builder's macro pass`() {
        val settings = PolkitActionSettings()
        settings.action("com.example.unlock") { it.message = "Unlock \${executable}" }
        val script =
            LinuxUpdateHelper.composeAfterInstallScript("#!/bin/bash", silentUpdate = false, null, settings.actions) +
                LinuxUpdateHelper.composeAfterRemoveScript(silentUpdate = false, null, settings.actions)
        assertFalse("the text lives in the payload, not in a script", script.contains("Unlock"))
        // FpmTarget.writeConfigFile replaces every match, and fails on an unknown name.
        val macros = Regex("""\$\{([a-zA-Z]+)}""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("sanitizedProductName"), macros)
    }

    @Test
    fun `remove script skips upgrades and is emitted for actions alone`() {
        val script =
            LinuxUpdateHelper.composeAfterRemoveScript(silentUpdate = false, null, actions("com.example.unlock"))
        assertNotNull(script)
        assertTrue(script!!.contains("upgrade|failed-upgrade|[1-9]|[1-9][0-9]) ;;"))
        assertTrue(script.contains("rm -f '/usr/share/polkit-1/actions/com.example.unlock.policy'"))
        assertEquals(null, LinuxUpdateHelper.composeAfterRemoveScript(silentUpdate = false, null, emptyList()))
    }

    @Test
    fun `install and remove scripts copy then delete the payload policies`() {
        assumeTrue(File("/bin/bash").canExecute())
        val settings = PolkitActionSettings()
        val message = "Unlock \"Ex'ample\" & <co> for \$5 `whoami` \$(id)"
        settings.action("com.example.unlock") {
            it.message = message
            it.allowActive = PolkitAuthorization.AuthAdminKeep
        }
        settings.action("com.example.other") { it.message = "Other" }
        val appDir = tmp.newFolder("opt-app")
        val root = tmp.newFolder("actions")
        LinuxPolkitActions.writePolicies(appDir, settings.actions)

        fun run(
            script: String,
            vararg args: String,
        ) {
            val resolved =
                script
                    .replace("/opt/\${sanitizedProductName}", appDir.path)
                    .replace(LinuxPolkitActions.ACTIONS_DIR, root.path)
            val file = tmp.newFile().apply { writeText(resolved) }
            val process = ProcessBuilder("/bin/bash", file.path, *args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }

        run(LinuxPolkitActions.afterInstallFragment(settings.actions))
        val policy = root.resolve("com.example.unlock.policy")
        val factory = DocumentBuilderFactory.newInstance()
        // The DOCTYPE names polkit's DTD by URL: never fetch it.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(policy)
        assertEquals(message, document.getElementsByTagName("message").item(0).textContent)
        assertEquals("auth_admin_keep", document.getElementsByTagName("allow_active").item(0).textContent)
        assertTrue(root.resolve("com.example.other.policy").isFile)

        val remove = LinuxUpdateHelper.composeAfterRemoveScript(silentUpdate = false, null, settings.actions)!!
        run(remove, "upgrade") // dpkg upgrade
        run(remove, "1") // RPM upgrade, after the new package's %post
        assertTrue("an upgrade keeps the policies", policy.isFile)
        run(remove, "0") // RPM erase
        assertFalse(policy.exists())
        assertFalse(root.resolve("com.example.other.policy").exists())
    }
}

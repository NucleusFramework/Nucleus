package dev.nucleusframework.lab.core

import dev.nucleusframework.lab.core.commands.LabCommands
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabCommandsTest {
    @Test
    fun `parses probe id and decoded parameters`() {
        val (id, params) = LabCommands.parse(URI("nucleus-lab://probe/shell.badge?count=5&label=a%20b"))!!
        assertEquals(ProbeId("shell.badge"), id)
        assertEquals(mapOf("count" to "5", "label" to "a b"), params)
    }

    @Test
    fun `a link without parameters has none`() {
        assertEquals(emptyMap(), LabCommands.parse(URI("nucleus-lab://probe/system.appearance"))!!.second)
    }

    @Test
    fun `foreign schemes and hosts are not commands`() {
        assertNull(LabCommands.parse(URI("https://probe/shell.badge")))
        assertNull(LabCommands.parse(URI("nucleus-lab://other/shell.badge")))
        assertNull(LabCommands.parse(URI("nucleus-lab://probe/")))
    }

    @Test
    fun `deep link round-trips through parse`() {
        val link = LabCommands.deepLink(ProbeId("updater.engine"), mapOf("scenario" to "update"))
        assertEquals(ProbeId("updater.engine") to mapOf("scenario" to "update"), LabCommands.parse(URI(link)))
    }
}

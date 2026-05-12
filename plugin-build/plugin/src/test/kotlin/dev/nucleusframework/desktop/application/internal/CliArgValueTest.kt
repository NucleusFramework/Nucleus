package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class CliArgValueTest {
    @Test
    fun `value cli arg emits name and quoted value`() {
        val args = mutableListOf<String>()

        args.cliArg("--name", "Nucleus")

        assertEquals(listOf("--name", "\"Nucleus\""), args)
    }
}

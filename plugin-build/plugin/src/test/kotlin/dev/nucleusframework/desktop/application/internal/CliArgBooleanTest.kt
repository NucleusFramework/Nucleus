package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class CliArgBooleanTest {
    @Test
    fun `boolean cli arg only emits flag when true`() {
        val args = mutableListOf<String>()

        args.cliArg("--verbose", true)
        args.cliArg("--quiet", false)

        assertEquals(listOf("--verbose"), args)
    }
}

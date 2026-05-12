package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaOptionTest {
    @Test
    fun `java option is wrapped as java-options cli argument`() {
        val args = mutableListOf<String>()

        args.javaOption("-Dapp.mode=test")

        assertEquals(listOf("--java-options", "\"'-Dapp.mode=test'\""), args)
    }
}

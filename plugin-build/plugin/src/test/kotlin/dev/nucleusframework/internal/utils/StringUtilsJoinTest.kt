package dev.nucleusframework.internal.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StringUtilsJoinTest {
    @Test
    fun `dash lowercase join ignores empty parts`() {
        assertEquals("nucleus-plugin", joinDashLowercaseNonEmpty("Nucleus", "", "Plugin"))
    }

    @Test
    fun `camel case joins preserve expected first letter casing`() {
        assertEquals("nucleusPlugin", joinLowerCamelCase("Nucleus", "Plugin"))
        assertEquals("NucleusPlugin", joinUpperCamelCase("nucleus", "Plugin"))
    }
}

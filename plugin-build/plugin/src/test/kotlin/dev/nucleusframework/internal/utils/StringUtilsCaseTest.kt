package dev.nucleusframework.internal.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StringUtilsCaseTest {
    @Test
    fun `first character case helpers only transform first character`() {
        assertEquals("Hello", "hello".uppercaseFirstChar())
        assertEquals("hello", "Hello".lowercaseFirstChar())
        assertEquals("", "".uppercaseFirstChar())
    }
}

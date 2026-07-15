package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacDmgLzmaTest {
    @Test
    fun `null or blank minimumSystemVersion is treated as modern`() {
        assertTrue(MacDmgLzma.isUlmoCompatible(null))
        assertTrue(MacDmgLzma.isUlmoCompatible(""))
        assertTrue(MacDmgLzma.isUlmoCompatible("   "))
    }

    @Test
    fun `macOS 10_15 and later support ULMO`() {
        assertTrue(MacDmgLzma.isUlmoCompatible("10.15"))
        assertTrue(MacDmgLzma.isUlmoCompatible("10.15.7"))
        assertTrue(MacDmgLzma.isUlmoCompatible("11"))
        assertTrue(MacDmgLzma.isUlmoCompatible("12.0"))
        assertTrue(MacDmgLzma.isUlmoCompatible("26.0"))
    }

    @Test
    fun `macOS before 10_15 does not support ULMO`() {
        assertFalse(MacDmgLzma.isUlmoCompatible("10.14"))
        assertFalse(MacDmgLzma.isUlmoCompatible("10.13.6"))
        assertFalse(MacDmgLzma.isUlmoCompatible("10.9"))
    }

    @Test
    fun `unparseable minimumSystemVersion is treated as modern rather than blocking`() {
        assertTrue(MacDmgLzma.isUlmoCompatible("latest"))
    }
}

package dev.nucleusframework.spellcheck

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpellCheckerLocaleTest {
    @AfterTest
    fun reset() {
        SpellChecker.resetForTests()
    }

    @Test
    fun `locale defaults to the JVM default`() {
        assertEquals(Locale.getDefault(), SpellChecker.locale)
    }

    @Test
    fun `locale override is a plain Locale`() {
        SpellChecker.locale = Locale.FRANCE
        assertEquals(Locale.FRANCE, SpellChecker.locale)
        SpellChecker.locale = Locale.forLanguageTag("de-DE")
        assertEquals(Locale.GERMANY, SpellChecker.locale)
    }

    @Test
    fun `ensureSession uses the configured locale`() {
        SpellChecker.locale = Locale.FRANCE
        val session = SpellChecker.ensureSession()
        assertEquals(Locale.FRANCE, session.locale)
        assertSame(session, SpellChecker.ensureSession())
    }

    @Test
    fun `ensureSession for another locale does not replace the default`() {
        SpellChecker.locale = Locale.US
        val process = SpellChecker.ensureSession()
        val french = SpellChecker.ensureSession(Locale.FRANCE)
        assertEquals(Locale.US, process.locale)
        assertEquals(Locale.FRANCE, french.locale)
        assertSame(process, SpellChecker.ensureSession())
        assertSame(french, SpellChecker.ensureSession(Locale.FRANCE))
    }

    @Test
    fun `changing locale replaces the process-wide session`() {
        SpellChecker.locale = Locale.US
        val first = SpellChecker.ensureSession()
        SpellChecker.locale = Locale.FRANCE
        val second = SpellChecker.ensureSession()
        assertEquals(Locale.FRANCE, second.locale)
        assertNotEquals(first, second)
    }

    @Test
    fun `session stores the requested locale even when the engine is a no-op`() {
        SpellcheckSession(locale = Locale.FRANCE, osName = "FreeBSD").use { session ->
            assertEquals(Locale.FRANCE, session.locale)
        }
    }

    @Test
    fun `facade is a no-op until a session is loaded`() {
        assertNull(SpellChecker.sessionIfReady)
        assertFalse(SpellChecker.isAvailable)
        assertFalse(SpellChecker.check("hello"))
        assertEquals(emptyList(), SpellChecker.suggest("helo"))
        assertFalse(SpellChecker.addToDictionary("helo"))
        assertEquals(emptyList(), SpellChecker.misspellings("helo world"))
    }

    @Test
    fun `ensureSession populates the process-wide facade`() {
        val session = SpellChecker.ensureSession()
        assertSame(session, SpellChecker.session)
        assertSame(session, SpellChecker.sessionIfReady)
        assertEquals(session.isAvailable, SpellChecker.isAvailable)
        assertEquals(session.locale, SpellChecker.locale)
        if (session.isAvailable) {
            assertTrue(SpellChecker.check("hello"))
            assertFalse(SpellChecker.check("helo"))
            assertTrue(SpellChecker.suggest("helo").isNotEmpty())
            assertEquals(listOf("helo"), SpellChecker.misspellings("helo world").map { it.word })
        } else {
            assertFalse(SpellChecker.check("hello"))
            assertEquals(emptyList(), SpellChecker.suggest("helo"))
        }
    }

    @Test
    fun `setting the same locale keeps the loaded session`() {
        SpellChecker.locale = Locale.UK
        val first = SpellChecker.ensureSession()
        SpellChecker.locale = Locale.UK
        assertSame(first, SpellChecker.sessionIfReady)
        assertSame(first, SpellChecker.ensureSession())
    }

    @Test
    fun `switching locale reuses a previously cached extra session`() {
        SpellChecker.locale = Locale.US
        val us = SpellChecker.ensureSession()
        val french = SpellChecker.ensureSession(Locale.FRANCE)
        SpellChecker.locale = Locale.FRANCE
        assertSame(french, SpellChecker.sessionIfReady)
        assertSame(french, SpellChecker.ensureSession())
        assertFalse(us.isAvailable)
        assertEquals(Locale.FRANCE, SpellChecker.locale)
    }

    @Test
    fun `setting locale to the already-loaded extra is a no-op reload`() {
        SpellChecker.locale = Locale.GERMANY
        SpellChecker.ensureSession()
        val italian = SpellChecker.ensureSession(Locale.ITALY)
        SpellChecker.locale = Locale.ITALY
        assertSame(italian, SpellChecker.ensureSession())
        SpellChecker.locale = Locale.ITALY
        assertSame(italian, SpellChecker.sessionIfReady)
    }
}

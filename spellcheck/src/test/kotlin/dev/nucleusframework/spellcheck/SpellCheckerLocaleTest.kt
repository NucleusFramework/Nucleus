package dev.nucleusframework.spellcheck

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

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
}

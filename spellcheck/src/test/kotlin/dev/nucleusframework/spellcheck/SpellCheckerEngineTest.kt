package dev.nucleusframework.spellcheck

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpellCheckerEngineTest {
    @Test
    fun `engine checks hello and helo against English`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertTrue(session.check("hello"), "hello should be correct")
            assertFalse(session.check("helo"), "helo should be a misspelling")
            val suggestions = session.suggest("helo")
            assertTrue(
                suggestions.any { it.equals("hello", ignoreCase = true) },
                "suggestions for helo should include hello, got $suggestions",
            )
        }
    }

    @Test
    fun `add to dictionary makes helo check as correct`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertFalse(session.check("helo"))
            assertTrue(session.addToDictionary("helo"), "addToDictionary should succeed")
            assertTrue(session.check("helo"), "helo should check as correct after add")
        }
    }

    @Test
    fun `misspelling ranges cover helo only in helo world`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val ranges = session.misspellings("helo world")
            assertTrue(ranges.isNotEmpty(), "expected a misspelling in 'helo world'")
            val helo = ranges.first()
            assertTrue(helo.word == "helo")
            assertTrue(helo.start == 0)
            assertTrue(helo.end == 4)
            assertTrue(ranges.none { it.word == "world" })
        }
    }

    @Test
    fun `word iteration extracts helo from helo world`() {
        val words = iterateWords("helo world")
        assertTrue(words.size == 2, "expected two words, got $words")
        assertTrue(words[0].word == "helo" && words[0].start == 0 && words[0].end == 4)
        assertTrue(words[1].word == "world")
        val at = wordAt("helo world", 2)
        assertNotNull(at)
        assertTrue(at.word == "helo")
    }

    @Test
    fun `empty words are rejected even when the native engine is available`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertFalse(session.check(""))
            assertFalse(session.check("   "))
            assertEquals(emptyList(), session.suggest(""))
            assertFalse(session.addToDictionary(""))
            assertFalse(session.addToDictionary(" \t"))
        }
    }

    @Test
    fun `user dictionary is loaded and persisted`() {
        val file = isolatedUserDict()
        Files.write(file, listOf("customword", "", "  "))
        SpellcheckSession(locale = Locale.US, userDictionaryFile = file).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertTrue(session.check("customword"))
            assertFalse(session.check("xyzzyword"))
            assertTrue(session.addToDictionary("xyzzyword"))
            assertTrue(session.check("xyzzyword"))
            assertTrue(session.addToDictionary("xyzzyword"))
        }
        waitUntil { Files.readAllLines(file).any { it.trim() == "xyzzyword" } }
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.contains("customword"))
        assertEquals(1, lines.count { it == "xyzzyword" })
        SpellcheckSession(locale = Locale.US, userDictionaryFile = file).use { reloaded ->
            assumeTrue(reloaded.isAvailable)
            assertTrue(reloaded.check("xyzzyword"))
            assertTrue(reloaded.check("customword"))
        }
    }

    @Test
    fun `default user dictionary path uses the locale tag`() {
        val us = SpellcheckSession.defaultUserDictionaryFile(Locale.US)
        assertTrue(us.toString().endsWith("en_US.dic"))
        assertTrue(us.toString().contains("nucleus"))
        assertTrue(us.toString().contains("spellcheck"))
        val french = SpellcheckSession.defaultUserDictionaryFile(Locale.FRANCE)
        assertTrue(french.fileName.toString() == "fr_FR.dic")
        val posix = SpellcheckSession.defaultUserDictionaryFile(Locale.ROOT)
        assertTrue(posix.fileName.toString() == "en_US.dic")
    }

    @Test
    fun `close disables the session and blank words stay rejected`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertFalse(session.check(""))
            assertFalse(session.check("   "))
            assertTrue(session.suggest("").isEmpty())
            assertFalse(session.addToDictionary(""))
            val curly = "it\u2019s"
            session.check(curly)
            session.close()
            assertFalse(session.isAvailable)
            assertFalse(session.check("hello"))
            assertTrue(session.suggest("helo").isEmpty())
            assertFalse(session.addToDictionary("helo"))
            assertTrue(session.misspellings("helo").isEmpty())
        }
    }

    @Test
    fun `null user dictionary file does not throw`() {
        SpellcheckSession(locale = Locale.US, userDictionaryFile = null).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertTrue(session.check("hello"))
            val added = session.addToDictionary("helo")
            assertTrue(added)
            assertTrue(session.check("helo"))
        }
    }

    private fun isolatedUserDict() = Files.createTempFile("nucleus-spellcheck-", "-${UUID.randomUUID()}.dic")

    private fun waitUntil(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out waiting for user dictionary persist")
    }
}

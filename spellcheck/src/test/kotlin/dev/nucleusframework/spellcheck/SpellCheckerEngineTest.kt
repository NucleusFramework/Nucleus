package dev.nucleusframework.spellcheck

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpellCheckerEngineTest {
    @Test
    fun `linux hunspell checks hello and helo against en_US`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Hunspell + en_US dictionary required", session.isAvailable)
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
            assumeTrue("Hunspell + en_US dictionary required", session.isAvailable)
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
            assumeTrue("Hunspell + en_US dictionary required", session.isAvailable)
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

    private fun isolatedUserDict() = Files.createTempFile("nucleus-spellcheck-", "-${UUID.randomUUID()}.dic")
}

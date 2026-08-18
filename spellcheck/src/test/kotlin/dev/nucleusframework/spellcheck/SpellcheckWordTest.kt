package dev.nucleusframework.spellcheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellcheckWordTest {
    @Test
    fun `iterateWords skips empty text punctuation and digits-only tokens`() {
        assertEquals(emptyList(), iterateWords(""))
        assertEquals(emptyList(), iterateWords("   \t\n"))
        assertEquals(emptyList(), iterateWords("123 456"))
        assertEquals(emptyList(), iterateWords("... -- !!"))
        val words = iterateWords("hello 123 world")
        assertEquals(listOf("hello", "world"), words.map { it.word })
        assertEquals(0, words[0].start)
        assertEquals(5, words[0].end)
        assertEquals(10, words[1].start)
    }

    @Test
    fun `apostrophes join a token and hyphens split`() {
        assertEquals(listOf("don't"), iterateWords("don't").map { it.word })
        assertEquals(listOf("l'eau"), iterateWords("l'eau").map { it.word })
        assertEquals(listOf("it\u2019s"), iterateWords("it\u2019s").map { it.word })
        assertEquals(listOf("dogs"), iterateWords("dogs'").map { it.word })
        assertEquals(listOf("well", "known"), iterateWords("well-known").map { it.word })
        assertEquals(listOf("café"), iterateWords("café").map { it.word })
    }

    @Test
    fun `tokens longer than 64 characters are skipped`() {
        val long = "a".repeat(65)
        val text = "ok $long end"
        val words = iterateWords(text)
        assertEquals(listOf("ok", "end"), words.map { it.word })
        assertNull(wordAt(text, 4))
        assertEquals("ok", wordAt(text, 1)?.word)
    }

    @Test
    fun `letters followed by digits stay one token`() {
        val words = iterateWords("utf8 and mp3s")
        assertEquals(listOf("utf8", "and", "mp3s"), words.map { it.word })
    }

    @Test
    fun `wordAt returns the token that contains the offset`() {
        val text = "helo world"
        assertEquals("helo", wordAt(text, 0)?.word)
        assertEquals("helo", wordAt(text, 3)?.word)
        assertNull(wordAt(text, 4))
        assertEquals("world", wordAt(text, 5)?.word)
        assertEquals("world", wordAt(text, 10)?.word)
        assertNull(wordAt(text, 11))
        assertNull(wordAt(text, -1))
        assertNull(wordAt("", 0))
        assertNull(wordAt("   ", 1))
    }

    @Test
    fun `misspellingRanges keeps only rejected tokens`() {
        val ranges = misspellingRanges("helo world") { word -> word == "world" }
        assertEquals(listOf("helo"), ranges.map { it.word })
        assertEquals(emptyList(), misspellingRanges("hello") { true })
    }

    @Test
    fun `replaceWord rewrites a span and rejects inverted ranges`() {
        assertEquals("hello world", replaceWord("helo world", 0, 4, "hello"))
        assertEquals("helo there", replaceWord("helo world", SpellcheckWord(5, 10, "world"), "there"))
        assertEquals("x", replaceWord("", 0, 0, "x"))
        assertFailsWith<IllegalArgumentException> { replaceWord("ab", -1, 1, "x") }
        assertFailsWith<IllegalArgumentException> { replaceWord("ab", 3, 3, "x") }
        assertFailsWith<IllegalArgumentException> { replaceWord("ab", 2, 1, "x") }
    }
}

package dev.nucleusframework.spellcheck

import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DictionaryLocatorMoreLocalesTest {
    @Test
    fun `locale candidates cover aliases and posix fallback`() {
        assertEquals(listOf("pt_BR", "pt", "pt_PT"), DictionaryLocator.localeCandidates(Locale.forLanguageTag("pt-BR")))
        assertEquals(listOf("pt_PT", "pt", "pt_BR"), DictionaryLocator.localeCandidates(Locale.forLanguageTag("pt-PT")))
        assertEquals(listOf("it_IT", "it"), DictionaryLocator.localeCandidates(Locale.ITALY))
        assertEquals(listOf("nl_NL", "nl"), DictionaryLocator.localeCandidates(Locale.forLanguageTag("nl-NL")))
        assertEquals(listOf("ru_RU", "ru"), DictionaryLocator.localeCandidates(Locale.forLanguageTag("ru-RU")))
        assertEquals(listOf("ja"), DictionaryLocator.localeCandidates(Locale.JAPANESE))
        assertEquals(listOf("en_US", "en_GB", "en"), DictionaryLocator.localeCandidates(Locale.ROOT))
        assertEquals(
            listOf("en_US", "en_GB", "en"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("posix")),
        )
    }

    @Test
    fun `find returns the first matching aff dic pair`() {
        val dir = Files.createTempDirectory("hunspell-kover")
        try {
            Files.writeString(dir.resolve("fr_FR.aff"), "SET UTF-8")
            Files.writeString(dir.resolve("fr_FR.dic"), "1\nbonjour")
            val found = DictionaryLocator.find(Locale.FRANCE, listOf(dir))
            assertNotNull(found)
            assertEquals("fr_FR", found.tag)
            assertEquals(dir.resolve("fr_FR.aff"), found.aff)
            assertEquals(dir.resolve("fr_FR.dic"), found.dic)
            assertNull(DictionaryLocator.find(Locale.JAPAN, listOf(dir)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

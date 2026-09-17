package dev.nucleusframework.spellcheck

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionaryLocatorTest {
    @Test
    fun `default directories include the system Hunspell locations`() {
        val dirs = DictionaryLocator.defaultDirectories()
        assertTrue(dirs.contains(Path.of("/usr/share/hunspell")))
        assertTrue(dirs.contains(Path.of("/usr/share/myspell/dicts")))
        assertTrue(dirs.contains(Path.of("/usr/share/myspell")))
        assertTrue(dirs.contains(Path.of("/usr/local/share/hunspell")))
        assertTrue(dirs.any { it.fileName.toString() == "hunspell" })
        val fromEnv =
            System
                .getenv("DICPATH")
                ?.split(':')
                ?.filter { it.isNotBlank() }
                ?.map { Path.of(it) }
                .orEmpty()
        assertTrue(dirs.take(fromEnv.size) == fromEnv)
    }

    @Test
    fun `locale candidates try exact tag then language then aliases`() {
        assertEquals(listOf("en_US", "en", "en_GB"), DictionaryLocator.localeCandidates(Locale.US))
        assertEquals(listOf("en", "en_US", "en_GB"), DictionaryLocator.localeCandidates(Locale.ENGLISH))
        assertEquals(listOf("en_GB", "en", "en_US"), DictionaryLocator.localeCandidates(Locale.UK))
        assertEquals(listOf("fr_FR", "fr"), DictionaryLocator.localeCandidates(Locale.FRANCE))
        assertEquals(listOf("de_DE", "de"), DictionaryLocator.localeCandidates(Locale.GERMANY))
        assertEquals(
            listOf("es_ES", "es"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("es-ES")),
        )
        assertEquals(
            listOf("pt_BR", "pt", "pt_PT"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("pt-BR")),
        )
        assertEquals(
            listOf("pt_PT", "pt", "pt_BR"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("pt-PT")),
        )
        assertEquals(listOf("it_IT", "it"), DictionaryLocator.localeCandidates(Locale.ITALY))
        assertEquals(
            listOf("nl_NL", "nl"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("nl-NL")),
        )
        assertEquals(
            listOf("ru_RU", "ru"),
            DictionaryLocator.localeCandidates(Locale.forLanguageTag("ru-RU")),
        )
        assertEquals(listOf("ja"), DictionaryLocator.localeCandidates(Locale.JAPANESE))
        assertEquals(listOf("ja_JP", "ja"), DictionaryLocator.localeCandidates(Locale.JAPAN))
    }

    @Test
    fun `posix and empty languages fall back to English tags`() {
        assertEquals(
            listOf("en_US", "en_GB", "en"),
            DictionaryLocator.localeCandidates(Locale.ROOT),
        )
        @Suppress("DEPRECATION")
        assertEquals(
            listOf("en_US", "en_GB", "en"),
            DictionaryLocator.localeCandidates(Locale("c")),
        )
        @Suppress("DEPRECATION")
        assertEquals(
            listOf("en_US", "en_GB", "en"),
            DictionaryLocator.localeCandidates(Locale("posix")),
        )
    }

    @Test
    fun `find prefers the exact locale pair and returns null when missing`() {
        val dir = Files.createTempDirectory("nucleus-spellcheck-dicts-")
        try {
            writePair(dir, "en_US")
            writePair(dir, "en")
            writePair(dir, "fr")
            val us = DictionaryLocator.find(Locale.US, listOf(dir))
            assertNotNull(us)
            assertEquals("en_US", us.tag)
            assertEquals(dir.resolve("en_US.aff"), us.aff)
            assertEquals(dir.resolve("en_US.dic"), us.dic)
            val french = DictionaryLocator.find(Locale.FRANCE, listOf(dir))
            assertNotNull(french)
            assertEquals("fr", french.tag)
            assertNull(DictionaryLocator.find(Locale.GERMANY, listOf(dir)))
            assertNull(DictionaryLocator.find(Locale.US, listOf(dir.resolve("missing"))))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `find walks directories in order and skips incomplete pairs`() {
        val first = Files.createTempDirectory("nucleus-spellcheck-dicts-a-")
        val second = Files.createTempDirectory("nucleus-spellcheck-dicts-b-")
        try {
            Files.writeString(first.resolve("en_US.aff"), "SET UTF-8")
            writePair(second, "en_US")
            val found = DictionaryLocator.find(Locale.US, listOf(first, second))
            assertNotNull(found)
            assertEquals(second.resolve("en_US.dic"), found.dic)
        } finally {
            first.toFile().deleteRecursively()
            second.toFile().deleteRecursively()
        }
    }

    private fun writePair(
        dir: Path,
        tag: String,
    ) {
        Files.writeString(dir.resolve("$tag.aff"), "SET UTF-8")
        Files.writeString(dir.resolve("$tag.dic"), "1\nhello")
    }
}

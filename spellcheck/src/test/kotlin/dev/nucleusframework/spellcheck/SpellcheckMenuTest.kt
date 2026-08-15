package dev.nucleusframework.spellcheck

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellcheckMenuTest {
    @Test
    fun `menu model for helo contains suggestions and add to dictionary`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val model = buildSpellcheckMenuModel("helo world", offset = 1, session = session)
            assertNotNull(model)
            assertEquals("helo", model.word)
            assertTrue(model.suggestions.isNotEmpty(), "expected suggestions, got ${model.suggestions}")
            assertEquals(SpellcheckMenuModel.DEFAULT_ADD_TO_DICTIONARY_LABEL, model.addToDictionaryLabel)
            val applied = applySuggestion("helo world", model, model.suggestions.first())
            assertEquals("${model.suggestions.first()} world", applied)
            assertEquals("hello world", applySuggestion("helo world", model, "hello"))
        }
    }

    @Test
    fun `replaceWord rewrites only the target span`() {
        assertEquals("hello world", replaceWord("helo world", 0, 4, "hello"))
        val word = iterateWords("helo world").first()
        assertEquals("hello world", replaceWord("helo world", word, "hello"))
    }

    @Test
    fun `correct word yields no menu model`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            assertNull(buildSpellcheckMenuModel("hello", session))
        }
    }

    @Test
    fun `noop session yields no menu model`() {
        SpellcheckSession(locale = Locale.US, osName = "FreeBSD").use { session ->
            assertNull(buildSpellcheckMenuModel("helo", session))
        }
    }

    private fun isolatedUserDict() = Files.createTempFile("nucleus-spellcheck-menu-", "-${UUID.randomUUID()}.dic")
}

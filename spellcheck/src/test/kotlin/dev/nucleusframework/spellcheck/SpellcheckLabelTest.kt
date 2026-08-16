package dev.nucleusframework.spellcheck

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class SpellcheckLabelTest {
    @Test
    fun `add to dictionary follows the requested locale`() {
        assertEquals(
            "Add to dictionary",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.US),
        )
        assertEquals(
            "Ajouter au dictionnaire",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.FRANCE),
        )
        assertEquals(
            "Zum Wörterbuch hinzufügen",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.GERMANY),
        )
        assertEquals(
            "Añadir al diccionario",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("es-ES")),
        )
        assertEquals(
            "Agregar al diccionario",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("es-MX")),
        )
        assertEquals(
            "加入字典",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.TAIWAN),
        )
        assertEquals(
            "添加到字典",
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.CHINA),
        )
    }

    @Test
    fun `unknown locale falls back to English`() {
        assertEquals(
            SpellcheckMenuModel.DEFAULT_ADD_TO_DICTIONARY_LABEL,
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("xx")),
        )
    }

    @Test
    fun `legacy language codes map onto the modern tags`() {
        assertEquals(
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("id")),
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("in")),
        )
        assertEquals(
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("he")),
            SpellcheckMenuModel.localizedAddToDictionaryLabel(Locale.forLanguageTag("iw")),
        )
    }
}

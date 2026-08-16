@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.spellcheck

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.nucleusframework.spellcheck.SpellcheckMenuModel
import dev.nucleusframework.spellcheck.SpellcheckSession
import dev.nucleusframework.spellcheck.applySuggestion
import dev.nucleusframework.spellcheck.buildSpellcheckMenuModel
import dev.nucleusframework.spellcheck.iterateWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class SpellcheckInstallerTest {
    @Test
    fun `default separator is inserted around suggestions`() {
        val items =
            spellcheckMenuSections(
                suggestions = listOf(ContextMenuItem("hello") {}),
                addToDictionaryLabel = "Add to dictionary",
                onAddToDictionary = {},
                separator = SpellcheckContextMenuSeparator,
            )
        assertEquals(4, items.size)
        assertTrue(items[0] === SpellcheckContextMenuSeparator)
        assertEquals("hello", items[1].label)
        assertTrue(items[2] === SpellcheckContextMenuSeparator)
        assertEquals("Add to dictionary", items[3].label)
    }

    @Test
    fun `custom separator is inserted around suggestions`() {
        val divider = ContextMenuItem("---") {}
        val items =
            spellcheckMenuSections(
                suggestions = listOf(ContextMenuItem("hello") {}),
                addToDictionaryLabel = "Add to dictionary",
                onAddToDictionary = {},
                separator = divider,
            )
        assertEquals(4, items.size)
        assertTrue(items[0] === divider)
        assertEquals("hello", items[1].label)
        assertTrue(items[2] === divider)
        assertEquals("Add to dictionary", items[3].label)
    }

    @Test
    fun `top placement trails with a separator instead of leading`() {
        val items =
            spellcheckMenuSections(
                suggestions = listOf(ContextMenuItem("hello") {}),
                addToDictionaryLabel = "Add to dictionary",
                onAddToDictionary = {},
                separator = SpellcheckContextMenuSeparator,
                placement = SpellcheckMenuPlacement.Top,
            )
        assertEquals(4, items.size)
        assertEquals("hello", items[0].label)
        assertTrue(items[1] === SpellcheckContextMenuSeparator)
        assertEquals("Add to dictionary", items[2].label)
        assertTrue(items[3] === SpellcheckContextMenuSeparator)
    }

    @Test
    fun `recompute delay is zero after whitespace and idle mid-word`() {
        assertEquals(0L, spellcheckRecomputeDelayMs(""))
        assertEquals(0L, spellcheckRecomputeDelayMs("helo "))
        assertEquals(0L, spellcheckRecomputeDelayMs("helo\n"))
        assertEquals(SPELLCHECK_IDLE_DELAY_MS, spellcheckRecomputeDelayMs("helo"))
    }

    @Test
    fun `stale ranges are dropped when the text no longer matches`() {
        val helo = iterateWords("helo world").first { it.word == "helo" }
        assertTrue(spellcheckRangesStillValid("helo world", listOf(helo)).isNotEmpty())
        assertTrue(spellcheckRangesStillValid("hello world", listOf(helo)).isEmpty())
    }

    @Test
    fun `context menu items use cached ranges and rewrite the word`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            var rewritten: String? = null
            val ranges = session.misspellings("helo world")
            val items =
                spellcheckContextMenuItems(
                    text = "helo world",
                    session = session,
                    ranges = ranges,
                    separator = SpellcheckContextMenuSeparator,
                    onTextChange = { rewritten = it },
                )
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val suggestions =
                items.filter { it !== SpellcheckContextMenuSeparator && it.label != addLabel }
            assertTrue("expected suggestion items", suggestions.isNotEmpty())
            assertTrue("expected Add to dictionary", items.any { it.label == addLabel })
            suggestions.first().onClick()
            val result = rewritten ?: error("suggestion must rewrite the field")
            assertTrue("suggestion must keep world", result.contains("world"))
            assertTrue("helo should be replaced", !result.startsWith("helo"))
        }
    }

    @Test
    fun `menu items for helo include suggestions and add to dictionary`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            var applied: String? = null
            var added = false
            val items =
                NucleusSpellcheckInstaller.menuItems(
                    word = "helo",
                    session = session,
                    onSuggestion = { applied = it },
                    onAddToDictionary = { added = true },
                )
            assertTrue("expected spellcheck menu items", items.isNotEmpty())
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val suggestionItems =
                items.filter { it !== SpellcheckContextMenuSeparator && it.label != addLabel }
            assertTrue("expected suggestion items, got ${items.map { it.label }}", suggestionItems.isNotEmpty())
            assertTrue("expected Add to dictionary", items.any { it.label == addLabel })
            suggestionItems.first().onClick()
            assertEquals(suggestionItems.first().label, applied)
            items.first { it.label == addLabel }.onClick()
            assertTrue(added)
        }
    }

    @Test
    fun `applying a menu suggestion rewrites only that word`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val model = buildSpellcheckMenuModel("helo world", offset = 0, session = session)
            assertNotNull("expected a menu model for helo", model)
            assertEquals("hello world", applySuggestion("helo world", model!!, "hello"))
        }
    }

    @Test
    fun `squiggle helper produces boxes covering the helo span`() {
        val measurer =
            TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = Density(1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        val layout =
            measurer.measure(
                text = "helo world",
                style = TextStyle(fontSize = 16.sp),
                constraints = Constraints(maxWidth = 1000),
            )
        val helo = iterateWords("helo world").first { it.word == "helo" }
        val boxes = boundingBoxesForRange(layout, helo.start, helo.end)
        assertTrue("expected bounding boxes for helo", boxes.isNotEmpty())
        assertTrue("expected non-empty boxes", boxes.all { it.width > 0f && it.height > 0f })
    }

    @Test
    fun `legacy IME zero offset falls back to the inner clipping rect`() {
        val inner = Rect(left = 16f, top = 28f, right = 240f, bottom = 52f)
        assertEquals(
            inner.topLeft,
            resolveSpellcheckTextOriginInRoot(Offset.Zero, inner),
        )
        assertEquals(
            Offset(16f, 20f),
            resolveSpellcheckTextOriginInRoot(Offset(16f, 20f), inner),
        )
        assertEquals(null, resolveSpellcheckTextOriginInRoot(Offset.Zero, Rect.Zero))
        assertEquals(null, resolveSpellcheckTextOriginInRoot(null, null))
    }

    private fun isolatedUserDict() = Files.createTempFile("nucleus-spellcheck-ui-", "-${UUID.randomUUID()}.dic")
}

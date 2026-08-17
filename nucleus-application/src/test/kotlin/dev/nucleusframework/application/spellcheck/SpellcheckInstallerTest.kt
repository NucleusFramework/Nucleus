@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.spellcheck

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.nucleusframework.application.contextmenu.NucleusContextMenuDivider
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
    fun `renderer divider is inserted around suggestions`() {
        val items =
            spellcheckMenuSections(
                suggestions = listOf(ContextMenuItem("hello") {}),
                addToDictionaryLabel = "Add to dictionary",
                onAddToDictionary = {},
                separator = NucleusContextMenuDivider,
            )
        assertEquals(4, items.size)
        assertTrue(items[0] === NucleusContextMenuDivider)
        assertEquals("hello", items[1].label)
        assertTrue(items[2] === NucleusContextMenuDivider)
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
    fun `no separator is emitted when the renderer cannot draw one`() {
        SpellcheckMenuPlacement.entries.forEach { placement ->
            val items =
                spellcheckMenuSections(
                    suggestions = listOf(ContextMenuItem("hello") {}),
                    addToDictionaryLabel = "Add to dictionary",
                    onAddToDictionary = {},
                    separator = null,
                    placement = placement,
                )
            assertEquals("$placement", 2, items.size)
            assertEquals("hello", items[0].label)
            assertEquals("Add to dictionary", items[1].label)
            assertTrue(
                "no stand-in row may be emitted for $placement",
                items.none { it.label.isEmpty() || !it.enabled },
            )
        }
    }

    @Test
    fun `installer emits no separator by default`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val items =
                NucleusSpellcheckInstaller.menuItems(
                    word = "helo",
                    session = session,
                    onSuggestion = {},
                    onAddToDictionary = {},
                )
            assertTrue("expected spellcheck menu items", items.isNotEmpty())
            assertTrue("default must not insert a divider", items.none { it === NucleusContextMenuDivider })
        }
    }

    @Test
    fun `top placement trails with a separator instead of leading`() {
        val items =
            spellcheckMenuSections(
                suggestions = listOf(ContextMenuItem("hello") {}),
                addToDictionaryLabel = "Add to dictionary",
                onAddToDictionary = {},
                separator = NucleusContextMenuDivider,
                placement = SpellcheckMenuPlacement.Top,
            )
        assertEquals(4, items.size)
        assertEquals("hello", items[0].label)
        assertTrue(items[1] === NucleusContextMenuDivider)
        assertEquals("Add to dictionary", items[2].label)
        assertTrue(items[3] === NucleusContextMenuDivider)
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
                    separator = NucleusContextMenuDivider,
                    onTextChange = { rewritten = it },
                    anchor = TextRange(0, 4),
                )
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val suggestions =
                items.filter { it !== NucleusContextMenuDivider && it.label != addLabel }
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
                items.filter { it !== NucleusContextMenuDivider && it.label != addLabel }
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
    fun `menu items only include suggestions for the selected misspelling`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val text = "helo wrold"
            val ranges = session.misspellings(text)
            assumeTrue("expected two misspellings", ranges.size >= 2)
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val heloItems =
                spellcheckContextMenuItems(
                    text = text,
                    session = session,
                    ranges = ranges,
                    separator = NucleusContextMenuDivider,
                    onTextChange = {},
                    anchor = TextRange(0, 4),
                )
            val heloSuggestions =
                heloItems.filter { it !== NucleusContextMenuDivider && it.label != addLabel }
            assertTrue("expected helo suggestions", heloSuggestions.isNotEmpty())
            assertTrue(
                "helo menu must not list the other misspelling",
                heloSuggestions.none { it.label == "wrold" },
            )
            val empty =
                spellcheckContextMenuItems(
                    text = text,
                    session = session,
                    ranges = ranges,
                    separator = NucleusContextMenuDivider,
                    onTextChange = {},
                    anchor = TextRange(4, 5),
                )
            assertTrue("whitespace must not open spellcheck items", empty.isEmpty())
        }
    }

    @Test
    fun `click offset wins over a stale caret selection`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            val text = "helo wrold"
            val ranges = session.misspellings(text)
            assumeTrue("expected two misspellings", ranges.size >= 2)
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val anchor = spellcheckAnchor(clickOffset = 7, selection = TextRange(0, 4))
            val items =
                spellcheckContextMenuItems(
                    text = text,
                    session = session,
                    ranges = ranges,
                    separator = NucleusContextMenuDivider,
                    onTextChange = {},
                    anchor = anchor,
                )
            val suggestions =
                items.filter { it !== NucleusContextMenuDivider && it.label != addLabel }
            assertTrue("expected wrold suggestions", suggestions.isNotEmpty())
            assertTrue(
                "click on wrold must not list the helo token",
                suggestions.none { it.label.equals("helo", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `selection maps onto the misspelling under the caret or highlight`() {
        val helo = iterateWords("helo wrold").first { it.word == "helo" }
        val wrold = iterateWords("helo wrold").first { it.word == "wrold" }
        val ranges = listOf(helo, wrold)
        assertEquals(helo, misspellingAt(ranges, TextRange(0, 4)))
        assertEquals(helo, misspellingAt(ranges, TextRange(2)))
        assertEquals(wrold, misspellingAt(ranges, TextRange(5, 10)))
        assertEquals(null, misspellingAt(ranges, TextRange(4, 5)))
        assertEquals(null, misspellingAt(emptyList(), TextRange(0, 4)))
        assertEquals(TextRange(7), spellcheckAnchor(clickOffset = 7, selection = TextRange(0, 4)))
        assertEquals(TextRange(0, 4), spellcheckAnchor(clickOffset = null, selection = TextRange(0, 4)))
        assertEquals(null, spellcheckAnchor(clickOffset = null, selection = null))
    }

    @Test
    fun `click in layout space maps onto the misspelled span`() {
        val measurer =
            TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = Density(1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        val layout =
            measurer.measure(
                text = "helo wrold",
                style = TextStyle(fontSize = 16.sp),
                constraints = Constraints(maxWidth = 1000),
            )
        val helo = iterateWords("helo wrold").first { it.word == "helo" }
        val wrold = iterateWords("helo wrold").first { it.word == "wrold" }
        val heloBox = boundingBoxesForRange(layout, helo.start, helo.end).first()
        val wroldBox = boundingBoxesForRange(layout, wrold.start, wrold.end).first()
        val heloClick =
            textOffsetAtRoot(
                layout,
                Offset.Zero,
                Offset(heloBox.left + heloBox.width / 2f, heloBox.center.y),
            )
        val wroldClick =
            textOffsetAtRoot(
                layout,
                Offset.Zero,
                Offset(wroldBox.left + wroldBox.width / 2f, wroldBox.center.y),
            )
        assertEquals(helo, misspellingAt(listOf(helo, wrold), TextRange(heloClick)))
        assertEquals(wrold, misspellingAt(listOf(helo, wrold), TextRange(wroldClick)))
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

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.ui.text.AnnotatedString
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenuSeparator
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.menu.macos.NsMenuItemImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuInterpreterTest {
    @Test
    fun `nucleus item keeps icon`() {
        val item =
            NucleusContextMenuItem(
                label = "Delete",
                icon = ContextMenuIcon.Delete,
                onClick = {},
            )
        val entry = DefaultContextMenuItemInterpreter.interpret(item, SpellcheckContextMenuSeparator)
        val typed = entry as ContextMenuEntry.Item
        assertEquals("Delete", typed.label)
        assertSame(ContextMenuIcon.Delete, typed.icon)
        assertNull(typed.shortcut)
        assertTrue(typed.enabled)
    }

    @Test
    fun `stock edit icons get platform shortcuts`() {
        val copy =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Copy", icon = ContextMenuIcon.Copy) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        val cut =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Cut", icon = ContextMenuIcon.Cut) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        val paste =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Paste", icon = ContextMenuIcon.Paste) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        val selectAll =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Select All", icon = ContextMenuIcon.SelectAll) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        assertEquals(expectedPrimaryShortcut("C"), copy.shortcut)
        assertEquals(expectedPrimaryShortcut("X"), cut.shortcut)
        assertEquals(expectedPrimaryShortcut("V"), paste.shortcut)
        assertEquals(expectedPrimaryShortcut("A"), selectAll.shortcut)
    }

    @Test
    fun `explicit shortcut overrides stock fallback and empty hides it`() {
        val custom =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem(
                    label = "Copy",
                    icon = ContextMenuIcon.Copy,
                    shortcut = "Ctrl+Shift+C",
                ) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        assertEquals("Ctrl+Shift+C", custom.shortcut)
        val hidden =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem(
                    label = "Copy",
                    icon = ContextMenuIcon.Copy,
                    shortcut = "",
                ) {},
                SpellcheckContextMenuSeparator,
            ) as ContextMenuEntry.Item
        assertNull(hidden.shortcut)
    }

    @Test
    fun `dividers become separators`() {
        val interpreter = DefaultContextMenuItemInterpreter
        assertSame(
            ContextMenuEntry.Separator,
            interpreter.interpret(NucleusContextMenuDivider, SpellcheckContextMenuSeparator),
        )
        assertSame(
            ContextMenuEntry.Separator,
            interpreter.interpret(SpellcheckContextMenuSeparator, SpellcheckContextMenuSeparator),
        )
        val custom = ContextMenuItem("---") {}
        assertSame(
            ContextMenuEntry.Separator,
            interpreter.interpret(custom, custom),
        )
    }

    @Test
    fun `plain compose item has no icon`() {
        val item = ContextMenuItem("Copy") {}
        val entry = DefaultContextMenuItemInterpreter.interpret(item, SpellcheckContextMenuSeparator)
        val typed = entry as ContextMenuEntry.Item
        assertEquals("Copy", typed.label)
        assertNull(typed.icon)
    }

    @Test
    fun `submenu interprets children`() {
        val submenu =
            NucleusContextMenuSubmenu("More") {
                listOf(
                    NucleusContextMenuItem("Nested", icon = ContextMenuIcon.Folder) {},
                    NucleusContextMenuDivider,
                )
            }
        val entry = DefaultContextMenuItemInterpreter.interpret(submenu, SpellcheckContextMenuSeparator)
        val typed = entry as ContextMenuEntry.Submenu
        assertEquals("More", typed.label)
        assertEquals(2, typed.items.size)
        assertSame(ContextMenuIcon.Folder, (typed.items[0] as ContextMenuEntry.Item).icon)
        assertSame(ContextMenuEntry.Separator, typed.items[1])
    }

    @Test
    fun `stock icons map to sf symbols except select all`() {
        assertEquals("scissors", symbolName(ContextMenuIcon.Cut))
        assertEquals("doc.on.doc", symbolName(ContextMenuIcon.Copy))
        assertEquals("doc.on.clipboard", symbolName(ContextMenuIcon.Paste))
        assertNull(ContextMenuIcon.SelectAll.toNsMenuItemImage())
        assertEquals("folder", symbolName(ContextMenuIcon.Folder))
        assertEquals("square.and.arrow.up", symbolName(ContextMenuIcon.SfSymbol("square.and.arrow.up")))
    }

    @Test
    fun `stock icons map to fluent glyphs and sf symbols are ignored`() {
        assertEquals("\uE8C6", ContextMenuIcon.Cut.toFluentGlyph())
        assertEquals("\uE8C8", ContextMenuIcon.Copy.toFluentGlyph())
        assertEquals("\uE77F", ContextMenuIcon.Paste.toFluentGlyph())
        assertEquals("\uE8B3", ContextMenuIcon.SelectAll.toFluentGlyph())
        assertEquals("\uE74D", ContextMenuIcon.Delete.toFluentGlyph())
        assertEquals("\uE8B7", ContextMenuIcon.Folder.toFluentGlyph())
        assertNull(ContextMenuIcon.SfSymbol("square.and.arrow.up").toFluentGlyph())
    }

    @Test
    fun `stock icons map to breeze glyphs and sf symbols are ignored`() {
        assertEquals("\u2702", ContextMenuIcon.Cut.toBreezeGlyph())
        assertEquals("\u2398", ContextMenuIcon.Copy.toBreezeGlyph())
        assertEquals("\u2399", ContextMenuIcon.Paste.toBreezeGlyph())
        assertEquals("\u2611", ContextMenuIcon.SelectAll.toBreezeGlyph())
        assertEquals("\u232B", ContextMenuIcon.Delete.toBreezeGlyph())
        assertEquals("\u25A1", ContextMenuIcon.Folder.toBreezeGlyph())
        assertNull(ContextMenuIcon.SfSymbol("square.and.arrow.up").toBreezeGlyph())
    }

    @Test
    fun `linux flyout uses breeze on kde and adwaita otherwise`() {
        val theme = linuxContextMenuTheme()
        if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) {
            assertSame(BreezeMenuTheme, theme)
        } else {
            assertSame(AdwaitaMenuTheme, theme)
        }
    }

    @Test
    fun `native text menu tags cut copy paste`() {
        val manager =
            object : TextContextMenu.TextManager {
                override val selectedText: AnnotatedString = AnnotatedString("hi")
                override val cut: TextContextMenu.Action = TextContextMenu.Action(enabled = true) {}
                override val copy: TextContextMenu.Action = TextContextMenu.Action(enabled = true) {}
                override val paste: TextContextMenu.Action = TextContextMenu.Action(enabled = false) {}
                override val selectAll: TextContextMenu.Action = TextContextMenu.Action(enabled = true) {}

                override fun selectWordAtPositionIfNotAlreadySelected(offset: androidx.compose.ui.geometry.Offset) {
                    // Not exercised — TextManager contract only.
                }
            }
        val localization =
            object : androidx.compose.ui.platform.PlatformLocalization {
                override val cut: String = "Cut"
                override val copy: String = "Copy"
                override val paste: String = "Paste"
                override val selectAll: String = "Select All"
            }
        val items = nativeTextContextMenuItems(localization, manager)
        assertEquals(4, items.size)
        assertSame(ContextMenuIcon.Cut, (items[0] as NucleusContextMenuItem).icon)
        assertSame(ContextMenuIcon.Copy, (items[1] as NucleusContextMenuItem).icon)
        assertSame(ContextMenuIcon.Paste, (items[2] as NucleusContextMenuItem).icon)
        assertSame(ContextMenuIcon.SelectAll, (items[3] as NucleusContextMenuItem).icon)
        assertEquals(false, items[2].enabled)
        val interpreted = items.map { DefaultContextMenuItemInterpreter.interpret(it, SpellcheckContextMenuSeparator) }
        assertEquals(expectedPrimaryShortcut("X"), (interpreted[0] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("C"), (interpreted[1] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("V"), (interpreted[2] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("A"), (interpreted[3] as ContextMenuEntry.Item).shortcut)
    }
}

private fun expectedPrimaryShortcut(key: String): String =
    if (Platform.Current == Platform.MacOS) "⌘$key" else "Ctrl+$key"

private fun symbolName(icon: ContextMenuIcon): String = (icon.toNsMenuItemImage() as NsMenuItemImage.SystemSymbol).name

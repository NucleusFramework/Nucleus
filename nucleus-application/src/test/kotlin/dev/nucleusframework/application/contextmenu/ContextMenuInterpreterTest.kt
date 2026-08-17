@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.ui.text.AnnotatedString
import dev.nucleusframework.core.runtime.LinuxUiToolkit
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
        val entry = DefaultContextMenuItemInterpreter.interpret(item, NucleusContextMenuDivider)
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
                NucleusContextMenuDivider,
            ) as ContextMenuEntry.Item
        val cut =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Cut", icon = ContextMenuIcon.Cut) {},
                NucleusContextMenuDivider,
            ) as ContextMenuEntry.Item
        val paste =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Paste", icon = ContextMenuIcon.Paste) {},
                NucleusContextMenuDivider,
            ) as ContextMenuEntry.Item
        val selectAll =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem("Select All", icon = ContextMenuIcon.SelectAll) {},
                NucleusContextMenuDivider,
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
                NucleusContextMenuDivider,
            ) as ContextMenuEntry.Item
        assertEquals("Ctrl+Shift+C", custom.shortcut)
        val hidden =
            DefaultContextMenuItemInterpreter.interpret(
                NucleusContextMenuItem(
                    label = "Copy",
                    icon = ContextMenuIcon.Copy,
                    shortcut = "",
                ) {},
                NucleusContextMenuDivider,
            ) as ContextMenuEntry.Item
        assertNull(hidden.shortcut)
    }

    @Test
    fun `dividers become separators`() {
        val interpreter = DefaultContextMenuItemInterpreter
        assertSame(
            ContextMenuEntry.Separator,
            interpreter.interpret(NucleusContextMenuDivider, NucleusContextMenuDivider),
        )
        val custom = ContextMenuItem("---") {}
        assertSame(
            ContextMenuEntry.Separator,
            interpreter.interpret(custom, custom),
        )
        assertTrue(
            "an unrelated item must stay a row",
            interpreter.interpret(ContextMenuItem("Rename") {}, custom) is ContextMenuEntry.Item,
        )
    }

    @Test
    fun `separators are trimmed at the edges and collapsed in runs`() {
        val entries =
            listOf(
                ContextMenuEntry.Separator,
                ContextMenuEntry.Separator,
                row("Cut"),
                ContextMenuEntry.Separator,
                ContextMenuEntry.Separator,
                row("Paste"),
                ContextMenuEntry.Separator,
            ).withNormalizedSeparators()
        assertEquals(3, entries.size)
        assertEquals("Cut", (entries[0] as ContextMenuEntry.Item).label)
        assertSame(ContextMenuEntry.Separator, entries[1])
        assertEquals("Paste", (entries[2] as ContextMenuEntry.Item).label)
    }

    @Test
    fun `separator normalization recurses into submenus`() {
        val entries =
            listOf(
                ContextMenuEntry.Submenu(
                    label = "More",
                    items = listOf(ContextMenuEntry.Separator, row("Nested"), ContextMenuEntry.Separator),
                ),
            ).withNormalizedSeparators()
        val submenu = entries.single() as ContextMenuEntry.Submenu
        assertEquals(1, submenu.items.size)
        assertEquals("Nested", (submenu.items.single() as ContextMenuEntry.Item).label)
    }

    @Test
    fun `a menu made only of separators normalizes to nothing`() {
        assertTrue(
            listOf(ContextMenuEntry.Separator, ContextMenuEntry.Separator).withNormalizedSeparators().isEmpty(),
        )
    }

    @Test
    fun `plain compose item has no icon`() {
        val item = ContextMenuItem("Copy") {}
        val entry = DefaultContextMenuItemInterpreter.interpret(item, NucleusContextMenuDivider)
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
        val entry = DefaultContextMenuItemInterpreter.interpret(submenu, NucleusContextMenuDivider)
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
    fun `stock icons map to breeze vectors and sf symbols are ignored`() {
        listOf(
            ContextMenuIcon.Cut,
            ContextMenuIcon.Copy,
            ContextMenuIcon.Paste,
            ContextMenuIcon.SelectAll,
            ContextMenuIcon.Delete,
            ContextMenuIcon.Folder,
        ).forEach { icon ->
            val vector = icon.toBreezeVector()!!
            assertEquals("width of $icon", 16f, vector.defaultWidth.value, 0f)
            assertEquals("height of $icon", 16f, vector.defaultHeight.value, 0f)
            assertEquals("viewport width of $icon", 16f, vector.viewportWidth, 0f)
            assertEquals("viewport height of $icon", 16f, vector.viewportHeight, 0f)
        }
        assertNull(ContextMenuIcon.SfSymbol("square.and.arrow.up").toBreezeVector())
    }

    @Test
    fun `linux flyout uses breeze on qt desktops and adwaita on gtk`() {
        val theme = linuxContextMenuTheme()
        if (LinuxUiToolkit.Current == LinuxUiToolkit.Qt) {
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
        val interpreted = items.map { DefaultContextMenuItemInterpreter.interpret(it, NucleusContextMenuDivider) }
        assertEquals(expectedPrimaryShortcut("X"), (interpreted[0] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("C"), (interpreted[1] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("V"), (interpreted[2] as ContextMenuEntry.Item).shortcut)
        assertEquals(expectedPrimaryShortcut("A"), (interpreted[3] as ContextMenuEntry.Item).shortcut)
    }
}

private fun row(label: String): ContextMenuEntry.Item =
    ContextMenuEntry.Item(label = label, enabled = true, icon = null, onClick = {})

private fun expectedPrimaryShortcut(key: String): String =
    if (Platform.Current == Platform.MacOS) "⌘$key" else "Ctrl+$key"

private fun symbolName(icon: ContextMenuIcon): String = (icon.toNsMenuItemImage() as NsMenuItemImage.SystemSymbol).name

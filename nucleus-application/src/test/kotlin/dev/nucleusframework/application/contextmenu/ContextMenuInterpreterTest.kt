@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.ui.text.AnnotatedString
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenuSeparator
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
        assertTrue(typed.enabled)
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
    }
}

private fun symbolName(icon: ContextMenuIcon): String = (icon.toNsMenuItemImage() as NsMenuItemImage.SystemSymbol).name

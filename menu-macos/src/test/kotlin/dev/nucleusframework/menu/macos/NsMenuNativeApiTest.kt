package dev.nucleusframework.menu.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class NsMenuNativeApiTest {
    @Test
    fun `create inspect and release a native menu tree without popping it`() {
        if (!NsMenu.isAvailable) {
            assertFalse(isNativePopupMenuAvailable)
            return
        }

        val created = mutableListOf<AutoCloseable>()
        try {
            val menu = NsMenu("Coverage").also { created += it }
            assertTrue(menu.handle != 0L)
            menu.autoenablesItems = false
            assertFalse(menu.autoenablesItems)
            menu.title = "Renamed"
            assertEquals("Renamed", menu.title)
            menu.minimumWidth = 120f
            assertTrue(menu.minimumWidth >= 0f)
            menu.showsStateColumn = true
            assertTrue(menu.showsStateColumn)
            menu.allowsContextMenuPlugIns = false
            assertFalse(menu.allowsContextMenuPlugIns)
            menu.userInterfaceLayoutDirection = NsUserInterfaceLayoutDirection.RIGHT_TO_LEFT
            assertEquals(NsUserInterfaceLayoutDirection.RIGHT_TO_LEFT, menu.userInterfaceLayoutDirection)
            menu.presentationStyle = NsMenuPresentationStyle.REGULAR
            assertEquals(NsMenuPresentationStyle.REGULAR, menu.presentationStyle)
            menu.selectionMode = NsMenuSelectionMode.SELECT_ANY
            assertEquals(NsMenuSelectionMode.SELECT_ANY, menu.selectionMode)

            val item = NsMenuItem("Open", keyEquivalent = "o").also { created += it }
            item.isEnabled = false
            assertFalse(item.isEnabled)
            item.isHidden = true
            assertTrue(item.isHidden)
            item.isAlternate = true
            assertTrue(item.isAlternate)
            item.tag = 42
            assertEquals(42, item.tag)
            item.state = NsMenuItemState.MIXED
            assertEquals(NsMenuItemState.MIXED, item.state)
            item.indentationLevel = 2
            assertEquals(2, item.indentationLevel)
            item.keyEquivalentModifierMask = NsEventModifierFlags.COMMAND or NsEventModifierFlags.SHIFT
            item.title = "Open File"
            assertEquals("Open File", item.title)
            item.keyEquivalent = "O"
            assertEquals("O", item.keyEquivalent)
            item.toolTip = "tip"
            assertEquals("tip", item.toolTip)
            item.subtitle = "sub"
            item.image = NsMenuItemImage.Named("NSActionTemplate")
            item.onStateImage = NsMenuItemImage.SystemSymbol("checkmark")
            item.offStateImage = NsMenuItemImage.File("/tmp/off.png")
            item.mixedStateImage = NsMenuItemImage.SystemSymbol("minus", "mixed")
            item.badge = NsMenuItemBadge.Count(3)
            item.badge = NsMenuItemBadge.Text("NEW")
            item.badge = NsMenuItemBadge.alerts(1)
            item.badge = NsMenuItemBadge.newItems(2)
            item.badge = NsMenuItemBadge.updates(4)
            item.badge = null
            item.image = null
            item.allowsAutomaticKeyEquivalentLocalization = false
            item.allowsAutomaticKeyEquivalentMirroring = false
            item.allowsKeyEquivalentWhenHidden = true
            var clicks = 0
            item.onAction = { clicks++ }
            assertNotNull(item.onAction)
            item.onAction?.invoke()
            assertEquals(1, clicks)
            item.onAction = null
            assertNull(item.onAction)

            menu.addItem(item)
            val added = menu.addItem("Save", "s").also { created += it }
            added.tag = 7
            val inserted = menu.insertItem("Insert", "", 0).also { created += it }
            val extra = NsMenuItem("Extra").also { created += it }
            menu.insertItem(extra, 1)

            val sep = NsMenuItem.separator().also { created += it }
            menu.addItem(sep)
            assertTrue(sep.isSeparatorItem)

            val header = NsMenuItem.sectionHeader("Recent").also { created += it }
            menu.addItem(header)

            val search = NsMenuItem.searchField("Filter", 160.0).also { created += it }
            menu.addItem(search)

            val submenu = NsMenu("More").also { created += it }
            submenu.autoenablesItems = false
            val child = NsMenuItem("Child").also { created += it }
            submenu.addItem(child)
            val parentItem = NsMenuItem("Parent").also { created += it }
            parentItem.submenu = submenu
            menu.addItem(parentItem)
            menu.setSubmenu(submenu, parentItem)

            assertTrue(menu.numberOfItems >= 6)
            assertNotNull(menu.itemWithTag(42)).close()
            assertNotNull(menu.itemWithTitle("Save")).close()
            assertNotNull(menu.itemAtIndex(0)).close()
            assertTrue(menu.indexOfItem(item) >= 0)
            assertTrue(menu.indexOfItemWithTitle("Save") >= 0)
            assertTrue(menu.indexOfItemWithTag(7) >= 0)
            assertTrue(menu.indexOfItemWithSubmenu(submenu) >= 0)
            assertTrue(parentItem.hasSubmenu)
            parentItem.submenu?.close()
            parentItem.menu?.close()
            child.parentItem?.close()

            menu.itemChanged(item)
            menu.update()
            menu.sizeToFit()
            val size = menu.size
            assertTrue(size.first >= 0f)
            assertTrue(size.second >= 0f)
            assertNull(menu.highlightedItem)
            menu.supermenu?.close()
            menu.items.forEach { it.close() }
            menu.selectedItems.forEach { it.close() }

            val borrowedMenu = NsMenu.borrowed(menu.handle)
            borrowedMenu.close()
            val borrowedItem = NsMenuItem.borrowed(item.handle)
            borrowedItem.close()

            menu.removeItem(extra)
            menu.removeItemAtIndex(0)
            inserted.close()
            menu.removeAllItems()
            assertEquals(0, menu.numberOfItems)

            val visible = NsMenu.isMenuBarVisible
            NsMenu.isMenuBarVisible = visible
            assertTrue(NsMenu.menuBarHeight >= 0f)
            NsMenu.mainMenu?.close()
        } catch (error: Throwable) {
            if (error is UnsatisfiedLinkError || error.message?.contains("AppKit") == true) {
                return
            }
            fail(error.message ?: error.toString())
        } finally {
            created.asReversed().forEach { runCatching { it.close() } }
        }
    }
}

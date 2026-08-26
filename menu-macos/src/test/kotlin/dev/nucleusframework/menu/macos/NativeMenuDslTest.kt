package dev.nucleusframework.menu.macos

import dev.nucleusframework.sfsymbols.SFSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeMenuDslTest {
    @Test
    fun `menu bar scope records menus window help and nested items`() {
        var itemClicks = 0
        var checked = false
        var radio = "a"
        val scope = NativeMenuBarScope()
        scope.Menu("MyApp", enabled = false) {
            Item(
                text = "About",
                shortcut = NativeKeyShortcut("a", shift = true, option = true, control = true, function = true),
                icon = NsMenuItemImage.SystemSymbol("info.circle"),
                state = NsMenuItemState.MIXED,
                tag = 7,
                badge = NsMenuItemBadge.Count(3),
                subtitle = "sub",
                toolTip = "tip",
                indentationLevel = 1,
                isAlternate = true,
                isHidden = true,
                onStateImage = NsMenuItemImage.Named("NSOn"),
                offStateImage = NsMenuItemImage.File("/tmp/off.png"),
                mixedStateImage = NsMenuItemImage.SystemSymbol(SFSymbol.Custom("circle"), "mixed"),
            ) { itemClicks++ }
            CheckboxItem("Wrap", checked = false, onCheckedChange = { checked = it }, shortcut = NativeKeyShortcut("w"))
            RadioButtonItem("A", selected = true, onClick = { radio = "a" })
            RadioButtonItem("B", selected = false, onClick = { radio = "b" })
            SearchField("Filter", width = 180.0)
            Separator()
            SectionHeader("Recent")
            Menu("Open Recent", enabled = false, icon = NsMenuItemImage.Named("NSFolder")) {
                Item("One") { }
            }
        }
        scope.MenuWindow("Window") {
            Item("Minimize") { }
        }
        scope.MenuHelp("Help") {
            Item("Docs") { }
        }

        assertEquals(3, scope.entries.size)
        val app = scope.entries[0]
        assertEquals("MyApp", app.text)
        assertFalse(app.enabled)
        assertEquals(MenuRole.None, app.role)
        assertEquals(MenuRole.Window, scope.entries[1].role)
        assertEquals(MenuRole.Help, scope.entries[2].role)

        val about = assertIs<MenuItemEntry.Regular>(app.items[0])
        assertEquals("About", about.text)
        assertEquals("a", about.shortcut?.key)
        assertTrue(about.shortcut!!.shift)
        assertTrue(about.shortcut.option)
        assertTrue(about.shortcut.control)
        assertTrue(about.shortcut.function)
        assertEquals(NsMenuItemState.MIXED, about.state)
        assertEquals(7, about.tag)
        assertIs<NsMenuItemBadge.Count>(about.badge)
        assertEquals(3, about.badge.count)
        assertEquals("sub", about.subtitle)
        assertEquals("tip", about.toolTip)
        assertEquals(1, about.indentationLevel)
        assertTrue(about.isAlternate)
        assertTrue(about.isHidden)
        about.onClick()
        assertEquals(1, itemClicks)

        val wrap = assertIs<MenuItemEntry.Regular>(app.items[1])
        assertEquals(NsMenuItemState.OFF, wrap.state)
        wrap.onClick()
        assertTrue(checked)

        val radioA = assertIs<MenuItemEntry.Regular>(app.items[2])
        assertEquals(NsMenuItemState.ON, radioA.state)
        val radioB = assertIs<MenuItemEntry.Regular>(app.items[3])
        assertEquals(NsMenuItemState.OFF, radioB.state)
        radioB.onClick()
        assertEquals("b", radio)

        val search = assertIs<MenuItemEntry.SearchFieldEntry>(app.items[4])
        assertEquals("Filter", search.placeholder)
        assertEquals(180.0, search.width)
        assertIs<MenuItemEntry.SeparatorEntry>(app.items[5])
        val header = assertIs<MenuItemEntry.SectionHeaderEntry>(app.items[6])
        assertEquals("Recent", header.title)
        val submenu = assertIs<MenuItemEntry.SubmenuEntry>(app.items[7])
        assertEquals("Open Recent", submenu.text)
        assertFalse(submenu.enabled)
        assertEquals(1, submenu.items.size)
    }

    @Test
    fun `empty window and help menus are allowed`() {
        val scope = NativeMenuBarScope()
        scope.MenuWindow("Window")
        scope.MenuHelp("Help")
        assertTrue(scope.entries[0].items.isEmpty())
        assertTrue(scope.entries[1].items.isEmpty())
        assertEquals("Window", scope.entries[0].text)
        assertEquals("Help", scope.entries[1].text)
    }

    @Test
    fun `key shortcut constants and defaults`() {
        val commandOnly = NativeKeyShortcut("s")
        assertTrue(commandOnly.command)
        assertFalse(commandOnly.shift)
        assertEquals("s", commandOnly.key)
        assertEquals("\u001B", NativeKey.ESCAPE)
        assertEquals("\r", NativeKey.RETURN)
        assertEquals("\t", NativeKey.TAB)
        assertEquals("\u007F", NativeKey.DELETE)
        assertEquals("\u0008", NativeKey.BACKSPACE)
        assertEquals("\uF700", NativeKey.UP)
        assertEquals("\uF701", NativeKey.DOWN)
        assertEquals("\uF702", NativeKey.LEFT)
        assertEquals("\uF703", NativeKey.RIGHT)
        assertEquals("\uF704", NativeKey.F1)
        assertEquals("\uF70F", NativeKey.F12)
        assertEquals("\uF729", NativeKey.HOME)
        assertEquals("\uF72B", NativeKey.END)
        assertEquals("\uF72C", NativeKey.PAGE_UP)
        assertEquals("\uF72D", NativeKey.PAGE_DOWN)
    }

    @Test
    fun `menu item state badge and image models`() {
        assertEquals(NsMenuItemState.OFF, NsMenuItemState.fromNative(0))
        assertEquals(NsMenuItemState.ON, NsMenuItemState.fromNative(1))
        assertEquals(NsMenuItemState.MIXED, NsMenuItemState.fromNative(-1))
        assertEquals(NsMenuItemState.OFF, NsMenuItemState.fromNative(99))

        assertEquals(3, (NsMenuItemBadge.alerts(3) as NsMenuItemBadge.Alerts).count)
        assertEquals(2, (NsMenuItemBadge.newItems(2) as NsMenuItemBadge.NewItems).count)
        assertEquals(1, (NsMenuItemBadge.updates(1) as NsMenuItemBadge.Updates).count)
        assertEquals("Hi", (NsMenuItemBadge.Text("Hi")).string)

        val named = NsMenuItemImage.Named("NSActionTemplate")
        val file = NsMenuItemImage.File("/tmp/icon.png")
        val symbol = NsMenuItemImage.SystemSymbol(SFSymbol.Custom("scissors"), "Cut")
        assertEquals("NSActionTemplate", named.name)
        assertEquals("/tmp/icon.png", file.path)
        assertEquals("scissors", symbol.name)
        assertEquals("Cut", symbol.accessibilityDescription)

        assertEquals(NsUserInterfaceLayoutDirection.LEFT_TO_RIGHT, NsUserInterfaceLayoutDirection.fromNative(0))
        assertEquals(NsUserInterfaceLayoutDirection.RIGHT_TO_LEFT, NsUserInterfaceLayoutDirection.fromNative(1))
        assertEquals(NsUserInterfaceLayoutDirection.LEFT_TO_RIGHT, NsUserInterfaceLayoutDirection.fromNative(9))
        assertEquals(NsMenuPresentationStyle.REGULAR, NsMenuPresentationStyle.fromNative(0))
        assertEquals(NsMenuPresentationStyle.PALETTE, NsMenuPresentationStyle.fromNative(1))
        assertEquals(NsMenuPresentationStyle.REGULAR, NsMenuPresentationStyle.fromNative(4))
        assertEquals(NsMenuSelectionMode.AUTOMATIC, NsMenuSelectionMode.fromNative(0))
        assertEquals(NsMenuSelectionMode.SELECT_ANY, NsMenuSelectionMode.fromNative(1))
        assertEquals(NsMenuSelectionMode.SELECT_ONE, NsMenuSelectionMode.fromNative(2))
        assertEquals(NsMenuSelectionMode.AUTOMATIC, NsMenuSelectionMode.fromNative(8))
        assertEquals(NsMenuItemBadgeType.NONE, NsMenuItemBadgeType.fromNative(0))
        assertEquals(NsMenuItemBadgeType.UPDATES, NsMenuItemBadgeType.fromNative(1))
        assertEquals(NsMenuItemBadgeType.NEW_ITEMS, NsMenuItemBadgeType.fromNative(2))
        assertEquals(NsMenuItemBadgeType.ALERTS, NsMenuItemBadgeType.fromNative(3))
        assertEquals(NsMenuItemBadgeType.NONE, NsMenuItemBadgeType.fromNative(9))
    }

    @Test
    fun `popup menu items and empty pop-up return false`() {
        val entry =
            NativePopupMenuItem.Entry(
                "Open",
                enabled = false,
                icon = NsMenuItemImage.Named("NSOpen"),
                onClick = {},
            )
        val submenu = NativePopupMenuItem.Submenu("More", listOf(entry, NativePopupMenuItem.Separator))
        assertEquals("Open", entry.title)
        assertFalse(entry.enabled)
        assertEquals("More", submenu.title)
        assertEquals(2, submenu.items.size)
        assertFalse(popUpNativeMenu(emptyList()))
        assertEquals(NsMenu.isAvailable, isNativePopupMenuAvailable)
    }

    @Test
    fun `modifier flag constants are distinct bit masks`() {
        assertEquals(1 shl 16, NsEventModifierFlags.CAPS_LOCK)
        assertEquals(1 shl 17, NsEventModifierFlags.SHIFT)
        assertEquals(1 shl 18, NsEventModifierFlags.CONTROL)
        assertEquals(1 shl 19, NsEventModifierFlags.OPTION)
        assertEquals(1 shl 20, NsEventModifierFlags.COMMAND)
        assertEquals(1 shl 23, NsEventModifierFlags.FUNCTION)
        assertEquals(1, MenuStringProp.TITLE)
        assertEquals(1, BadgeType.COUNT)
        assertEquals(2, BadgeType.STRING)
        assertEquals(0, ImageType.CLEAR)
        assertEquals(2, ImageType.SYSTEM_SYMBOL)
        assertEquals(1, ImageType.NAMED)
        assertEquals(3, ImageType.FILE)
        assertEquals(0, StateImageTarget.ON)
        assertEquals(1, StateImageTarget.OFF)
        assertEquals(2, StateImageTarget.MIXED)
        assertEquals(0, BadgeType.CLEAR)
        assertEquals(3, BadgeType.ALERTS)
        assertEquals(4, BadgeType.NEW_ITEMS)
        assertEquals(5, BadgeType.UPDATES)
        assertEquals(1, MenuBoolProp.AUTO_ENABLES_ITEMS)
        assertEquals(2, MenuBoolProp.SHOWS_STATE_COLUMN)
        assertEquals(3, MenuBoolProp.ALLOWS_CONTEXT_MENU_PLUGINS)
        assertEquals(1, MenuIntProp.PRESENTATION_STYLE)
        assertEquals(2, MenuIntProp.SELECTION_MODE)
        assertEquals(3, MenuIntProp.LAYOUT_DIRECTION)
        assertEquals(1, MenuFloatProp.MINIMUM_WIDTH)
        assertEquals(2, ItemStringProp.KEY_EQUIVALENT)
        assertEquals(3, ItemStringProp.TOOLTIP)
        assertEquals(4, ItemStringProp.SUBTITLE)
        assertEquals(11, ItemBoolProp.ALLOWS_KEY_EQ_WHEN_HIDDEN)
        assertEquals(4, ItemIntProp.KEY_EQUIVALENT_MODIFIER_MASK)
    }

    @Test
    fun `remaining NativeKey constants and checked checkbox`() {
        assertEquals("\uF705", NativeKey.F2)
        assertEquals("\uF706", NativeKey.F3)
        assertEquals("\uF707", NativeKey.F4)
        assertEquals("\uF708", NativeKey.F5)
        assertEquals("\uF709", NativeKey.F6)
        assertEquals("\uF70A", NativeKey.F7)
        assertEquals("\uF70B", NativeKey.F8)
        assertEquals("\uF70C", NativeKey.F9)
        assertEquals("\uF70D", NativeKey.F10)
        assertEquals("\uF70E", NativeKey.F11)
        val noCommand = NativeKeyShortcut("", command = false)
        assertFalse(noCommand.command)
        assertEquals("", noCommand.key)

        var checked = true
        val scope = NativeMenuBarScope()
        scope.Menu("Edit", mnemonic = 'E') {
            CheckboxItem("Wrap", checked = true, onCheckedChange = { checked = it })
            RadioButtonItem("On", selected = false, onClick = {}, mnemonic = 'O')
        }
        val wrap = assertIs<MenuItemEntry.Regular>(scope.entries[0].items[0])
        assertEquals(NsMenuItemState.ON, wrap.state)
        wrap.onClick()
        assertFalse(checked)
    }

    @Test
    fun `bridge callbacks are no-ops without a registered mapping`() {
        NativeNsMenuBridge.clearAllActions()
        NativeNsMenuBridge.removeAction(99L)
        assertEquals(null, NativeNsMenuBridge.getAction(99L))
        NativeNsMenuBridge.removeDelegateMapping(99L)
        NativeNsMenuBridge.onMenuItemAction(99L)
        NativeNsMenuBridge.onMenuWillOpen(99L)
        NativeNsMenuBridge.onMenuDidClose(99L)
        NativeNsMenuBridge.onMenuNeedsUpdate(99L)
        NativeNsMenuBridge.onMenuWillHighlightItem(99L, 0L)
        assertEquals(-1, NativeNsMenuBridge.onNumberOfItemsInMenu(99L))

        val dummy = object : NsMenuDelegate {}
        NativeNsMenuBridge.setDelegateMapping(42L, dummy)
        NativeNsMenuBridge.setDelegateMapping(42L, null)
        assertEquals(-1, NativeNsMenuBridge.onNumberOfItemsInMenu(42L))
    }

    @Test
    fun `popup entry defaults and submenu nesting`() {
        val entry = NativePopupMenuItem.Entry("Default")
        assertTrue(entry.enabled)
        assertEquals(null, entry.icon)
        entry.onClick()
        val nested =
            NativePopupMenuItem.Submenu(
                "More",
                listOf(entry, NativePopupMenuItem.Separator, NativePopupMenuItem.Entry("Leaf", enabled = false)),
            )
        assertEquals(3, nested.items.size)
        assertFalse((nested.items[2] as NativePopupMenuItem.Entry).enabled)
    }
}

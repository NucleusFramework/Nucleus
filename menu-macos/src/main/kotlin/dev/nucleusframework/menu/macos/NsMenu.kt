package dev.nucleusframework.menu.macos

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin wrapper for AppKit NSMenu.
 *
 * Each instance holds a retained native handle. Call [close] when done to
 * release the native reference. Implements [AutoCloseable] for use with
 * Kotlin's `use {}` pattern.
 *
 * Objects returned by query methods (e.g. [itemAtIndex], [supermenu]) are
 * also retained and must be closed independently.
 */
internal class NsMenu internal constructor(
    internal val handle: Long,
    private val owned: Boolean = true,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Creates a new NSMenu with the given title. */
    constructor(title: String = "") : this(NativeNsMenuBridge.nativeCreateMenu(title))

    override fun close() {
        if (owned && closed.compareAndSet(false, true)) {
            delegate = null
            NativeNsMenuBridge.nativeRelease(handle)
        }
    }

    // ---- Menu bar (static) ----

    companion object {
        /** Whether the native library is loaded and the module is functional. */
        val isAvailable: Boolean get() = NativeNsMenuBridge.isLoaded

        /** Whether the menu bar is visible and selectable by the user. */
        var isMenuBarVisible: Boolean
            get() = NativeNsMenuBridge.nativeMenuBarIsVisible()
            set(value) = NativeNsMenuBridge.nativeMenuBarSetVisible(value)

        /** The menu bar height for the main menu in pixels. */
        val menuBarHeight: Float
            get() = NativeNsMenuBridge.nativeMenuBarGetHeight()

        /**
         * Returns the application's main menu (the menu bar).
         * The returned [NsMenu] is a retained reference that must be closed when no longer needed.
         * Returns null if no main menu is set.
         */
        val mainMenu: NsMenu?
            get() {
                val h = NativeNsMenuBridge.nativeGetMainMenu()
                return if (h != 0L) NsMenu(h) else null
            }

        /**
         * Sets the application's main menu (the menu bar).
         * The menu should contain items, each with a submenu that represents
         * a top-level menu (File, Edit, View, etc.).
         */
        fun setMainMenu(menu: NsMenu) {
            NativeNsMenuBridge.nativeSetMainMenu(menu.handle)
        }

        /** Creates a non-owning wrapper for use in callbacks. */
        internal fun borrowed(handle: Long): NsMenu = NsMenu(handle, owned = false)
    }

    // ---- Properties ----

    var title: String
        get() = NativeNsMenuBridge.nativeMenuGetString(handle, MenuStringProp.TITLE) ?: ""
        set(value) = NativeNsMenuBridge.nativeMenuSetString(handle, MenuStringProp.TITLE, value)

    var autoenablesItems: Boolean
        get() = NativeNsMenuBridge.nativeMenuGetBool(handle, MenuBoolProp.AUTO_ENABLES_ITEMS)
        set(value) = NativeNsMenuBridge.nativeMenuSetBool(handle, MenuBoolProp.AUTO_ENABLES_ITEMS, value)

    var minimumWidth: Float
        get() = NativeNsMenuBridge.nativeMenuGetFloat(handle, MenuFloatProp.MINIMUM_WIDTH)
        set(value) = NativeNsMenuBridge.nativeMenuSetFloat(handle, MenuFloatProp.MINIMUM_WIDTH, value)

    /** The size of the menu in screen coordinates (width, height). */
    val size: Pair<Float, Float>
        get() {
            val arr = NativeNsMenuBridge.nativeMenuGetSize(handle)
            return arr[0] to arr[1]
        }

    var showsStateColumn: Boolean
        get() = NativeNsMenuBridge.nativeMenuGetBool(handle, MenuBoolProp.SHOWS_STATE_COLUMN)
        set(value) = NativeNsMenuBridge.nativeMenuSetBool(handle, MenuBoolProp.SHOWS_STATE_COLUMN, value)

    var allowsContextMenuPlugIns: Boolean
        get() = NativeNsMenuBridge.nativeMenuGetBool(handle, MenuBoolProp.ALLOWS_CONTEXT_MENU_PLUGINS)
        set(value) = NativeNsMenuBridge.nativeMenuSetBool(handle, MenuBoolProp.ALLOWS_CONTEXT_MENU_PLUGINS, value)

    var userInterfaceLayoutDirection: NsUserInterfaceLayoutDirection
        get() =
            NsUserInterfaceLayoutDirection.fromNative(
                NativeNsMenuBridge.nativeMenuGetInt(handle, MenuIntProp.LAYOUT_DIRECTION),
            )
        set(value) = NativeNsMenuBridge.nativeMenuSetInt(handle, MenuIntProp.LAYOUT_DIRECTION, value.nativeValue)

    /** NSMenuPresentationStyle (macOS 14+). Returns [NsMenuPresentationStyle.REGULAR] on older systems. */
    var presentationStyle: NsMenuPresentationStyle
        get() =
            NsMenuPresentationStyle.fromNative(
                NativeNsMenuBridge.nativeMenuGetInt(handle, MenuIntProp.PRESENTATION_STYLE),
            )
        set(value) = NativeNsMenuBridge.nativeMenuSetInt(handle, MenuIntProp.PRESENTATION_STYLE, value.nativeValue)

    /** NSMenuSelectionMode (macOS 14+). Returns [NsMenuSelectionMode.AUTOMATIC] on older systems. */
    var selectionMode: NsMenuSelectionMode
        get() =
            NsMenuSelectionMode.fromNative(
                NativeNsMenuBridge.nativeMenuGetInt(handle, MenuIntProp.SELECTION_MODE),
            )
        set(value) = NativeNsMenuBridge.nativeMenuSetInt(handle, MenuIntProp.SELECTION_MODE, value.nativeValue)

    /** The currently highlighted item, or null. The returned item must be closed. */
    val highlightedItem: NsMenuItem?
        get() {
            val h = NativeNsMenuBridge.nativeMenuGetHighlightedItem(handle)
            return if (h != 0L) NsMenuItem(h) else null
        }

    /** The parent menu, or null. The returned menu must be closed. */
    val supermenu: NsMenu?
        get() {
            val h = NativeNsMenuBridge.nativeMenuGetSupermenu(handle)
            return if (h != 0L) NsMenu(h) else null
        }

    /** The number of menu items, including separators. */
    val numberOfItems: Int
        get() = NativeNsMenuBridge.nativeMenuGetNumberOfItems(handle)

    /**
     * All menu items. Each returned [NsMenuItem] is a retained reference
     * that must be closed when no longer needed.
     */
    val items: List<NsMenuItem>
        get() = NativeNsMenuBridge.nativeMenuGetItems(handle).map { NsMenuItem(it) }

    /**
     * The currently selected items (macOS 14+).
     * Each returned [NsMenuItem] must be closed.
     */
    val selectedItems: List<NsMenuItem>
        get() = NativeNsMenuBridge.nativeMenuGetSelectedItems(handle).map { NsMenuItem(it) }

    // ---- Adding and removing items ----

    fun addItem(item: NsMenuItem) {
        NativeNsMenuBridge.nativeMenuAddItem(handle, item.handle)
    }

    /** Creates a new item, adds it to the end, and returns it. The caller owns the returned handle. */
    fun addItem(
        title: String,
        keyEquivalent: String = "",
    ): NsMenuItem {
        val h = NativeNsMenuBridge.nativeMenuAddItemWithTitle(handle, title, keyEquivalent)
        return NsMenuItem(h)
    }

    fun insertItem(
        item: NsMenuItem,
        atIndex: Int,
    ) {
        NativeNsMenuBridge.nativeMenuInsertItem(handle, item.handle, atIndex)
    }

    /** Creates a new item, inserts it at [atIndex], and returns it. The caller owns the returned handle. */
    fun insertItem(
        title: String,
        keyEquivalent: String = "",
        atIndex: Int,
    ): NsMenuItem {
        val h = NativeNsMenuBridge.nativeMenuInsertItemWithTitle(handle, title, keyEquivalent, atIndex)
        return NsMenuItem(h)
    }

    fun removeItem(item: NsMenuItem) {
        NativeNsMenuBridge.nativeMenuRemoveItem(handle, item.handle)
    }

    fun removeItemAtIndex(index: Int) {
        NativeNsMenuBridge.nativeMenuRemoveItemAtIndex(handle, index)
    }

    fun removeAllItems() {
        NativeNsMenuBridge.nativeMenuRemoveAllItems(handle)
    }

    /** Notifies the menu that the given item has been modified visually. */
    fun itemChanged(item: NsMenuItem) {
        NativeNsMenuBridge.nativeMenuItemChanged(handle, item.handle)
    }

    // ---- Finding items ----

    /** Returns the first item with the given tag, or null. The returned item must be closed. */
    fun itemWithTag(tag: Int): NsMenuItem? {
        val h = NativeNsMenuBridge.nativeMenuItemWithTag(handle, tag)
        return if (h != 0L) NsMenuItem(h) else null
    }

    /** Returns the first item with the given title, or null. The returned item must be closed. */
    fun itemWithTitle(title: String): NsMenuItem? {
        val h = NativeNsMenuBridge.nativeMenuItemWithTitle(handle, title)
        return if (h != 0L) NsMenuItem(h) else null
    }

    /** Returns the item at the given index, or null. The returned item must be closed. */
    fun itemAtIndex(index: Int): NsMenuItem? {
        val h = NativeNsMenuBridge.nativeMenuItemAtIndex(handle, index)
        return if (h != 0L) NsMenuItem(h) else null
    }

    // ---- Finding indices ----

    fun indexOfItem(item: NsMenuItem): Int = NativeNsMenuBridge.nativeMenuIndexOfItem(handle, item.handle)

    fun indexOfItemWithTitle(title: String): Int = NativeNsMenuBridge.nativeMenuIndexOfItemWithTitle(handle, title)

    fun indexOfItemWithTag(tag: Int): Int = NativeNsMenuBridge.nativeMenuIndexOfItemWithTag(handle, tag)

    fun indexOfItemWithSubmenu(submenu: NsMenu): Int =
        NativeNsMenuBridge.nativeMenuIndexOfItemWithSubmenu(handle, submenu.handle)

    // ---- Submenu ----

    /** Assigns [submenu] as the submenu of [item]. Pass null to remove a submenu. */
    fun setSubmenu(
        submenu: NsMenu?,
        forItem: NsMenuItem,
    ) {
        NativeNsMenuBridge.nativeMenuSetSubmenuForItem(handle, submenu?.handle ?: 0L, forItem.handle)
    }

    // ---- Methods ----

    /** Validates and sizes menu items via the NSMenuValidation protocol. */
    fun update() = NativeNsMenuBridge.nativeMenuUpdate(handle)

    /** Resizes the menu to exactly fit its items. */
    fun sizeToFit() = NativeNsMenuBridge.nativeMenuSizeToFit(handle)

    /** Dismisses the menu and ends all menu tracking. */
    fun cancelTracking() = NativeNsMenuBridge.nativeMenuCancelTracking(handle)

    /** Dismisses the menu without animation. */
    fun cancelTrackingWithoutAnimation() = NativeNsMenuBridge.nativeMenuCancelTrackingWithoutAnimation(handle)

    /** Sends the action of the item at the given index to its target. */
    fun performActionForItemAtIndex(index: Int) =
        NativeNsMenuBridge.nativeMenuPerformActionForItemAtIndex(handle, index)

    /**
     * Pops up the menu at the given screen coordinates.
     *
     * @param positioningItem Optional item to position at the location, or null for the first item.
     * @param atX X coordinate in screen space.
     * @param atY Y coordinate in screen space.
     * @return true if the user selected an item, false if the menu was dismissed.
     */
    fun popUp(
        positioningItem: NsMenuItem? = null,
        atX: Float,
        atY: Float,
    ): Boolean = NativeNsMenuBridge.nativeMenuPopUp(handle, positioningItem?.handle ?: 0L, atX, atY)

    /** Pops the menu at the current mouse location (`NSEvent.mouseLocation`). */
    fun popUpAtCursor(): Boolean = NativeNsMenuBridge.nativeMenuPopUpAtCursor(handle)

    // ---- Delegate ----

    var delegate: NsMenuDelegate?
        get() = NativeNsMenuBridge.delegates[handle]
        set(value) {
            NativeNsMenuBridge.setDelegateMapping(handle, value)
            NativeNsMenuBridge.nativeMenuSetDelegate(handle, value != null)
        }
}

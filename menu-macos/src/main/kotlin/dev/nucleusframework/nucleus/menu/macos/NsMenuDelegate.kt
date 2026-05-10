package dev.nucleusframework.nucleus.menu.macos

/**
 * Kotlin mapping of NSMenuDelegate.
 *
 * Implement this interface to receive callbacks about menu lifecycle events.
 * All callbacks are dispatched on the JVM thread that the native menu system
 * uses (typically the AppKit main thread via JNI).
 */
internal interface NsMenuDelegate {
    /** Invoked when the menu is about to be displayed. */
    fun menuWillOpen(menu: NsMenu) {}

    /** Invoked after the menu has been closed. */
    fun menuDidClose(menu: NsMenu) {}

    /** Invoked when the menu needs to update its items before display. */
    fun menuNeedsUpdate(menu: NsMenu) {}

    /** Invoked when a menu item is about to be highlighted. [item] is null when no item is highlighted. */
    fun menuWillHighlightItem(
        menu: NsMenu,
        item: NsMenuItem?,
    ) {}

    /**
     * Return the number of items in a lazily-populated menu.
     * Return `-1` to use the existing [NsMenu.items] array instead.
     */
    fun numberOfItemsInMenu(menu: NsMenu): Int = -1
}

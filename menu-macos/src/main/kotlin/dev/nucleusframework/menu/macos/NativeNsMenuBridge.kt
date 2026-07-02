package dev.nucleusframework.menu.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

private const val LIBRARY_NAME = "nucleus_menu_macos"

/**
 * Internal JNI bridge for the menu-macos module.
 *
 * All native handles are `jlong` pointers to retained Objective-C objects.
 * Callers must invoke [nativeRelease] when done with a handle.
 */
@Suppress("TooManyFunctions")
internal object NativeNsMenuBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeNsMenuBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // ---- Action callbacks (handle → callback) ----
    private val actionCallbacks = ConcurrentHashMap<Long, () -> Unit>()

    fun setAction(
        handle: Long,
        action: (() -> Unit)?,
    ) {
        if (action != null) {
            actionCallbacks[handle] = action
        } else {
            actionCallbacks.remove(handle)
        }
        if (isLoaded) nativeItemSetHasAction(handle, action != null)
    }

    fun getAction(handle: Long): (() -> Unit)? = actionCallbacks[handle]

    fun removeAction(handle: Long) {
        actionCallbacks.remove(handle)
    }

    fun clearAllActions() {
        actionCallbacks.clear()
    }

    @JvmStatic
    fun onMenuItemAction(handle: Long) {
        val callback = actionCallbacks[handle] ?: return
        SwingUtilities.invokeLater { callback() }
    }

    // ---- Delegate callbacks (menuHandle → delegate) ----
    internal val delegates = ConcurrentHashMap<Long, NsMenuDelegate>()

    fun setDelegateMapping(
        menuHandle: Long,
        delegate: NsMenuDelegate?,
    ) {
        if (delegate != null) {
            delegates[menuHandle] = delegate
        } else {
            delegates.remove(menuHandle)
        }
    }

    fun removeDelegateMapping(menuHandle: Long) {
        delegates.remove(menuHandle)
    }

    @JvmStatic
    fun onMenuWillOpen(menuHandle: Long) {
        val delegate = delegates[menuHandle] ?: return
        val menu = NsMenu.borrowed(menuHandle)
        SwingUtilities.invokeLater { delegate.menuWillOpen(menu) }
    }

    @JvmStatic
    fun onMenuDidClose(menuHandle: Long) {
        val delegate = delegates[menuHandle] ?: return
        val menu = NsMenu.borrowed(menuHandle)
        SwingUtilities.invokeLater { delegate.menuDidClose(menu) }
    }

    @JvmStatic
    fun onMenuNeedsUpdate(menuHandle: Long) {
        val delegate = delegates[menuHandle] ?: return
        val menu = NsMenu.borrowed(menuHandle)
        SwingUtilities.invokeLater { delegate.menuNeedsUpdate(menu) }
    }

    @JvmStatic
    fun onMenuWillHighlightItem(
        menuHandle: Long,
        itemHandle: Long,
    ) {
        val delegate = delegates[menuHandle] ?: return
        val menu = NsMenu.borrowed(menuHandle)
        val item = if (itemHandle != 0L) NsMenuItem.borrowed(itemHandle) else null
        SwingUtilities.invokeLater { delegate.menuWillHighlightItem(menu, item) }
    }

    @JvmStatic
    fun onNumberOfItemsInMenu(menuHandle: Long): Int {
        val delegate = delegates[menuHandle] ?: return -1
        val menu = NsMenu.borrowed(menuHandle)
        return delegate.numberOfItemsInMenu(menu)
    }

    // ---- Lifecycle ----

    @JvmStatic external fun nativeCreateMenu(title: String): Long

    @JvmStatic external fun nativeCreateItem(
        title: String,
        keyEquivalent: String,
    ): Long

    @JvmStatic external fun nativeCreateSeparatorItem(): Long

    @JvmStatic
    external fun nativeCreateSearchFieldItem(
        placeholder: String,
        width: Double,
    ): Long

    @JvmStatic external fun nativeCreateSectionHeader(title: String): Long

    @JvmStatic external fun nativeRelease(handle: Long)

    // ---- Menu properties (consolidated by type) ----

    @JvmStatic external fun nativeMenuGetString(
        handle: Long,
        propId: Int,
    ): String?

    @JvmStatic external fun nativeMenuSetString(
        handle: Long,
        propId: Int,
        value: String,
    )

    @JvmStatic external fun nativeMenuGetBool(
        handle: Long,
        propId: Int,
    ): Boolean

    @JvmStatic external fun nativeMenuSetBool(
        handle: Long,
        propId: Int,
        value: Boolean,
    )

    @JvmStatic external fun nativeMenuGetInt(
        handle: Long,
        propId: Int,
    ): Int

    @JvmStatic external fun nativeMenuSetInt(
        handle: Long,
        propId: Int,
        value: Int,
    )

    @JvmStatic external fun nativeMenuGetFloat(
        handle: Long,
        propId: Int,
    ): Float

    @JvmStatic external fun nativeMenuSetFloat(
        handle: Long,
        propId: Int,
        value: Float,
    )

    // ---- Menu item management ----

    @JvmStatic external fun nativeMenuAddItem(
        menuHandle: Long,
        itemHandle: Long,
    )

    @JvmStatic external fun nativeMenuAddItemWithTitle(
        menuHandle: Long,
        title: String,
        keyEquivalent: String,
    ): Long

    @JvmStatic external fun nativeMenuInsertItem(
        menuHandle: Long,
        itemHandle: Long,
        index: Int,
    )

    @JvmStatic external fun nativeMenuInsertItemWithTitle(
        menuHandle: Long,
        title: String,
        keyEquivalent: String,
        index: Int,
    ): Long

    @JvmStatic external fun nativeMenuRemoveItem(
        menuHandle: Long,
        itemHandle: Long,
    )

    @JvmStatic external fun nativeMenuRemoveItemAtIndex(
        menuHandle: Long,
        index: Int,
    )

    @JvmStatic external fun nativeMenuRemoveAllItems(menuHandle: Long)

    @JvmStatic external fun nativeMenuItemChanged(
        menuHandle: Long,
        itemHandle: Long,
    )

    // ---- Menu finding ----

    @JvmStatic external fun nativeMenuItemWithTag(
        menuHandle: Long,
        tag: Int,
    ): Long

    @JvmStatic external fun nativeMenuItemWithTitle(
        menuHandle: Long,
        title: String,
    ): Long

    @JvmStatic external fun nativeMenuItemAtIndex(
        menuHandle: Long,
        index: Int,
    ): Long

    @JvmStatic external fun nativeMenuGetNumberOfItems(menuHandle: Long): Int

    @JvmStatic external fun nativeMenuGetItems(menuHandle: Long): LongArray

    // ---- Menu indices ----

    @JvmStatic external fun nativeMenuIndexOfItem(
        menuHandle: Long,
        itemHandle: Long,
    ): Int

    @JvmStatic external fun nativeMenuIndexOfItemWithTitle(
        menuHandle: Long,
        title: String,
    ): Int

    @JvmStatic external fun nativeMenuIndexOfItemWithTag(
        menuHandle: Long,
        tag: Int,
    ): Int

    @JvmStatic external fun nativeMenuIndexOfItemWithSubmenu(
        menuHandle: Long,
        submenuHandle: Long,
    ): Int

    // ---- Menu special read-only properties ----

    @JvmStatic external fun nativeMenuGetSize(menuHandle: Long): FloatArray

    @JvmStatic external fun nativeMenuGetHighlightedItem(menuHandle: Long): Long

    @JvmStatic external fun nativeMenuGetSupermenu(menuHandle: Long): Long

    @JvmStatic external fun nativeMenuGetSelectedItems(menuHandle: Long): LongArray

    // ---- Menu methods ----

    @JvmStatic external fun nativeMenuUpdate(menuHandle: Long)

    @JvmStatic external fun nativeMenuSizeToFit(menuHandle: Long)

    @JvmStatic external fun nativeMenuCancelTracking(menuHandle: Long)

    @JvmStatic external fun nativeMenuCancelTrackingWithoutAnimation(menuHandle: Long)

    @JvmStatic external fun nativeMenuPerformActionForItemAtIndex(
        menuHandle: Long,
        index: Int,
    )

    @JvmStatic external fun nativeMenuPopUp(
        menuHandle: Long,
        itemHandle: Long,
        x: Float,
        y: Float,
    ): Boolean

    // ---- Menu bar ----

    @JvmStatic external fun nativeMenuBarIsVisible(): Boolean

    @JvmStatic external fun nativeMenuBarSetVisible(visible: Boolean)

    @JvmStatic external fun nativeMenuBarGetHeight(): Float

    @JvmStatic external fun nativeGetMainMenu(): Long

    @JvmStatic external fun nativeSetMainMenu(menuHandle: Long)

    @JvmStatic external fun nativeSetWindowsMenu(menuHandle: Long)

    @JvmStatic external fun nativeSetHelpMenu(menuHandle: Long)

    // ---- Menu submenu ----

    @JvmStatic external fun nativeMenuSetSubmenuForItem(
        menuHandle: Long,
        submenuHandle: Long,
        itemHandle: Long,
    )

    // ---- Menu delegate ----

    @JvmStatic external fun nativeMenuSetDelegate(
        menuHandle: Long,
        enabled: Boolean,
    )

    // ---- Item properties (consolidated by type) ----

    @JvmStatic external fun nativeItemGetString(
        handle: Long,
        propId: Int,
    ): String?

    @JvmStatic external fun nativeItemSetString(
        handle: Long,
        propId: Int,
        value: String?,
    )

    @JvmStatic external fun nativeItemGetBool(
        handle: Long,
        propId: Int,
    ): Boolean

    @JvmStatic external fun nativeItemSetBool(
        handle: Long,
        propId: Int,
        value: Boolean,
    )

    @JvmStatic external fun nativeItemGetInt(
        handle: Long,
        propId: Int,
    ): Int

    @JvmStatic external fun nativeItemSetInt(
        handle: Long,
        propId: Int,
        value: Int,
    )

    // ---- Item navigation ----

    @JvmStatic external fun nativeItemGetSubmenu(handle: Long): Long

    @JvmStatic external fun nativeItemSetSubmenu(
        handle: Long,
        submenuHandle: Long,
    )

    @JvmStatic external fun nativeItemGetParentItem(handle: Long): Long

    @JvmStatic external fun nativeItemGetMenu(handle: Long): Long

    // ---- Item image ----

    @JvmStatic external fun nativeItemSetImage(
        handle: Long,
        imageType: Int,
        value: String?,
        accessibilityDesc: String?,
    )

    @JvmStatic external fun nativeItemSetStateImage(
        handle: Long,
        stateTarget: Int,
        imageType: Int,
        value: String?,
        accessibilityDesc: String?,
    )

    // ---- Item badge ----

    @JvmStatic external fun nativeItemSetBadge(
        handle: Long,
        badgeType: Int,
        count: Int,
        string: String?,
    )

    // ---- Item action ----

    @JvmStatic external fun nativeItemSetHasAction(
        handle: Long,
        enabled: Boolean,
    )
}

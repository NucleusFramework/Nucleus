package dev.nucleusframework.menu.macos

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin wrapper for AppKit NSMenuItem.
 *
 * Each instance holds a retained native handle. Call [close] when done.
 * Implements [AutoCloseable] for use with Kotlin's `use {}` pattern.
 */
internal class NsMenuItem internal constructor(
    internal val handle: Long,
    private val owned: Boolean = true,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Creates a new NSMenuItem with the given title and optional key equivalent. */
    constructor(title: String, keyEquivalent: String = "") : this(
        NativeNsMenuBridge.nativeCreateItem(title, keyEquivalent),
    )

    override fun close() {
        if (owned && closed.compareAndSet(false, true)) {
            NativeNsMenuBridge.nativeRelease(handle)
        }
    }

    companion object {
        /** Creates a separator menu item. */
        fun separator(): NsMenuItem = NsMenuItem(NativeNsMenuBridge.nativeCreateSeparatorItem())

        /** Creates a section header item (macOS 14+). */
        fun sectionHeader(title: String): NsMenuItem = NsMenuItem(NativeNsMenuBridge.nativeCreateSectionHeader(title))

        /**
         * Creates a search-field item: an NSSearchField hosted in the item's view that filters
         * the sibling items of its menu natively as the user types (title contains query,
         * case/diacritic-insensitive). The filter resets when the menu closes.
         */
        fun searchField(
            placeholder: String,
            width: Double = 230.0,
        ): NsMenuItem = NsMenuItem(NativeNsMenuBridge.nativeCreateSearchFieldItem(placeholder, width))

        /** Creates a non-owning wrapper for use in callbacks. */
        internal fun borrowed(handle: Long): NsMenuItem = NsMenuItem(handle, owned = false)
    }

    // ---- String properties ----

    var title: String
        get() = NativeNsMenuBridge.nativeItemGetString(handle, ItemStringProp.TITLE) ?: ""
        set(value) = NativeNsMenuBridge.nativeItemSetString(handle, ItemStringProp.TITLE, value)

    var keyEquivalent: String
        get() = NativeNsMenuBridge.nativeItemGetString(handle, ItemStringProp.KEY_EQUIVALENT) ?: ""
        set(value) = NativeNsMenuBridge.nativeItemSetString(handle, ItemStringProp.KEY_EQUIVALENT, value)

    var toolTip: String?
        get() = NativeNsMenuBridge.nativeItemGetString(handle, ItemStringProp.TOOLTIP)
        set(value) = NativeNsMenuBridge.nativeItemSetString(handle, ItemStringProp.TOOLTIP, value)

    /** Subtitle text displayed below the title (macOS 14+). */
    var subtitle: String?
        get() = NativeNsMenuBridge.nativeItemGetString(handle, ItemStringProp.SUBTITLE)
        set(value) = NativeNsMenuBridge.nativeItemSetString(handle, ItemStringProp.SUBTITLE, value)

    // ---- Bool properties ----

    var isEnabled: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.ENABLED)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.ENABLED, value)

    var isHidden: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.HIDDEN)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.HIDDEN, value)

    val isHiddenOrHasHiddenAncestor: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.HIDDEN_OR_HAS_HIDDEN_ANCESTOR)

    var isAlternate: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.ALTERNATE)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.ALTERNATE, value)

    val isSeparatorItem: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.IS_SEPARATOR)

    val isHighlighted: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.IS_HIGHLIGHTED)

    val hasSubmenu: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.HAS_SUBMENU)

    /** Whether this item is a section header (macOS 14+). */
    val isSectionHeader: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.IS_SECTION_HEADER)

    var allowsAutomaticKeyEquivalentLocalization: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_LOCALIZATION)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_LOCALIZATION, value)

    var allowsAutomaticKeyEquivalentMirroring: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_MIRRORING)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_MIRRORING, value)

    var allowsKeyEquivalentWhenHidden: Boolean
        get() = NativeNsMenuBridge.nativeItemGetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_WHEN_HIDDEN)
        set(value) = NativeNsMenuBridge.nativeItemSetBool(handle, ItemBoolProp.ALLOWS_KEY_EQ_WHEN_HIDDEN, value)

    // ---- Int properties ----

    var tag: Int
        get() = NativeNsMenuBridge.nativeItemGetInt(handle, ItemIntProp.TAG)
        set(value) = NativeNsMenuBridge.nativeItemSetInt(handle, ItemIntProp.TAG, value)

    var state: NsMenuItemState
        get() = NsMenuItemState.fromNative(NativeNsMenuBridge.nativeItemGetInt(handle, ItemIntProp.STATE))
        set(value) = NativeNsMenuBridge.nativeItemSetInt(handle, ItemIntProp.STATE, value.nativeValue)

    var indentationLevel: Int
        get() = NativeNsMenuBridge.nativeItemGetInt(handle, ItemIntProp.INDENTATION_LEVEL)
        set(value) = NativeNsMenuBridge.nativeItemSetInt(handle, ItemIntProp.INDENTATION_LEVEL, value)

    /** Key equivalent modifier mask. Combine values from [NsEventModifierFlags]. */
    var keyEquivalentModifierMask: Int
        get() = NativeNsMenuBridge.nativeItemGetInt(handle, ItemIntProp.KEY_EQUIVALENT_MODIFIER_MASK)
        set(value) = NativeNsMenuBridge.nativeItemSetInt(handle, ItemIntProp.KEY_EQUIVALENT_MODIFIER_MASK, value)

    // ---- Action callback ----

    /** Callback invoked when the user clicks this item. Dispatched on the Swing EDT. */
    var onAction: (() -> Unit)?
        get() = NativeNsMenuBridge.getAction(handle)
        set(value) = NativeNsMenuBridge.setAction(handle, value)

    // ---- Submenu / navigation ----

    /** The item's submenu. Setting to null removes the submenu. Returned menu must be closed. */
    var submenu: NsMenu?
        get() {
            val h = NativeNsMenuBridge.nativeItemGetSubmenu(handle)
            return if (h != 0L) NsMenu(h) else null
        }
        set(value) = NativeNsMenuBridge.nativeItemSetSubmenu(handle, value?.handle ?: 0L)

    /** The parent item (if this item is in a submenu). Returned item must be closed. */
    val parentItem: NsMenuItem?
        get() {
            val h = NativeNsMenuBridge.nativeItemGetParentItem(handle)
            return if (h != 0L) NsMenuItem(h) else null
        }

    /** The menu that contains this item. Returned menu must be closed. */
    val menu: NsMenu?
        get() {
            val h = NativeNsMenuBridge.nativeItemGetMenu(handle)
            return if (h != 0L) NsMenu(h) else null
        }

    // ---- Image ----

    /** The menu item's image. Set to null to clear. */
    var image: NsMenuItemImage?
        @Deprecated("Write-only from Kotlin. Use the setter.", level = DeprecationLevel.HIDDEN)
        get() = null
        set(value) = setImageNative(value, -1)

    /** Image displayed when state is ON. */
    var onStateImage: NsMenuItemImage?
        @Deprecated("Write-only from Kotlin.", level = DeprecationLevel.HIDDEN)
        get() = null
        set(value) = setImageNative(value, StateImageTarget.ON)

    /** Image displayed when state is OFF. */
    var offStateImage: NsMenuItemImage?
        @Deprecated("Write-only from Kotlin.", level = DeprecationLevel.HIDDEN)
        get() = null
        set(value) = setImageNative(value, StateImageTarget.OFF)

    /** Image displayed when state is MIXED. */
    var mixedStateImage: NsMenuItemImage?
        @Deprecated("Write-only from Kotlin.", level = DeprecationLevel.HIDDEN)
        get() = null
        set(value) = setImageNative(value, StateImageTarget.MIXED)

    private fun setImageNative(
        image: NsMenuItemImage?,
        stateTarget: Int,
    ) {
        val (type, value, desc) =
            when (image) {
                null -> Triple(ImageType.CLEAR, null, null)
                is NsMenuItemImage.Named -> Triple(ImageType.NAMED, image.name, null)
                is NsMenuItemImage.SystemSymbol ->
                    Triple(ImageType.SYSTEM_SYMBOL, image.name, image.accessibilityDescription)
                is NsMenuItemImage.File -> Triple(ImageType.FILE, image.path, null)
            }
        if (stateTarget < 0) {
            NativeNsMenuBridge.nativeItemSetImage(handle, type, value, desc)
        } else {
            NativeNsMenuBridge.nativeItemSetStateImage(handle, stateTarget, type, value, desc)
        }
    }

    // ---- Badge (macOS 14+) ----

    /** The badge displayed on this menu item. Set to null to clear. */
    var badge: NsMenuItemBadge?
        @Deprecated("Write-only from Kotlin.", level = DeprecationLevel.HIDDEN)
        get() = null
        set(value) {
            when (value) {
                null -> NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.CLEAR, 0, null)
                is NsMenuItemBadge.Count ->
                    NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.COUNT, value.count, null)
                is NsMenuItemBadge.Text ->
                    NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.STRING, 0, value.string)
                is NsMenuItemBadge.Alerts ->
                    NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.ALERTS, value.count, null)
                is NsMenuItemBadge.NewItems ->
                    NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.NEW_ITEMS, value.count, null)
                is NsMenuItemBadge.Updates ->
                    NativeNsMenuBridge.nativeItemSetBadge(handle, BadgeType.UPDATES, value.count, null)
            }
        }
}

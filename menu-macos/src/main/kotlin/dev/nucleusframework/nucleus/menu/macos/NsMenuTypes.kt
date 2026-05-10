package dev.nucleusframework.nucleus.menu.macos

/** NSControlStateValue used by NSMenuItem.state. */
enum class NsMenuItemState(
    val nativeValue: Int,
) {
    OFF(0),
    ON(1),
    MIXED(-1),
    ;

    companion object {
        fun fromNative(value: Int): NsMenuItemState = entries.firstOrNull { it.nativeValue == value } ?: OFF
    }
}

/** NSEventModifierFlags bitmask constants for key equivalent modifiers. */
internal object NsEventModifierFlags {
    const val CAPS_LOCK: Int = 1 shl 16
    const val SHIFT: Int = 1 shl 17
    const val CONTROL: Int = 1 shl 18
    const val OPTION: Int = 1 shl 19
    const val COMMAND: Int = 1 shl 20
    const val FUNCTION: Int = 1 shl 23
}

/** NSUserInterfaceLayoutDirection. */
internal enum class NsUserInterfaceLayoutDirection(
    val nativeValue: Int,
) {
    LEFT_TO_RIGHT(0),
    RIGHT_TO_LEFT(1),
    ;

    companion object {
        fun fromNative(value: Int): NsUserInterfaceLayoutDirection =
            entries.firstOrNull { it.nativeValue == value } ?: LEFT_TO_RIGHT
    }
}

/** NSMenuPresentationStyle (macOS 14+). */
internal enum class NsMenuPresentationStyle(
    val nativeValue: Int,
) {
    REGULAR(0),
    PALETTE(1),
    ;

    companion object {
        fun fromNative(value: Int): NsMenuPresentationStyle = entries.firstOrNull { it.nativeValue == value } ?: REGULAR
    }
}

/** NSMenuSelectionMode (macOS 14+). */
internal enum class NsMenuSelectionMode(
    val nativeValue: Int,
) {
    AUTOMATIC(0),
    SELECT_ANY(1),
    SELECT_ONE(2),
    ;

    companion object {
        fun fromNative(value: Int): NsMenuSelectionMode = entries.firstOrNull { it.nativeValue == value } ?: AUTOMATIC
    }
}

/** NSMenuItemBadgeType (macOS 14+). */
@Suppress("MagicNumber")
internal enum class NsMenuItemBadgeType(
    val nativeValue: Int,
) {
    NONE(0),
    UPDATES(1),
    NEW_ITEMS(2),
    ALERTS(3),
    ;

    companion object {
        fun fromNative(value: Int): NsMenuItemBadgeType = entries.firstOrNull { it.nativeValue == value } ?: NONE
    }
}

/** Image source for NSMenuItem image properties. */
sealed class NsMenuItemImage {
    /** Named image from the app bundle or AppKit constants (e.g. "NSActionTemplate"). */
    data class Named(
        val name: String,
    ) : NsMenuItemImage()

    /**
     * SF Symbol image (macOS 11+).
     *
     * Accepts a raw symbol name string or a type-safe [SFSymbol][dev.nucleusframework.nucleus.sfsymbols.SFSymbol]
     * constant from the `sf-symbols` module.
     *
     * ```kotlin
     * // With sf-symbols (type-safe):
     * NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.SCISSORS)
     *
     * // With a raw string:
     * NsMenuItemImage.SystemSymbol("scissors")
     * ```
     */
    data class SystemSymbol(
        val name: String,
        val accessibilityDescription: String? = null,
    ) : NsMenuItemImage() {
        /** Creates an image from a type-safe [SFSymbol][dev.nucleusframework.nucleus.sfsymbols.SFSymbol] constant. */
        constructor(
            symbol: dev.nucleusframework.nucleus.sfsymbols.SFSymbol,
            accessibilityDescription: String? = null,
        ) : this(symbol.symbolName, accessibilityDescription)
    }

    /** Image loaded from a file path. */
    data class File(
        val path: String,
    ) : NsMenuItemImage()
}

// Property ID constants used internally between Kotlin and native code.

internal object MenuStringProp {
    const val TITLE = 1
}

internal object MenuBoolProp {
    const val AUTO_ENABLES_ITEMS = 1
    const val SHOWS_STATE_COLUMN = 2
    const val ALLOWS_CONTEXT_MENU_PLUGINS = 3
}

internal object MenuIntProp {
    const val PRESENTATION_STYLE = 1
    const val SELECTION_MODE = 2
    const val LAYOUT_DIRECTION = 3
}

internal object MenuFloatProp {
    const val MINIMUM_WIDTH = 1
}

internal object ItemStringProp {
    const val TITLE = 1
    const val KEY_EQUIVALENT = 2
    const val TOOLTIP = 3
    const val SUBTITLE = 4
}

internal object ItemBoolProp {
    const val ENABLED = 1
    const val HIDDEN = 2
    const val ALTERNATE = 3
    const val HIDDEN_OR_HAS_HIDDEN_ANCESTOR = 4
    const val IS_SEPARATOR = 5
    const val IS_HIGHLIGHTED = 6
    const val HAS_SUBMENU = 7
    const val IS_SECTION_HEADER = 8
    const val ALLOWS_KEY_EQ_LOCALIZATION = 9
    const val ALLOWS_KEY_EQ_MIRRORING = 10
    const val ALLOWS_KEY_EQ_WHEN_HIDDEN = 11
}

internal object ItemIntProp {
    const val TAG = 1
    const val STATE = 2
    const val INDENTATION_LEVEL = 3
    const val KEY_EQUIVALENT_MODIFIER_MASK = 4
}

// Image type discriminators
internal object ImageType {
    const val CLEAR = 0
    const val NAMED = 1
    const val SYSTEM_SYMBOL = 2
    const val FILE = 3
}

// State image target discriminators
internal object StateImageTarget {
    const val ON = 0
    const val OFF = 1
    const val MIXED = 2
}

// Badge type discriminators
internal object BadgeType {
    const val CLEAR = 0
    const val COUNT = 1
    const val STRING = 2
    const val ALERTS = 3
    const val NEW_ITEMS = 4
    const val UPDATES = 5
}

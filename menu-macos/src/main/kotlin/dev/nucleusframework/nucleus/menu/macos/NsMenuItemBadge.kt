package dev.nucleusframework.nucleus.menu.macos

/**
 * Represents an NSMenuItemBadge (macOS 14+).
 *
 * Badges provide additional quantitative information on menu items.
 * Use the factory methods for predefined badge types that the system
 * automatically localizes and pluralizes.
 */
sealed class NsMenuItemBadge {
    /** Badge displaying a numeric count. */
    data class Count(
        val count: Int,
    ) : NsMenuItemBadge()

    /** Badge displaying a custom string. Must be localized by the caller. */
    data class Text(
        val string: String,
    ) : NsMenuItemBadge()

    /** Alert-style badge with a predefined, system-localized label. */
    data class Alerts(
        val count: Int,
    ) : NsMenuItemBadge()

    /** New-items-style badge with a predefined, system-localized label. */
    data class NewItems(
        val count: Int,
    ) : NsMenuItemBadge()

    /** Updates-style badge with a predefined, system-localized label. */
    data class Updates(
        val count: Int,
    ) : NsMenuItemBadge()

    companion object {
        fun alerts(count: Int): NsMenuItemBadge = Alerts(count)

        fun newItems(count: Int): NsMenuItemBadge = NewItems(count)

        fun updates(count: Int): NsMenuItemBadge = Updates(count)
    }
}

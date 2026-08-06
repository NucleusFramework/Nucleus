package dev.nucleusframework.menu.macos

/**
 * Represents an NSMenuItemBadge (macOS 14+).
 *
 * Badges provide additional quantitative information on menu items.
 * Use the factory methods for predefined badge types that the system
 * automatically localizes and pluralizes.
 */
public sealed class NsMenuItemBadge {
    /** Badge displaying a numeric count. */
    public data class Count(
        val count: Int,
    ) : NsMenuItemBadge()

    /** Badge displaying a custom string. Must be localized by the caller. */
    public data class Text(
        val string: String,
    ) : NsMenuItemBadge()

    /** Alert-style badge with a predefined, system-localized label. */
    public data class Alerts(
        val count: Int,
    ) : NsMenuItemBadge()

    /** New-items-style badge with a predefined, system-localized label. */
    public data class NewItems(
        val count: Int,
    ) : NsMenuItemBadge()

    /** Updates-style badge with a predefined, system-localized label. */
    public data class Updates(
        val count: Int,
    ) : NsMenuItemBadge()

    public companion object {
        public fun alerts(count: Int): NsMenuItemBadge = Alerts(count)

        public fun newItems(count: Int): NsMenuItemBadge = NewItems(count)

        public fun updates(count: Int): NsMenuItemBadge = Updates(count)
    }
}

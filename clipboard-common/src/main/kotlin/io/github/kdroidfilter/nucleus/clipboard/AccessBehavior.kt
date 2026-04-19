package io.github.kdroidfilter.nucleus.clipboard

/**
 * Policy for background pasteboard access — maps to macOS 15.4+ `NSPasteboard.AccessBehavior`.
 * No-op on platforms without a privacy model.
 */
enum class AccessBehavior {
    /** Read freely (legacy macOS default, still the default on other platforms). */
    AlwaysAllow,

    /** Show a permission prompt on every read of pasteboard contents. */
    AskEveryTime,

    /** Deny programmatic reads without user interaction. */
    AlwaysDeny,
}

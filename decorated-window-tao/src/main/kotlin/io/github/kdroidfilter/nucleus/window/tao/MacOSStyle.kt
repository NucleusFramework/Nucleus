package io.github.kdroidfilter.nucleus.window.tao

/**
 * macOS visual treatment applied to a [DecoratedWindow].
 *
 * The "modern" treatment opts the window into the macOS 26 (Tahoe)
 * Liquid-Glass / large-corner-radius chrome by attaching an invisible
 * `NSToolbar`. It is silently ignored on macOS releases that don't honour the
 * new corner radius (≤ Sequoia), so [Auto] is the correct default for most
 * applications: modern look on Tahoe, classic chrome on older systems.
 */
enum class MacOSStyle {
    /** Modern (Tahoe) on macOS 26+, classic chrome on older releases. */
    Auto,

    /** Force classic chrome, even on macOS 26+. */
    Classic,

    /** Force the modern treatment (NSToolbar attached) regardless of OS. */
    Modern,
}

internal fun MacOSStyle.shouldApplyLargeCornerRadius(): Boolean = when (this) {
    MacOSStyle.Auto -> NativeMetalBridge.nativeIsMacOSTahoeOrLater()
    MacOSStyle.Classic -> false
    MacOSStyle.Modern -> true
}

package io.github.kdroidfilter.nucleus.window.tao

/**
 * macOS visual treatment applied to a [DecoratedWindow] at construction.
 *
 * Imperative counterpart of [io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius]
 * — the Modifier is the recommended path (works the same on jbr/jni/tao).
 * Tao defaults to [Classic] so the swap-in API behaves identically to the
 * AWT backends: opt in to the large corner radius via the Modifier on
 * `TitleBar { … }`, not via this enum.
 */
enum class MacOSStyle {
    /** Modern (Tahoe) on macOS 26+, classic chrome on older releases. */
    Auto,

    /** Force classic chrome, even on macOS 26+. */
    Classic,

    /** Force the modern treatment (NSToolbar attached) regardless of OS. */
    Modern,
}

internal fun MacOSStyle.shouldApplyLargeCornerRadius(): Boolean =
    when (this) {
        MacOSStyle.Auto -> NativeMetalBridge.nativeIsMacOSTahoeOrLater()
        MacOSStyle.Classic -> false
        MacOSStyle.Modern -> true
    }

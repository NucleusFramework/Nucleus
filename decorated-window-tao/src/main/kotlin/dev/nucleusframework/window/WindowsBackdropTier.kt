package dev.nucleusframework.window

// Wire values shared with the native TIER_* constants in
// nucleus_tao_windows_deco.c — named so the enum reads as the mapping it is.
private const val TIER_AUTO = 0
private const val TIER_MODERN = 1
private const val TIER_LEGACY_MICA = 2
private const val TIER_WIN10_ACRYLIC = 3

/**
 * Which implementation [WindowsBackdrop] uses, when you do not want the
 * automatic choice.
 *
 * The backdrop API spans three Windows generations with visibly different
 * results, and only one of them runs on any given machine — which makes the
 * other two easy to ship broken. Pinning a tier renders that tier's appearance
 * on any Windows 11 box, so the fallbacks can be looked at, screenshotted and
 * reviewed without hunting down a Windows 10 install.
 *
 * Pinning a tier the running OS does not support leaves the window opaque, the
 * same as an unsupported style. Ship [Auto].
 */
public enum class WindowsBackdropTier(
    // Explicit wire value for the JNI call, as everywhere else in this API.
    internal val nativeValue: Int,
) {
    /** Use the best implementation this OS supports. The only sane shipping value. */
    Auto(TIER_AUTO),

    /** `DWMWA_SYSTEMBACKDROP_TYPE` — Windows 11 22H2 and later. */
    Modern(TIER_MODERN),

    /**
     * `DWMWA_MICA_EFFECT` — Windows 11 before 22H2. Real Mica, but a single
     * material: [WindowsBackdropStyle.Mica] and [WindowsBackdropStyle.MicaAlt]
     * are indistinguishable, and [WindowsBackdropStyle.Acrylic] falls through
     * to [Windows10Acrylic].
     */
    LegacyMica(TIER_LEGACY_MICA),

    /**
     * `SetWindowCompositionAttribute` acrylic — Windows 10. Not Mica: it blurs
     * what is behind the window rather than tinting from the wallpaper, and it
     * takes the tint colour passed to [WindowsBackdrop].
     */
    Windows10Acrylic(TIER_WIN10_ACRYLIC),
}

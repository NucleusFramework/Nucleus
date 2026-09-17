package dev.nucleusframework.window

/**
 * Appearance the OS applies to this window's native surfaces.
 */
public enum class WindowAppearanceMode(
    // Explicit wire value for the JNI call: reordering the entries must not
    // silently swap light and dark.
    internal val nativeValue: Int,
) {
    /** Follow the OS setting (default). */
    System(0),

    /** Force the light appearance regardless of the OS setting. */
    Light(1),

    /** Force the dark appearance regardless of the OS setting. */
    Dark(2),
}

package dev.nucleusframework.globalhotkey

/**
 * Modifier keys for global hotkey registration.
 *
 * Combine using addition: `HotKeyModifier.CONTROL + HotKeyModifier.SHIFT`
 */
@Suppress("MagicNumber")
public enum class HotKeyModifier(
    internal val nativeFlag: Int,
) {
    /** Alt key (Option on macOS). */
    ALT(0x0001),

    /** Control key. */
    CONTROL(0x0002),

    /** Shift key. */
    SHIFT(0x0004),

    /** Meta key (Windows key on Windows, Command on macOS). */
    META(0x0008),
    ;

    public operator fun plus(other: HotKeyModifier): Int = nativeFlag or other.nativeFlag
}

/** Combines an existing modifier bitmask with another modifier. */
public operator fun Int.plus(modifier: HotKeyModifier): Int = this or modifier.nativeFlag

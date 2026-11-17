package dev.nucleusframework.tray.menu

/**
 * Represents a keyboard shortcut hint displayed alongside a menu item.
 * This is display-only — it does not register any global hotkey handler.
 *
 * On macOS, the shortcut renders as native key equivalent glyphs (e.g. ⌘S, ⇧⌘N).
 * On Linux, the shortcut is serialized as a DBusMenu `shortcut` property
 * and rendered by the desktop environment's indicator renderer (KDE, GNOME, etc.).
 * On Windows, the shortcut renders as right-aligned accelerator text (e.g. Ctrl+S, Ctrl+Shift+N)
 * using the native Win32 tab-separated text convention.
 *
 * Example:
 * ```
 * Item("Save", shortcut = KeyShortcut(Key.S, meta = true)) { ... }
 * Item("New Window", shortcut = KeyShortcut(Key.N, meta = true, shift = true)) { ... }
 * ```
 */
public data class KeyShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /**
     * Returns the macOS NSEventModifierFlags bitmask for this shortcut.
     */
    internal fun toMacModifierMask(): Long {
        var mask = 0L
        if (meta) mask = mask or (1L shl 20) // NSEventModifierFlagCommand
        if (shift) mask = mask or (1L shl 17) // NSEventModifierFlagShift
        if (alt) mask = mask or (1L shl 19) // NSEventModifierFlagOption
        if (ctrl) mask = mask or (1L shl 18) // NSEventModifierFlagControl
        return mask
    }

    /**
     * Returns the key equivalent string for macOS NSMenuItem.
     * Lowercase letter for regular key, special Unicode for function/special keys.
     */
    internal fun toMacKeyEquivalent(): String = key.macKeyEquivalent

    /**
     * Returns the DBusMenu key name for Linux shortcut hints.
     */
    internal fun toLinuxKey(): String = key.linuxKeyName

    /**
     * Returns the Windows accelerator display text (e.g. "Ctrl+S", "Ctrl+Shift+N").
     * Used with the Win32 tab-separated text convention for menu items.
     */
    internal fun toWindowsAcceleratorText(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (meta) parts.add("Win")
        if (alt) parts.add("Alt")
        if (shift) parts.add("Shift")
        parts.add(key.windowsDisplayName)
        return parts.joinToString("+")
    }
}

/**
 * Keyboard keys that can be used in [KeyShortcut].
 *
 * @property macKeyEquivalent The string value passed to NSMenuItem.setKeyEquivalent on macOS.
 * @property linuxKeyName The DBusMenu key name string for Linux (follows XKB naming).
 * @property windowsDisplayName The display name shown in Win32 menu accelerator text.
 */
public enum class Key(
    internal val macKeyEquivalent: String,
    internal val linuxKeyName: String,
    internal val windowsDisplayName: String,
) {
    A("a", "a", "A"),
    B("b", "b", "B"),
    C("c", "c", "C"),
    D("d", "d", "D"),
    E("e", "e", "E"),
    F("f", "f", "F"),
    G("g", "g", "G"),
    H("h", "h", "H"),
    I("i", "i", "I"),
    J("j", "j", "J"),
    K("k", "k", "K"),
    L("l", "l", "L"),
    M("m", "m", "M"),
    N("n", "n", "N"),
    O("o", "o", "O"),
    P("p", "p", "P"),
    Q("q", "q", "Q"),
    R("r", "r", "R"),
    S("s", "s", "S"),
    T("t", "t", "T"),
    U("u", "u", "U"),
    V("v", "v", "V"),
    W("w", "w", "W"),
    X("x", "x", "X"),
    Y("y", "y", "Y"),
    Z("z", "z", "Z"),

    Num0("0", "0", "0"),
    Num1("1", "1", "1"),
    Num2("2", "2", "2"),
    Num3("3", "3", "3"),
    Num4("4", "4", "4"),
    Num5("5", "5", "5"),
    Num6("6", "6", "6"),
    Num7("7", "7", "7"),
    Num8("8", "8", "8"),
    Num9("9", "9", "9"),

    // Function keys (AppKit private-use Unicode)
    F1("\uF704", "F1", "F1"),
    F2("\uF705", "F2", "F2"),
    F3("\uF706", "F3", "F3"),
    F4("\uF707", "F4", "F4"),
    F5("\uF708", "F5", "F5"),
    F6("\uF709", "F6", "F6"),
    F7("\uF70A", "F7", "F7"),
    F8("\uF70B", "F8", "F8"),
    F9("\uF70C", "F9", "F9"),
    F10("\uF70D", "F10", "F10"),
    F11("\uF70E", "F11", "F11"),
    F12("\uF70F", "F12", "F12"),

    // Special keys
    Return("\r", "Return", "Enter"),
    Tab("\t", "Tab", "Tab"),
    Space(" ", "space", "Space"),
    Escape("\u001B", "Escape", "Esc"),
    Delete("\u007F", "BackSpace", "Backspace"),
    ForwardDelete("\uF728", "Delete", "Del"),
    UpArrow("\uF700", "Up", "Up"),
    DownArrow("\uF701", "Down", "Down"),
    LeftArrow("\uF702", "Left", "Left"),
    RightArrow("\uF703", "Right", "Right"),
    Home("\uF729", "Home", "Home"),
    End("\uF72B", "End", "End"),
    PageUp("\uF72C", "Page_Up", "PgUp"),
    PageDown("\uF72D", "Page_Down", "PgDn"),
    Minus("-", "minus", "-"),
    Equal("=", "equal", "="),
    LeftBracket("[", "bracketleft", "["),
    RightBracket("]", "bracketright", "]"),
    Backslash("\\", "backslash", "\\"),
    Semicolon(";", "semicolon", ";"),
    Quote("'", "apostrophe", "'"),
    Comma(",", "comma", ","),
    Period(".", "period", "."),
    Slash("/", "slash", "/"),
    Backquote("`", "grave", "`"),
}

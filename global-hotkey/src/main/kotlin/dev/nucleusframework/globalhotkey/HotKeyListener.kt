package dev.nucleusframework.globalhotkey

/** Callback invoked when a registered global hotkey is pressed. */
public fun interface HotKeyListener {
    /**
     * Called when the hotkey is triggered.
     *
     * @param keyCode the virtual key code that was pressed.
     * @param modifiers the modifier bitmask that was active.
     */
    public fun onHotKey(
        keyCode: Int,
        modifiers: Int,
    )
}

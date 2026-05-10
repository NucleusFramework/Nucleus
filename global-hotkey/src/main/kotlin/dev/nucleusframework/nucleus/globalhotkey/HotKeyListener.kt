package dev.nucleusframework.nucleus.globalhotkey

/** Callback invoked when a registered global hotkey is pressed. */
fun interface HotKeyListener {
    /**
     * Called when the hotkey is triggered.
     *
     * @param keyCode the virtual key code that was pressed.
     * @param modifiers the modifier bitmask that was active.
     */
    fun onHotKey(
        keyCode: Int,
        modifiers: Int,
    )
}

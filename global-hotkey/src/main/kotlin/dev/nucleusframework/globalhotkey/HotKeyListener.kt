package dev.nucleusframework.globalhotkey

/**
 * Callback invoked when a registered global hotkey is pressed.
 *
 * Always invoked on the host's UI thread (see [dev.nucleusframework.core.runtime.NucleusUiThread]):
 * the Tao main thread under `nucleusApplication`, the AWT event dispatch thread otherwise.
 * Never called on the native thread that received the key press. A press still queued
 * when [GlobalHotKeyManager.unregister] or [GlobalHotKeyManager.shutdown] returns is dropped.
 */
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

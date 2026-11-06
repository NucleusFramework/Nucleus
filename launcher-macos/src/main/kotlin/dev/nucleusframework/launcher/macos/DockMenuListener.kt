package dev.nucleusframework.launcher.macos

/** Listener for dock menu item clicks. */
public fun interface DockMenuListener {
    /**
     * Called when the user clicks a dock menu item.
     *
     * Invoked on the host's UI thread (the Tao main thread under Nucleus, the
     * AWT EDT in a plain Swing / Compose Desktop host).
     */
    public fun onItemClicked(itemId: Int)
}

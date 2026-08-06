package dev.nucleusframework.launcher.macos

/** Listener for dock menu item clicks. */
public fun interface DockMenuListener {
    /** Called when the user clicks a dock menu item. Invoked on the Swing EDT. */
    public fun onItemClicked(itemId: Int)
}

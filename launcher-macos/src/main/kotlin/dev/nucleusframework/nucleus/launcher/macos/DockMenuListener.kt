package dev.nucleusframework.nucleus.launcher.macos

/** Listener for dock menu item clicks. */
fun interface DockMenuListener {
    /** Called when the user clicks a dock menu item. Invoked on the Swing EDT. */
    fun onItemClicked(itemId: Int)
}

package dev.nucleusframework.launcher.windows

/**
 * Callback for thumbnail toolbar button clicks.
 *
 * Called on the AWT Event Dispatch Thread when a thumbnail toolbar button is clicked.
 * The native WndProc invokes [onThumbButtonClick] via JNI.
 */
public fun interface ThumbBarClickListener {
    public fun onThumbButtonClick(buttonId: Int)
}

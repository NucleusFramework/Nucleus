package dev.nucleusframework.nucleus.launcher.windows

/**
 * Callback for thumbnail toolbar button clicks.
 *
 * Called on the AWT Event Dispatch Thread when a thumbnail toolbar button is clicked.
 * The native WndProc invokes [onThumbButtonClick] via JNI.
 */
fun interface ThumbBarClickListener {
    fun onThumbButtonClick(buttonId: Int)
}

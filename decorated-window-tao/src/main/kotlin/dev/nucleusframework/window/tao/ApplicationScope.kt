package dev.nucleusframework.window.tao

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Scope exposed by [taoApplication]. Mirrors `androidx.compose.ui.window.ApplicationScope`
 * so call sites can stay nearly identical between the AWT-based backends
 * (removed in 2.6) and the Tao backend.
 *
 * On macOS, Cmd+Q and Dock → Quit request a close from every open window.
 * Each window's `onCloseRequest` can confirm or cancel the close, just as on
 * Windows and Linux. When no windows are open, Quit exits the event loop.
 */
public interface ApplicationScope {
    /** Posts an exit request to the Tao event loop, unblocking [taoApplication]. */
    public fun exitApplication()

    /** The underlying Tao application instance. Most users won't need this. */
    public val taoApplication: TaoApplication
}

internal class ComposableApplicationScope(
    override val taoApplication: TaoApplication,
) : ApplicationScope {
    var isOpen by mutableStateOf(true)

    override fun exitApplication() {
        isOpen = false
    }
}

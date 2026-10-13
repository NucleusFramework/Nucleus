package dev.nucleusframework.window.tao

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Scope exposed by [taoApplication]. Mirrors `androidx.compose.ui.window.ApplicationScope`
 * so call sites can stay nearly identical between the AWT-based backends
 * (removed in 2.6) and the Tao backend.
 *
 * On macOS, Cmd+Q, Dock → Quit and logout / restart / shutdown request a close
 * from every open window, newest first — the same `onCloseRequest` the close
 * button runs, so it can confirm or cancel. The app exits once every window
 * closed; one that stays open cancels the quit. Windows the framework owns
 * (workspace satellites, tab windows) are left as they are. While a quit is
 * in progress [TaoApplication.isQuitting] is `true`, so a hide-to-tray
 * `onCloseRequest` can let it through. [exitApplication] called from such a
 * close request is that window's consent: the app still exits only once no
 * other window refused.
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
        if (taoApplication.consentToQuit()) return
        isOpen = false
    }
}

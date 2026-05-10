package dev.nucleusframework.nucleus.window.tao

/**
 * Scope exposed by [taoApplication]. Mirrors `androidx.compose.ui.window.ApplicationScope`
 * so call sites can stay nearly identical between the AWT-based backends
 * (`decorated-window-jni`, `decorated-window-jbr`) and the Tao backend.
 */
interface ApplicationScope {
    /** Posts an exit request to the Tao event loop, unblocking [taoApplication]. */
    fun exitApplication()

    /** The underlying Tao application instance. Most users won't need this. */
    val taoApplication: TaoApplication
}

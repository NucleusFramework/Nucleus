package io.github.kdroidfilter.nucleus.window.tao

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

internal class ApplicationScopeImpl(
    override val taoApplication: TaoApplication,
) : ApplicationScope {
    override fun exitApplication() {
        taoApplication.exit()
    }
}

/**
 * Top-level launcher for the Tao backend. Equivalent to Compose Desktop's
 * `application { }` block but driven by the Tao event loop instead of AWT.
 *
 * Must be called from the macOS main thread (process thread 0). In a GraalVM
 * native-image build this is automatic; on a regular JVM, launch with
 * `-XstartOnFirstThread`.
 *
 * The lambda body runs **once** when the Tao event loop has finished
 * initialising. Open one or more windows via [DecoratedWindow] (or the
 * lower-level [TaoApplication.openWindow]) and they will live until the user
 * closes them or [exitApplication] is called.
 */
fun taoApplication(content: ApplicationScope.() -> Unit) {
    // CRITICAL ORDER: force-load the Tao native library FIRST. Its `+load`
    // method sets `ApplePressAndHoldEnabled = YES` in NSUserDefaults; that
    // value is read by AppKit's `NSTextInputContext` the first time it
    // initialises. If AWT touches AppKit before we do (e.g. through any
    // Compose-Foundation static init), AppKit caches the press-and-hold
    // flag as NO and the macOS accent picker never appears even after a
    // later setBool flip.
    check(NativeTaoBridge.isLoaded) {
        "nucleus_tao native library is not available — did you run on macOS arm64/x86_64?"
    }

    TaoApplication.run { app ->
        ApplicationScopeImpl(app).content()
    }
}

package dev.nucleusframework.share

/**
 * The system share sheet.
 *
 * | Platform | Share UI |
 * |---|---|
 * | Android | Android Sharesheet (`Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` in a chooser) |
 * | iOS | `UIActivityViewController` |
 * | macOS | `NSSharingServicePicker` |
 * | Windows | Share UI (`DataTransferManager`) |
 * | Linux | XDG desktop portal: "Open With" for one file or URL, "save files" otherwise; `xdg-open` fallback |
 * | Web (js, wasmJs) | Web Share API (`navigator.share`) |
 *
 * ```kotlin
 * scope.launch {
 *     ShareSheet.share {
 *         title = "Share"
 *         text("Cross-platform native share sheet")
 *         url("https://nucleusframework.dev")
 *     }
 * }
 * ```
 *
 * Desktop and Android have overloads naming the parent window or context
 * (`share(request, parent)` / `share(request, context)`); without one, the frontmost
 * window of the app is used.
 */
public object ShareSheet {
    /** Whether this platform can present a share UI (on desktop: whether the native bridge loaded). */
    public val isSupported: Boolean
        get() = isPlatformShareSupported

    /**
     * Presents the share UI for [request] and returns once it is on screen — on Android
     * and Linux, once the request is dispatched; on the web, once the sheet is gone. It
     * does not report which target the user picked, nor whether they cancelled.
     *
     * On the web, call it from a user gesture (a click handler): browsers refuse to open
     * the sheet otherwise.
     *
     * Safe to call from any dispatcher: the platform hops to its UI thread itself.
     *
     * @throws ShareException when the request is invalid or the UI could not be shown
     */
    public suspend fun share(request: ShareRequest) {
        request.validate()
        platformShare(request)
    }

    /** Builds the request with [shareRequest] and [shares][share] it. */
    public suspend fun share(block: ShareRequestBuilder.() -> Unit): Unit = share(shareRequest(block))
}

internal expect val isPlatformShareSupported: Boolean

/** Presents an already [validated][validate] request. */
internal expect suspend fun platformShare(request: ShareRequest)

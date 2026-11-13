package dev.nucleusframework.widget.windows

import dev.nucleusframework.widget.ExperimentalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [NucleusWidget] that shows a web page on the Windows 11 widgets board: a Windows-only feature
 * (WidgetKit and Glance have no web widgets), so it lives here rather than in the common API.
 *
 * The page replaces the widget's content where the board shows web content (Windows documents it
 * for the European Economic Area); elsewhere the widget's content is shown as usual, so keep it a
 * meaningful fallback. On other platforms the interface is simply never consulted.
 *
 * ```kotlin
 * class NewsWidget : NucleusWidget(), WindowsWebWidget {
 *     override suspend fun provideWidget(id: WidgetId) = provideContent { Text("Latest news") }
 *     override fun webUrl(id: WidgetId, state: WidgetState) = "https://news.example.com/widget"
 * }
 * ```
 */
@ExperimentalNucleusWidgetApi
public interface WindowsWebWidget {
    /** The page widget [id] shows, read on every render; `null` shows the widget's content. */
    public fun webUrl(
        id: WidgetId,
        state: WidgetState,
    ): String?

    /**
     * Called with each message the page of widget [id] posts
     * (`window.chrome.webview.postMessage`). Answer with [sendWebWidgetMessage].
     */
    public suspend fun onWebMessage(
        id: WidgetId,
        message: String,
    ) {
    }

    /**
     * Answers a request of the page of widget [id] that matches the widget's web request filter
     * (the plugin's `webRequestFilter`). Return `null` to let it go to the network.
     */
    public suspend fun onWebRequest(
        id: WidgetId,
        request: WebWidgetRequest,
    ): WebWidgetResponse? = null
}

/** A request made by the page of a [WindowsWebWidget], as seen by [WindowsWebWidget.onWebRequest]. */
@ExperimentalNucleusWidgetApi
public class WebWidgetRequest(
    /** The requested URI. */
    public val uri: String,
    /** The HTTP method. */
    public val method: String,
    /** The request headers. */
    public val headers: Map<String, String>,
    /** The request body, if any. */
    public val body: ByteArray?,
)

/** The answer to a [WebWidgetRequest]. */
@ExperimentalNucleusWidgetApi
public class WebWidgetResponse(
    /** The response body. */
    public val body: ByteArray?,
    /** The HTTP status code. */
    public val statusCode: Int = 200,
    /** The HTTP reason phrase. */
    public val reasonPhrase: String = "OK",
    /** The response headers, e.g. `Content-Type`. */
    public val headers: Map<String, String> = emptyMap(),
)

/**
 * Sends [message] to the page of the [WindowsWebWidget] [id] (its `window.chrome.webview`
 * `message` event). Needs package identity, like every widget call.
 */
@ExperimentalNucleusWidgetApi
public suspend fun sendWebWidgetMessage(
    id: WidgetId,
    message: String,
) {
    withContext(Dispatchers.IO) { WidgetManager.getDefault().sendMessageToContent(id.value, message) }
}

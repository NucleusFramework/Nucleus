package dev.nucleusframework.share

/**
 * What to share and how to present it.
 *
 * @property items the payload, in order. Texts and URLs are joined by newlines where
 * the platform takes a single text (Android, Windows).
 * @property title the chooser title where the platform shows one (Android chooser and
 * preview, Windows Share UI, Linux save dialog); ignored on macOS and iOS. On the web it
 * is the shared data's title when there is no [subject].
 * @property subject secondary metadata some receivers use (an e-mail subject on
 * Android, the description on Windows, the shared data's title on the web); many ignore it.
 */
public data class ShareRequest(
    public val items: List<ShareItem>,
    public val title: String? = null,
    public val subject: String? = null,
)

/** Builds a [ShareRequest]: `shareRequest { title = "Share"; text("Hello"); url("https://…") }`. */
public fun shareRequest(block: ShareRequestBuilder.() -> Unit): ShareRequest =
    ShareRequestBuilder().apply(block).build()

/** Receiver of [shareRequest]. */
public class ShareRequestBuilder internal constructor() {
    private val items = mutableListOf<ShareItem>()

    /** See [ShareRequest.title]. */
    public var title: String? = null

    /** See [ShareRequest.subject]. */
    public var subject: String? = null

    /** Adds plain text. */
    public fun text(text: String) {
        items += ShareItem.Text(text)
    }

    /** Adds a link. */
    public fun url(url: String) {
        items += ShareItem.Url(url)
    }

    /** Adds a local file, see [ShareItem.File]. */
    public fun file(
        path: String,
        mimeType: String? = null,
    ) {
        items += ShareItem.File(path, mimeType)
    }

    /** Adds a file URI, see [ShareItem.FileUri]. */
    public fun fileUri(
        uri: String,
        mimeType: String? = null,
    ) {
        items += ShareItem.FileUri(uri, mimeType)
    }

    /** Adds any [ShareItem]. */
    public fun item(item: ShareItem) {
        items += item
    }

    internal fun build(): ShareRequest = ShareRequest(items.toList(), title, subject)
}

/** Rejects what no platform could share, before anything reaches the native side. */
internal fun ShareRequest.validate() {
    if (items.isEmpty()) throw ShareException(ShareError.Empty)
    for (item in items) {
        val blank =
            when (item) {
                is ShareItem.Text -> item.text.isBlank()
                is ShareItem.Url -> item.url.isBlank()
                is ShareItem.File -> item.path.isBlank() || item.mimeType?.isBlank() == true
                is ShareItem.FileUri -> item.uri.isBlank() || item.mimeType?.isBlank() == true
            }
        if (blank) throw ShareException(ShareError.InvalidItem, "Blank share item: $item")
    }
}

/** Texts and URLs joined by newlines, in payload order; `null` when there are none. */
internal fun ShareRequest.sharedText(): String? =
    items
        .mapNotNull {
            when (it) {
                is ShareItem.Text -> it.text
                is ShareItem.Url -> it.url
                else -> null
            }
        }.takeIf { it.isNotEmpty() }
        ?.joinToString("\n")

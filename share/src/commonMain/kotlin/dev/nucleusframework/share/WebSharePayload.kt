package dev.nucleusframework.share

/**
 * A [ShareRequest] as Web Share API `ShareData`.
 *
 * `title` is what receivers use as a subject (there is no chooser title on the web), so the
 * request's subject wins over its title. The first URL is the payload's `url`; the other
 * URLs stay in `text` with the texts, in order. Files are URIs the browser can `fetch`.
 */
internal class WebSharePayload(
    val title: String?,
    val text: String?,
    val url: String?,
    val files: List<ShareItem.FileUri>,
)

/** @throws ShareException for a local path: a web page has no filesystem to read it from. */
internal fun ShareRequest.toWebSharePayload(): WebSharePayload {
    val urlIndex = items.indexOfFirst { it is ShareItem.Url }
    val text =
        items
            .filterIndexed { index, _ -> index != urlIndex }
            .mapNotNull { item ->
                when (item) {
                    is ShareItem.Text -> item.text
                    is ShareItem.Url -> item.url
                    is ShareItem.File ->
                        throw ShareException(
                            ShareError.UnsupportedItem,
                            "A web page cannot read ${item.path}: share a blob:, data: or https: URI with FileUri",
                        )
                    is ShareItem.FileUri -> null
                }
            }.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    return WebSharePayload(
        title = subject ?: title,
        text = text,
        url = (items.getOrNull(urlIndex) as? ShareItem.Url)?.url,
        files = items.filterIsInstance<ShareItem.FileUri>(),
    )
}

package dev.nucleusframework.share

/** One element of a share payload. */
public sealed interface ShareItem {
    /** Plain text. */
    public data class Text(
        public val text: String,
    ) : ShareItem

    /**
     * A web or app link. Android's `ACTION_SEND` has no link field, so there it travels
     * as text; Windows also offers it as the payload's web link.
     */
    public data class Url(
        public val url: String,
    ) : ShareItem

    /**
     * A file on the local filesystem.
     *
     * On Android the file is copied into the app's cache and served to the receiving app
     * through the library's own content provider, so any path the app can read works.
     *
     * @property mimeType a hint for the receivers; guessed from the extension when `null`.
     * Only Android uses it: the desktop and iOS share UIs derive the type themselves.
     */
    public data class File(
        public val path: String,
        public val mimeType: String? = null,
    ) : ShareItem

    /**
     * A platform URI to a file: typically an Android `content://` URI the app owns or
     * received, or a `file://` URI anywhere.
     *
     * @property mimeType a hint for the receivers; only Android uses it.
     */
    public data class FileUri(
        public val uri: String,
        public val mimeType: String? = null,
    ) : ShareItem
}

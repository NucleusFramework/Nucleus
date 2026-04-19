package io.github.kdroidfilter.nucleus.clipboard

import io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardWritePayload
import java.nio.file.Path

/**
 * Builder for a multi-format clipboard write. Any non-null property is published
 * atomically on the same pasteboard item, so consumers can pick the richest
 * representation they understand.
 */
class ClipboardWriteScope internal constructor() {
    /** UTF-8 plain text. Written as `public.utf8-plain-text` on macOS. */
    var text: String? = null

    /** UTF-8 HTML fragment. Written as `public.html` on macOS (no CF_HTML wrapper). */
    var html: String? = null

    /** UTF-8 RTF payload. Written as `public.rtf` on macOS. */
    var rtf: String? = null

    /** PNG-encoded image bytes. Published alongside a TIFF representation on macOS. */
    var imagePng: ByteArray? = null

    /** Absolute file paths. Written as `public.file-url` NSURLs on macOS. */
    var files: List<Path>? = null

    internal fun toPayload(): ClipboardWritePayload =
        ClipboardWritePayload(
            text = text,
            html = html,
            rtf = rtf,
            imagePng = imagePng,
            files = files,
        )
}

package io.github.kdroidfilter.nucleus.clipboard.internal

import java.nio.file.Path

/**
 * Frozen snapshot passed from [io.github.kdroidfilter.nucleus.clipboard.Clipboard]
 * to a [ClipboardBackend]. Part of the backend SPI; not intended for direct use
 * by application code.
 */
data class ClipboardWritePayload(
    val text: String?,
    val html: String?,
    val rtf: String?,
    val imagePng: ByteArray?,
    val files: List<Path>?,
) {
    val isEmpty: Boolean
        get() = text == null && html == null && rtf == null && imagePng == null && files.isNullOrEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardWritePayload) return false
        if (text != other.text) return false
        if (html != other.html) return false
        if (rtf != other.rtf) return false
        if (!imagePng.contentEqualsOrBothNull(other.imagePng)) return false
        if (files != other.files) return false
        return true
    }

    override fun hashCode(): Int {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (html?.hashCode() ?: 0)
        result = 31 * result + (rtf?.hashCode() ?: 0)
        result = 31 * result + (imagePng?.contentHashCode() ?: 0)
        result = 31 * result + (files?.hashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> this.contentEquals(other)
        }
}

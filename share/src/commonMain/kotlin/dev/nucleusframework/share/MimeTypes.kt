package dev.nucleusframework.share

/**
 * The single MIME type an Android `ACTION_SEND` intent carries for [mimeTypes]: the
 * type itself for one file, the shared top-level type with a wildcard subtype when all
 * files agree on it, [ANY_MIME_TYPE] otherwise — the rule Android's own senders follow.
 */
internal fun primaryMimeType(
    mimeTypes: List<String?>,
    hasText: Boolean,
): String {
    if (mimeTypes.isEmpty()) return if (hasText) "text/plain" else ANY_MIME_TYPE
    val parsed =
        mimeTypes.map { mimeType ->
            mimeType?.takeIf { it.indexOf('/') in 1 until it.lastIndex } ?: return ANY_MIME_TYPE
        }
    val primaries = parsed.map { it.substringBefore('/') }.distinct()
    return when {
        parsed.size == 1 -> parsed.single()
        primaries.size == 1 -> primaries.single() + "/*"
        else -> ANY_MIME_TYPE
    }
}

internal const val ANY_MIME_TYPE = "*/*"

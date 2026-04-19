package io.github.kdroidfilter.nucleus.clipboard

/**
 * Change notification emitted by [Clipboard.watch].
 *
 * Carries only metadata — no payload bytes are read from the system pasteboard,
 * which keeps the watcher outside the scope of macOS 15.4+ pasteboard-privacy
 * prompts. Call [Clipboard.readText] / [Clipboard.readImageBytes] / ... to fetch
 * the actual content on demand.
 */
data class ClipboardEvent(
    /** Formats currently advertised on the clipboard. */
    val formats: Set<ClipboardFormat>,
    /** Backend-provided monotonic counter. Useful to deduplicate self-writes. */
    val changeCount: Long,
)

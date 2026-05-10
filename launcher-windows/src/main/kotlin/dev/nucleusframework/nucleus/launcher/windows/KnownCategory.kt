package dev.nucleusframework.nucleus.launcher.windows

/**
 * Built-in jump list categories managed by Windows.
 *
 * These categories are populated automatically by the shell based on
 * file usage tracking (SHAddToRecentDocs).
 *
 * @property value The native `KNOWNDESTCATEGORY` constant.
 */
enum class KnownCategory(
    val value: Int,
) {
    /** Frequently used destinations. */
    FREQUENT(1),

    /** Recently used destinations. */
    RECENT(2),
}

package dev.nucleusframework.launcher.windows

/**
 * Built-in jump list categories managed by Windows.
 *
 * These categories are populated automatically by the shell based on
 * file usage tracking (SHAddToRecentDocs).
 *
 * @property value The native `KNOWNDESTCATEGORY` constant.
 */
public enum class KnownCategory(
    public val value: Int,
) {
    /** Frequently used destinations. */
    FREQUENT(1),

    /** Recently used destinations. */
    RECENT(2),
}

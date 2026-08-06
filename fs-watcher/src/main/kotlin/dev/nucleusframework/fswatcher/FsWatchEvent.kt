package dev.nucleusframework.fswatcher

import java.nio.file.Path

public data class FsWatchSource(
    val root: Path,
    val recursive: Boolean,
    val name: String? = null,
)

public sealed interface FsWatchEvent {
    public val source: FsWatchSource?
    public val needsRescan: Boolean

    public data class Created(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    public data class Modified(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    public data class Removed(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    public data class Moved(
        val from: Path,
        val to: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    public data class Overflow(
        override val source: FsWatchSource? = null,
        override val needsRescan: Boolean = true,
    ) : FsWatchEvent

    public data class Other(
        val paths: List<Path> = emptyList(),
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent
}

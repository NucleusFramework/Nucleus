package dev.nucleusframework.updater.internal.delta

/** Where the bytes of a range in the new artifact come from. */
internal enum class OperationKind {
    /** Read from the artifact already on disk; [Operation.start] is an offset in the *old* file. */
    COPY,

    /** Fetch with a ranged request; [Operation.start] is an offset in the *new* file. */
    DOWNLOAD,
}

/** A contiguous range of the new artifact, `[start, end)`, and how to obtain it. */
internal data class Operation(
    val kind: OperationKind,
    val start: Long,
    val end: Long,
) {
    val length: Long get() = end - start
}

/**
 * Turns a pair of block maps into the list of copy/download operations that assembles the new
 * artifact — a direct port of electron-builder's `downloadPlanBuilder`, so a plan computed here is
 * the same plan `electron-updater` would compute from the same two block maps.
 *
 * Blocks of the new artifact whose digest also appears in the old one are copied from disk;
 * everything else is downloaded. Adjacent operations of the same kind are merged so that a run of
 * changed blocks becomes a single ranged request instead of one request per block. [coalesce]
 * goes further than electron-updater and also bridges small unchanged gaps between downloads.
 */
internal object DeltaPlan {
    fun compute(
        old: BlockMap,
        new: BlockMap,
    ): List<Operation> {
        // electron-builder only ever describes a single file per block map.
        val newEntry = new.files.first()
        val index = OldBlockIndex(counterpartOf(newEntry, old, new))
        val operations = ArrayList<Operation>()
        var newOffset = newEntry.offset

        for (i in newEntry.checksums.indices) {
            val size = newEntry.sizes[i]
            val oldOffset = index.offsetOf(newEntry.checksums[i], size)
            if (oldOffset == null) {
                addOrExtend(operations, OperationKind.DOWNLOAD, newOffset, size)
            } else {
                addOrExtend(operations, OperationKind.COPY, oldOffset, size)
            }
            newOffset += size
        }

        if (operations.isEmpty()) throw DeltaUnavailableException("Block map describes an empty artifact")
        return operations
    }

    /** The entry of the old block map describing the same file as [newEntry]. */
    private fun counterpartOf(
        newEntry: BlockMapFile,
        old: BlockMap,
        new: BlockMap,
    ): BlockMapFile {
        if (old.version != new.version) {
            throw DeltaUnavailableException("Block map versions differ (${old.version} vs ${new.version})")
        }
        return old.files.firstOrNull { it.name == newEntry.name }
            ?: throw DeltaUnavailableException("Old block map has no entry named '${newEntry.name}'")
    }

    /** Total number of bytes the plan will fetch over the network. */
    fun downloadSize(operations: List<Operation>): Long =
        operations.filter { it.kind == OperationKind.DOWNLOAD }.sumOf { it.length }

    /** Largest run of copied bytes [coalesce] replaces with downloaded bytes. */
    const val MAX_COALESCE_GAP: Long = 256L * 1024

    /**
     * Merges two downloads separated only by copies totalling at most [maxGap] bytes into one
     * download, fetching the gap from the server instead of copying it from disk. Every ranged
     * request costs a round trip, which outweighs transferring a small gap, so a plan with many
     * scattered changes needs far fewer requests for a little extra volume.
     *
     * The merge is exact: download offsets index the new artifact, so the copies between two
     * downloads fill `[first.end, next.start)` of it, and fetching that range yields the same bytes.
     * A gap whose copies do not add up to that span is left alone.
     */
    fun coalesce(
        operations: List<Operation>,
        maxGap: Long = MAX_COALESCE_GAP,
    ): List<Operation> {
        val merged = ArrayList<Operation>(operations.size)
        var index = 0
        while (index < operations.size) {
            val operation = operations[index]
            val last = merged.lastOrNull()
            if (operation.kind == OperationKind.COPY && last?.kind == OperationKind.DOWNLOAD) {
                var next = index
                var gap = 0L
                while (next < operations.size && operations[next].kind == OperationKind.COPY) {
                    gap += operations[next].length
                    next++
                }
                val following = operations.getOrNull(next)
                if (following?.kind == OperationKind.DOWNLOAD && gap <= maxGap && following.start == last.end + gap) {
                    merged[merged.lastIndex] = last.copy(end = following.end)
                    index = next + 1
                    continue
                }
            }
            merged += operation
            index++
        }
        return merged
    }

    private fun addOrExtend(
        operations: MutableList<Operation>,
        kind: OperationKind,
        start: Long,
        size: Long,
    ) {
        val last = operations.lastOrNull()
        if (last != null && last.kind == kind && last.end == start) {
            operations[operations.lastIndex] = last.copy(end = last.end + size)
        } else {
            operations += Operation(kind, start, start + size)
        }
    }

    /**
     * Digest to offset lookup over the old artifact's blocks. The first occurrence of a digest
     * wins — later duplicates are simply not reused, which costs a little download volume but
     * never produces a wrong result.
     */
    private class OldBlockIndex(
        entry: BlockMapFile,
    ) {
        private val offsets = HashMap<String, Long>(entry.checksums.size)
        private val sizes = HashMap<String, Long>(entry.checksums.size)

        init {
            var offset = entry.offset
            for (i in entry.checksums.indices) {
                val checksum = entry.checksums[i]
                if (!sizes.containsKey(checksum)) {
                    offsets[checksum] = offset
                    sizes[checksum] = entry.sizes[i]
                }
                offset += entry.sizes[i]
            }
        }

        /**
         * Offset of the block with this [checksum], or `null` when it is absent — or present with a
         * different [size], which means the two block maps disagree and the block must be refetched.
         */
        fun offsetOf(
            checksum: String,
            size: Long,
        ): Long? = offsets[checksum]?.takeIf { sizes[checksum] == size }
    }
}

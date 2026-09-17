package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.internal.delta.BlockMap
import dev.nucleusframework.updater.internal.delta.BlockMapCodec
import dev.nucleusframework.updater.internal.delta.BlockMapFile
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaUnavailableException
import dev.nucleusframework.updater.internal.delta.OperationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaPlanTest {
    @Test
    fun `real block maps reuse every block the insertion did not touch`() {
        val plan = planFromFixtures()

        assertEquals(
            "only the block absorbing the insertion must be downloaded",
            DeltaFixtures.EXPECTED_DELTA_BYTES,
            DeltaPlan.downloadSize(plan),
        )
        assertEquals(
            "the plan must cover the whole new artifact",
            DeltaFixtures.V2_SIZE.toLong(),
            plan.sumOf { it.length },
        )
        assertEquals(
            "one contiguous run of unchanged blocks on each side of the edit",
            listOf(OperationKind.COPY, OperationKind.DOWNLOAD, OperationKind.COPY),
            plan.map { it.kind },
        )
    }

    @Test
    fun `bytes copied from the old artifact are read from their old offsets`() {
        val plan = planFromFixtures()
        val old = DeltaFixtures.v1()
        val new = DeltaFixtures.v2()

        // Replaying the plan against the fixtures must reproduce the new artifact byte for byte;
        // this is what the downloader does, minus the network.
        val assembled =
            plan.fold(ByteArray(0)) { acc, operation ->
                val slice =
                    when (operation.kind) {
                        OperationKind.COPY -> old.copyOfRange(operation.start.toInt(), operation.end.toInt())
                        OperationKind.DOWNLOAD -> new.copyOfRange(operation.start.toInt(), operation.end.toInt())
                    }
                acc + slice
            }
        assertTrue("replaying the plan must reproduce the new artifact", new.contentEquals(assembled))
    }

    @Test
    fun `an unchanged artifact needs no download at all`() {
        val map = BlockMapCodec.parseGzip(DeltaFixtures.blockMapGzip("v2"))

        val plan = DeltaPlan.compute(map, map)

        assertEquals(listOf(OperationKind.COPY), plan.map { it.kind })
        assertEquals(0L, DeltaPlan.downloadSize(plan))
    }

    @Test
    fun `adjacent blocks of the same kind are merged into one operation`() {
        val old = blockMap(listOf("a" to 10L, "b" to 20L, "c" to 30L))
        val new = blockMap(listOf("a" to 10L, "b" to 20L, "x" to 5L, "y" to 5L))

        val plan = DeltaPlan.compute(old, new)

        assertEquals(2, plan.size)
        assertEquals(OperationKind.COPY, plan[0].kind)
        assertEquals(0L, plan[0].start)
        assertEquals(30L, plan[0].end)
        assertEquals(OperationKind.DOWNLOAD, plan[1].kind)
        assertEquals(30L, plan[1].start)
        assertEquals(40L, plan[1].end)
    }

    @Test
    fun `a reordered block is copied from where it now lives in the old artifact`() {
        val old = blockMap(listOf("a" to 10L, "b" to 20L))
        val new = blockMap(listOf("b" to 20L, "a" to 10L))

        val plan = DeltaPlan.compute(old, new)

        assertEquals(0L, DeltaPlan.downloadSize(plan))
        assertEquals(listOf(10L to 30L, 0L to 10L), plan.map { it.start to it.end })
    }

    @Test
    fun `a block whose digest matches but whose size differs is refetched`() {
        val old = blockMap(listOf("a" to 10L))
        val new = blockMap(listOf("a" to 12L))

        val plan = DeltaPlan.compute(old, new)

        assertEquals(listOf(OperationKind.DOWNLOAD), plan.map { it.kind })
        assertEquals(12L, DeltaPlan.downloadSize(plan))
    }

    @Test
    fun `a duplicated block in the old artifact is indexed by its first occurrence`() {
        val old = blockMap(listOf("a" to 10L, "a" to 10L))
        val new = blockMap(listOf("a" to 10L))

        val plan = DeltaPlan.compute(old, new)

        assertEquals(0L, plan.single().start)
    }

    @Test
    fun `mismatched block map versions abort the delta`() {
        val old = blockMap(listOf("a" to 10L)).copy(version = "1")
        val new = blockMap(listOf("a" to 10L))

        assertThrows(DeltaUnavailableException::class.java) { DeltaPlan.compute(old, new) }
    }

    @Test
    fun `an old block map describing a different file aborts the delta`() {
        val old = BlockMap("2", listOf(BlockMapFile("other", 0, listOf("a"), listOf(10L))))
        val new = blockMap(listOf("a" to 10L))

        assertThrows(DeltaUnavailableException::class.java) { DeltaPlan.compute(old, new) }
    }

    private fun planFromFixtures() =
        DeltaPlan.compute(
            BlockMapCodec.parseGzip(DeltaFixtures.blockMapGzip("v1")),
            BlockMapCodec.parseGzip(DeltaFixtures.blockMapGzip("v2")),
        )

    private fun blockMap(blocks: List<Pair<String, Long>>) =
        BlockMap("2", listOf(BlockMapFile("file", 0, blocks.map { it.first }, blocks.map { it.second })))
}

package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.internal.delta.BlockMapCodec
import dev.nucleusframework.updater.internal.delta.DeltaUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reads block maps a real electron-builder produced, in both shapes it emits. */
class BlockMapCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `fixtures still match the committed electron-builder block maps`() {
        DeltaFixtures.verify()
    }

    @Test
    fun `a standalone gzipped block map describes the whole artifact`() {
        val map = BlockMapCodec.parseGzip(DeltaFixtures.blockMapGzip("v1"))

        assertEquals("2", map.version)
        assertEquals(1, map.files.size)
        val entry = map.files.single()
        assertEquals("file", entry.name)
        assertEquals(0L, entry.offset)
        assertEquals(DeltaFixtures.BLOCK_COUNT, entry.checksums.size)
        assertEquals(DeltaFixtures.BLOCK_COUNT, entry.sizes.size)
        assertEquals(
            "the blocks must cover the artifact exactly",
            DeltaFixtures.V1_SIZE.toLong(),
            entry.sizes.sum(),
        )
        assertTrue("blocks are content-defined, so sizes must vary", entry.sizes.distinct().size > 1)
    }

    @Test
    fun `a block map embedded in the artifact tail is read back`() {
        val artifact = tmp.newFile("app.AppImage")
        artifact.writeBytes(DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v1(), "v1"))

        val embedded = BlockMapCodec.readEmbedded(artifact)

        assertEquals(
            BlockMapCodec.parseGzip(DeltaFixtures.blockMapGzip("v1")),
            embedded,
        )
        assertEquals(
            "the embedded map describes the artifact without its own trailer",
            DeltaFixtures.V1_SIZE.toLong(),
            embedded.files
                .single()
                .sizes
                .sum(),
        )
    }

    @Test
    fun `a truncated artifact is rejected instead of read as a block map`() {
        val artifact = tmp.newFile("truncated.AppImage")
        artifact.writeBytes(ByteArray(2))

        assertThrows(DeltaUnavailableException::class.java) { BlockMapCodec.readEmbedded(artifact) }
    }

    @Test
    fun `an implausible trailer length is rejected`() {
        val artifact = tmp.newFile("bogus.AppImage")
        // A length header claiming far more bytes than the file holds.
        artifact.writeBytes(ByteArray(1024) + byteArrayOf(0x7F, -1, -1, -1))

        assertThrows(DeltaUnavailableException::class.java) { BlockMapCodec.readEmbedded(artifact) }
    }

    @Test
    fun `garbage in place of a block map is rejected`() {
        assertThrows(DeltaUnavailableException::class.java) {
            BlockMapCodec.parseGzip("not a block map".toByteArray())
        }
    }
}

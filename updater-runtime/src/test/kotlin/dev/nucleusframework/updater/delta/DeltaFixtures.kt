package dev.nucleusframework.updater.delta

import org.junit.Assert.assertEquals
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream

/**
 * Two artifact versions and **the block maps a real `electron-builder` produced for them**, so the
 * delta tests run against genuine metadata — real Rabin block boundaries, real block digests, real
 * gzip framing — instead of metadata this codebase invented.
 *
 * The artifact bytes are regenerated here rather than committed: `java.util.Random` is specified to
 * be a fixed linear congruential generator, so a seed always yields the same bytes on every JVM.
 * [verify] pins the sizes and SHA-512 that the committed block maps were generated from, so any
 * drift in that regeneration fails loudly instead of silently invalidating the fixtures.
 *
 * v2 is v1 with 9000 bytes inserted at offset 200000, which shifts every following byte. That is the
 * case fixed-size blocking cannot handle and content-defined blocking can: electron-builder's own
 * block maps show 26 of the 27 blocks surviving the shift, leaving [EXPECTED_DELTA_BYTES] to fetch.
 *
 * Regenerate with:
 * ```
 * app-builder blockmap --input v1.bin --output v1.blockmap
 * app-builder blockmap --input v2.bin --output v2.blockmap
 * ```
 */
internal object DeltaFixtures {
    const val V1_SIZE = 524288
    const val V2_SIZE = 533288
    const val V1_SHA512 = "oDnRasbUz/qFQZRs3QDa29QbyLJiyHHVw/rtZrk3b8AIcE81mssdsyAAkhQhCwmXAbkI1Ai7718z1lipjStyxg=="
    const val V2_SHA512 = "pmgP+enVVyoYZLPsJrtZeVRedpKbaoFoAr9J3sJD3yZbseey57ikkXptEVJ5/UdixBnmJEl6XqpzyTrfgrRCIQ=="

    /** Blocks in each map, and how many of v2's are also in v1. */
    const val BLOCK_COUNT = 27
    const val REUSED_BLOCK_COUNT = 26

    /** Bytes of v2 that are not already in v1 — 5.7% of the artifact. */
    const val EXPECTED_DELTA_BYTES = 30283L

    private const val INSERT_OFFSET = 200000
    private const val INSERT_SIZE = 9000

    fun v1(): ByteArray = random(seed = 42L, size = V1_SIZE)

    fun v2(): ByteArray {
        val v1 = v1()
        val out = ByteArrayOutputStream(V2_SIZE)
        out.write(v1, 0, INSERT_OFFSET)
        out.write(random(seed = 7L, size = INSERT_SIZE))
        out.write(v1, INSERT_OFFSET, v1.size - INSERT_OFFSET)
        return out.toByteArray()
    }

    /** The gzipped block map `electron-builder` emitted for the given version. */
    fun blockMapGzip(version: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/delta/$version.blockmap")) {
            "missing block map fixture for $version"
        }.use { it.readBytes() }

    /** The block map JSON, as `electron-builder` embeds it in an AppImage: raw deflate. */
    fun blockMapDeflateRaw(version: String): ByteArray {
        val json = GZIPInputStream(blockMapGzip(version).inputStream()).use { it.readBytes() }
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(json) }
        return out.toByteArray()
    }

    /**
     * The artifact with its own block map appended, followed by the payload length as a big-endian
     * 32-bit integer — the layout electron-builder gives AppImages and nsis-web packages.
     */
    fun withEmbeddedBlockMap(
        artifact: ByteArray,
        version: String,
    ): ByteArray {
        val payload = blockMapDeflateRaw(version)
        val out = ByteArrayOutputStream(artifact.size + payload.size + Int.SIZE_BYTES)
        out.write(artifact)
        out.write(payload)
        out.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ),
        )
        return out.toByteArray()
    }

    fun embeddedBlockMapSize(version: String): Long = blockMapDeflateRaw(version).size.toLong()

    fun sha512Base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))

    /** Fails the calling test if the regenerated artifacts no longer match the committed block maps. */
    fun verify() {
        assertEquals("v1 fixture size drifted", V1_SIZE, v1().size)
        assertEquals("v2 fixture size drifted", V2_SIZE, v2().size)
        assertEquals("v1 fixture content drifted", V1_SHA512, sha512Base64(v1()))
        assertEquals("v2 fixture content drifted", V2_SHA512, sha512Base64(v2()))
    }

    private fun random(
        seed: Long,
        size: Int,
    ): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }
}

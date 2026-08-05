package dev.nucleusframework.updater.internal.delta

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A content-defined block map of a single packaged artifact, as produced by electron-builder.
 *
 * The artifact is split into variable-length blocks whose boundaries are chosen by a rolling
 * (Rabin) fingerprint of the content rather than by fixed offsets, so inserting or removing bytes
 * only invalidates the blocks around the edit instead of shifting — and thus invalidating — every
 * block after it. Each block is identified by a short digest, which is all the updater needs: it
 * never recomputes block digests, it only matches the digests of two block maps against each other
 * and validates the assembled file against the manifest SHA-512.
 *
 * Only version `2` block maps exist in the wild; the version is compared between the two maps
 * rather than pinned, mirroring electron-builder.
 */
@Serializable
internal data class BlockMap(
    val version: String,
    val files: List<BlockMapFile>,
)

/**
 * Blocks of one file inside a [BlockMap]. electron-builder always emits exactly one entry, named
 * `file`, covering the whole artifact — [offset] is where its first block starts.
 */
@Serializable
internal data class BlockMapFile(
    val name: String,
    val offset: Long = 0,
    val checksums: List<String> = emptyList(),
    val sizes: List<Long> = emptyList(),
)

/**
 * Signals that a differential download is not possible or no longer trustworthy, and that the
 * caller must fall back to downloading the whole artifact. Never surfaced to the application:
 * every delta failure degrades to a full download, which is always correct.
 */
internal class DeltaUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads the two shapes electron-builder emits: a standalone gzipped `<artifact>.blockmap` next to
 * the artifact (NSIS installers, macOS ZIPs, DMGs) and a raw-deflate payload appended to the
 * artifact itself, followed by its own big-endian length (AppImages, nsis-web packages).
 */
internal object BlockMapCodec {
    /** Size of the big-endian length header that terminates an embedded block map. */
    const val EMBEDDED_HEADER_SIZE = 4

    /** Sanity bound on a block map payload, so a bogus length header cannot trigger a huge read. */
    private const val MAX_PAYLOAD_SIZE = 64 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun parseGzip(bytes: ByteArray): BlockMap =
        decode(
            runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }
                .getOrElse { throw DeltaUnavailableException("Block map is not valid gzip", it) },
        )

    fun parseDeflateRaw(bytes: ByteArray): BlockMap {
        val inflater = Inflater(true)
        val inflated =
            try {
                runCatching { InflaterInputStream(bytes.inputStream(), inflater).use { it.readBytes() } }
                    .getOrElse { throw DeltaUnavailableException("Block map is not valid raw deflate", it) }
            } finally {
                inflater.end()
            }
        return decode(inflated)
    }

    /**
     * Reads the block map appended to the end of [file], as electron-builder does for AppImages.
     * The trailing [EMBEDDED_HEADER_SIZE] bytes hold the payload length; the payload precedes them.
     */
    fun readEmbedded(file: File): BlockMap {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val payloadSize = readEmbeddedPayloadSize(raf)
                raf.seek(raf.length() - EMBEDDED_HEADER_SIZE - payloadSize)
                val payload = ByteArray(payloadSize)
                raf.readFully(payload)
                return parseDeflateRaw(payload)
            }
        } catch (e: IOException) {
            throw DeltaUnavailableException("Cannot read the block map embedded in ${file.name}", e)
        }
    }

    /** Length of the embedded payload, excluding the length header itself. */
    fun readEmbeddedPayloadSize(raf: RandomAccessFile): Int {
        val fileSize = raf.length()
        if (fileSize <= EMBEDDED_HEADER_SIZE) {
            throw DeltaUnavailableException("File is too small to carry an embedded block map")
        }
        raf.seek(fileSize - EMBEDDED_HEADER_SIZE)
        val payloadSize = raf.readInt()
        if (payloadSize <= 0 ||
            payloadSize > MAX_PAYLOAD_SIZE ||
            payloadSize + EMBEDDED_HEADER_SIZE > fileSize
        ) {
            throw DeltaUnavailableException("Implausible embedded block map length: $payloadSize")
        }
        return payloadSize
    }

    private fun decode(jsonBytes: ByteArray): BlockMap =
        try {
            json.decodeFromString<BlockMap>(jsonBytes.decodeToString())
        } catch (e: SerializationException) {
            throw DeltaUnavailableException("Block map is not valid JSON", e)
        }.also(::validate)

    private fun validate(map: BlockMap) {
        if (map.files.isEmpty()) throw DeltaUnavailableException("Block map declares no files")
        map.files.forEach { entry ->
            if (entry.checksums.size != entry.sizes.size) {
                throw DeltaUnavailableException(
                    "Block map entry '${entry.name}' has ${entry.checksums.size} checksums " +
                        "but ${entry.sizes.size} sizes",
                )
            }
        }
    }
}

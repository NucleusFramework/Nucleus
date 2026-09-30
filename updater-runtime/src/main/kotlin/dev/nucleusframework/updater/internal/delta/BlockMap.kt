package dev.nucleusframework.updater.internal.delta

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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

    /**
     * Sanity bound on a compressed block map, whether fetched, read from an artifact's tail or
     * declared as `blockMapSize` in the manifest. Real ones are a few MB at most (about 35 bytes of
     * JSON per ~16 KiB block, before compression).
     */
    const val MAX_PAYLOAD_SIZE = 64 * 1024 * 1024

    /**
     * Sanity bound on a block map once inflated, so a hostile or corrupt payload cannot expand into
     * an [OutOfMemoryError] — an [Error], which would escape the fall back to a full download.
     */
    private const val MAX_DECOMPRESSED_SIZE = 128L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun parseGzip(bytes: ByteArray): BlockMap =
        decode(
            runCatching {
                GZIPInputStream(bytes.inputStream()).use { readAtMost(it, MAX_DECOMPRESSED_SIZE) }
            }.getOrElse {
                throw it as? DeltaUnavailableException ?: DeltaUnavailableException("Block map is not valid gzip", it)
            },
        )

    fun parseDeflateRaw(bytes: ByteArray): BlockMap {
        val inflater = Inflater(true)
        val inflated =
            try {
                runCatching {
                    InflaterInputStream(bytes.inputStream(), inflater).use { readAtMost(it, MAX_DECOMPRESSED_SIZE) }
                }.getOrElse {
                    throw it as? DeltaUnavailableException
                        ?: DeltaUnavailableException("Block map is not valid raw deflate", it)
                }
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
        val problem =
            if (map.files.isEmpty()) {
                "Block map declares no files"
            } else {
                map.files.firstNotNullOfOrNull(::problemWith)
            }
        if (problem != null) throw DeltaUnavailableException(problem)
    }

    private fun problemWith(entry: BlockMapFile): String? =
        when {
            entry.checksums.size != entry.sizes.size ->
                "Block map entry '${entry.name}' has ${entry.checksums.size} checksums but ${entry.sizes.size} sizes"
            entry.offset < 0 || entry.sizes.any { it <= 0 } ->
                "Block map entry '${entry.name}' declares a negative offset or a non-positive block size"
            else -> null
        }
}

/**
 * Reads [input] to the end, failing with [DeltaUnavailableException] instead of buffering more than
 * [maxBytes]: the length of a response or an inflated stream is decided by the server, not by us.
 */
internal fun readAtMost(
    input: InputStream,
    maxBytes: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) throw DeltaUnavailableException("Block map data exceeds $maxBytes bytes")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

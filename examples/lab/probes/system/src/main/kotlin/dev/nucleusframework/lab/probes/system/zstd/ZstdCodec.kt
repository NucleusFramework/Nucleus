package dev.nucleusframework.lab.probes.system.zstd

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * In-memory zstd round trip over the zstd-kmp streaming API. Creating the first
 * compressor is what loads the native library (`JniZstd`'s static initialiser).
 */
object ZstdCodec {
    private const val CHUNK = 1 shl 16

    fun compress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        zstdCompressor().use { compressor ->
            var consumed = 0
            do {
                // ZSTD_e_end: keep calling until the frame is flushed (0 bytes left to write).
                val remaining =
                    compressor.compressStream2(
                        buffer,
                        buffer.size,
                        0,
                        input,
                        input.size,
                        consumed,
                        ZSTD_e_end,
                    )
                getErrorName(remaining)?.let { error("compressStream2: $it") }
                out.write(buffer, 0, compressor.outputBytesProcessed)
                consumed += compressor.inputBytesProcessed
            } while (remaining != 0L)
        }
        return out.toByteArray()
    }

    fun decompress(
        input: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        val output = ByteArray(expectedSize)
        var written = 0
        zstdDecompressor().use { decompressor ->
            var consumed = 0
            while (consumed < input.size || written < expectedSize) {
                val result = decompressor.decompressStream(output, output.size, written, input, input.size, consumed)
                getErrorName(result)?.let { error("decompressStream: $it") }
                if (decompressor.inputBytesProcessed == 0 && decompressor.outputBytesProcessed == 0) break
                consumed += decompressor.inputBytesProcessed
                written += decompressor.outputBytesProcessed
            }
        }
        check(written == expectedSize) { "decompressed $written bytes, expected $expectedSize" }
        return output
    }

    /** Text-like, partly repetitive data: compresses well but not trivially. */
    fun sample(size: Int): ByteArray {
        val random = Random(size)
        val words = listOf("nucleus", "lab", "zstd", "sandbox", "marker", "manifest", "jni", "extract", "load")
        val builder = StringBuilder(size + 16)
        while (builder.length < size) {
            builder.append(words[random.nextInt(words.size)]).append(' ')
            if (random.nextInt(8) == 0) builder.append(random.nextLong()).append('\n')
        }
        return builder.substring(0, size).toByteArray()
    }
}

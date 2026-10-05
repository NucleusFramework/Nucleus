package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads whether a Windows PE image (`.exe` / `.dll`) carries an embedded Authenticode signature,
 * i.e. whether its security data directory is non-empty — the same check `signtool verify` starts
 * from, without needing a Windows SDK. A catalog-signed file reads as unsigned, which is what a
 * repackaged app wants: the catalog does not travel with the file.
 */
internal object PeSignature {
    private const val E_LFANEW_OFFSET = 0x3C
    private const val PE_SIGNATURE = 0x00004550 // "PE\0\0"
    private const val COFF_HEADER_SIZE = 20
    private const val PE32_MAGIC = 0x10B
    private const val PE32_PLUS_MAGIC = 0x20B
    private const val PE32_DATA_DIRECTORIES_OFFSET = 96
    private const val PE32_PLUS_DATA_DIRECTORIES_OFFSET = 112
    private const val NUMBER_OF_RVA_AND_SIZES_FROM_DIRECTORIES = -4
    private const val SECURITY_DIRECTORY_INDEX = 4
    private const val DATA_DIRECTORY_SIZE = 8

    /** Whether [file] is a PE image without an embedded signature. Non-PE files return `false`. */
    fun isUnsignedPe(file: File): Boolean =
        RandomAccessFile(file, "r").use { raf ->
            securityDirectorySize { offset, length ->
                if (offset < 0 || offset + length > raf.length()) return@securityDirectorySize null
                ByteArray(length).also {
                    raf.seek(offset)
                    raf.readFully(it)
                }
            } == 0L
        }

    /** Whether [bytes] hold a PE image without an embedded signature. Non-PE data returns `false`. */
    fun isUnsignedPe(bytes: ByteArray): Boolean =
        securityDirectorySize { offset, length ->
            if (offset < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        } == 0L

    /** Size of the security data directory, or `null` when the data is not a PE image. */
    @Suppress("ReturnCount")
    private fun securityDirectorySize(read: (offset: Long, length: Int) -> ByteArray?): Long? {
        val dos = read(0, E_LFANEW_OFFSET + 4)?.le() ?: return null
        if (dos.getShort(0).toInt() != 0x5A4D) return null // "MZ"
        val peOffset = dos.getInt(E_LFANEW_OFFSET).toLong() and 0xFFFFFFFFL
        val headers = read(peOffset, 4 + COFF_HEADER_SIZE + 2)?.le() ?: return null
        if (headers.getInt(0) != PE_SIGNATURE) return null
        val optionalHeader = peOffset + 4 + COFF_HEADER_SIZE
        val directories =
            when (headers.getShort(4 + COFF_HEADER_SIZE).toInt() and 0xFFFF) {
                PE32_MAGIC -> optionalHeader + PE32_DATA_DIRECTORIES_OFFSET
                PE32_PLUS_MAGIC -> optionalHeader + PE32_PLUS_DATA_DIRECTORIES_OFFSET
                else -> return null
            }
        val count = read(directories + NUMBER_OF_RVA_AND_SIZES_FROM_DIRECTORIES, 4)?.le()?.getInt(0) ?: return null
        if (count <= SECURITY_DIRECTORY_INDEX) return 0
        val entry = read(directories + SECURITY_DIRECTORY_INDEX * DATA_DIRECTORY_SIZE, DATA_DIRECTORY_SIZE)?.le() ?: return null
        return entry.getInt(4).toLong() and 0xFFFFFFFFL
    }

    private fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
}

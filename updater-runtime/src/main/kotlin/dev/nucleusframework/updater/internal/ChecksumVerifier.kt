package dev.nucleusframework.updater.internal

import java.io.File
import java.security.MessageDigest
import java.util.Base64

internal object ChecksumVerifier {
    fun verify(
        file: File,
        expectedSha512Base64: String,
    ): Boolean {
        val actual = computeSha512Base64(file)
        return actual == expectedSha512Base64
    }

    private const val BUFFER_SIZE = 8192

    fun computeSha512Base64(file: File): String {
        val digest = newSha512Digest()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return encode(digest)
    }

    /** A fresh SHA-512 digest, to hash a download while it is written instead of reading it back. */
    fun newSha512Digest(): MessageDigest = MessageDigest.getInstance("SHA-512")

    /** Completes [digest] in the Base64 form the update manifest uses. */
    fun encode(digest: MessageDigest): String = Base64.getEncoder().encodeToString(digest.digest())
}

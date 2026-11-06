package dev.nucleusframework.updater.internal

import java.security.MessageDigest
import java.util.Base64

internal object ChecksumVerifier {
    /** A fresh SHA-512 digest, to hash a download while it is written instead of reading it back. */
    fun newSha512Digest(): MessageDigest = MessageDigest.getInstance("SHA-512")

    /** Completes [digest] in the Base64 form the update manifest uses. */
    fun encode(digest: MessageDigest): String = Base64.getEncoder().encodeToString(digest.digest())
}

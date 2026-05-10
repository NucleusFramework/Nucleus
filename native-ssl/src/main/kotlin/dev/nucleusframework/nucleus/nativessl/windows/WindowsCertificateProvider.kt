package dev.nucleusframework.nucleus.nativessl.windows

import dev.nucleusframework.nucleus.nativessl.debugln
import java.security.KeyStore

private const val TAG = "WindowsCertificateProvider"

private val WINDOWS_STORES =
    listOf(
        "Windows-ROOT", // Trusted Root Certification Authorities
        "Windows-CA", // Intermediate Certification Authorities
        "Windows-MY", // Personal certificates (user-installed)
    )

internal object WindowsCertificateProvider {
    fun getSystemCertificates(): List<ByteArray> {
        // Prefer native bridge (Crypt32) – covers Group Policy, AD, Enterprise stores
        if (WindowsSslBridge.isLoaded) {
            val nativeCerts = WindowsSslBridge.getSystemCertificates()
            if (nativeCerts.isNotEmpty()) {
                debugln(TAG) { "Using native bridge: ${nativeCerts.size} certificates" }
                return nativeCerts
            }
            debugln(TAG) { "Native bridge returned no certificates, falling back to SunMSCAPI" }
        } else {
            debugln(TAG) { "Native bridge not loaded, falling back to SunMSCAPI" }
        }

        // Fallback: SunMSCAPI KeyStore (does not cover Group Policy / Enterprise stores)
        return getSystemCertificatesFallback()
    }

    private fun getSystemCertificatesFallback(): List<ByteArray> {
        val seen = mutableSetOf<String>()
        val allCerts = mutableListOf<ByteArray>()

        for (storeName in WINDOWS_STORES) {
            loadFromStore(storeName, seen, allCerts)
        }

        if (allCerts.isEmpty()) {
            debugln(TAG) { "Fallback: no system certificates found on Windows" }
        } else {
            debugln(TAG) { "Fallback total: ${allCerts.size} unique certificates" }
        }
        return allCerts
    }

    private fun loadFromStore(
        storeName: String,
        seen: MutableSet<String>,
        allCerts: MutableList<ByteArray>,
    ) {
        @Suppress("TooGenericExceptionCaught")
        try {
            val keyStore = KeyStore.getInstance(storeName)
            keyStore.load(null, null)
            val count = collectStoreCerts(keyStore, seen, allCerts)
            debugln(TAG) { "Fallback: loaded $count certificates from $storeName" }
        } catch (e: Exception) {
            debugln(TAG) { "Fallback: could not read store $storeName: ${e.message}" }
        }
    }

    private fun collectStoreCerts(
        keyStore: KeyStore,
        seen: MutableSet<String>,
        allCerts: MutableList<ByteArray>,
    ): Int {
        var count = 0
        for (alias in keyStore.aliases()) {
            val der = keyStore.getCertificate(alias)?.encoded ?: continue
            val fingerprint =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(der)
            if (seen.add(fingerprint)) {
                allCerts.add(der)
                count++
            }
        }
        return count
    }
}

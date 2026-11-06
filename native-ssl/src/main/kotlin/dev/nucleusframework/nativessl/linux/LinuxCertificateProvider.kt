package dev.nucleusframework.nativessl.linux

import dev.nucleusframework.nativessl.debugln
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "LinuxCertificateProvider"

// Same discovery logic as Go's crypto/x509:
// https://github.com/golang/go/blob/0668e3cb1a8407547f1b4e316748d3b898564f8e/src/crypto/x509/root_linux.go
private val BUNDLE_FILES =
    listOf(
        "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu/Gentoo etc.
        "/etc/pki/tls/certs/ca-bundle.crt", // Fedora/RHEL 6
        "/etc/ssl/ca-bundle.pem", // OpenSUSE
        "/etc/pki/tls/cacert.pem", // OpenELEC
        "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", // CentOS/RHEL 7
        "/etc/ssl/cert.pem", // Alpine Linux
    )

private val CERT_DIRS =
    listOf(
        "/etc/ssl/certs", // SLES10/SLES11, https://golang.org/issue/12139
        "/etc/pki/tls/certs", // Fedora/RHEL
        "/system/etc/security/cacerts", // Android
    )

// Inside a Flatpak, /etc/ssl is the runtime's static bundle: the host's own anchors (a company or
// filtering proxy's CA) only reach the sandbox through p11-kit, whose trust module Flatpak swaps for
// a client of the host's. `trust` ships in the Freedesktop and GNOME runtimes.
private const val FLATPAK_INFO = "/.flatpak-info"
private const val TRUST_TIMEOUT_SECONDS = 10L

private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val PEM_END = "-----END CERTIFICATE-----"

internal object LinuxCertificateProvider {
    fun getSystemCertificates(): List<ByteArray> {
        val seen = mutableSetOf<String>()
        val allCerts = mutableListOf<ByteArray>()

        // 1. Read all known bundle files (all distros, no early exit)
        for (path in BUNDLE_FILES) {
            val file = File(path)
            if (!file.isFile || !file.canRead()) continue
            debugln(TAG) { "Reading certificate bundle: $path" }
            val certs = tryReadPemFile(file, seen, allCerts)
            if (certs > 0) debugln(TAG) { "Loaded $certs certificates from $path" }
        }

        // 2. Scan individual-certificate directories (non-recursive, all regular files)
        for (dirPath in CERT_DIRS) {
            val dir = File(dirPath)
            if (!dir.isDirectory || !dir.canRead()) continue
            var count = 0
            for (file in (dir.listFiles() ?: continue)) {
                if (file.isFile && file.canRead()) {
                    count += tryReadPemFile(file, seen, allCerts)
                }
            }
            if (count > 0) debugln(TAG) { "Loaded $count certificates from $dirPath" }
        }

        // 3. Inside a Flatpak, the host's anchors through p11-kit
        if (File(FLATPAK_INFO).exists()) {
            val count = readHostAnchors(seen, allCerts)
            if (count > 0) debugln(TAG) { "Loaded $count host certificates through p11-kit" }
        }

        if (allCerts.isEmpty()) {
            debugln(TAG) { "No system certificates found on this Linux distribution" }
        } else {
            debugln(TAG) { "Total: ${allCerts.size} unique certificates" }
        }
        return allCerts
    }

    /** Extracts the host's server-auth anchors with `trust extract` and appends the new ones. */
    private fun readHostAnchors(
        seen: MutableSet<String>,
        out: MutableList<ByteArray>,
    ): Int {
        val bundle = File.createTempFile("nucleus-trust", ".pem")
        @Suppress("TooGenericExceptionCaught")
        try {
            val process =
                ProcessBuilder(
                    "trust",
                    "extract",
                    "--overwrite",
                    "--format=pem-bundle",
                    "--filter=ca-anchors",
                    "--purpose=server-auth",
                    bundle.path,
                ).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(TRUST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                debugln(TAG) { "trust extract timed out" }
                return 0
            }
            if (process.exitValue() != 0) {
                debugln(TAG) { "trust extract failed with exit code ${process.exitValue()}" }
                return 0
            }
            return tryReadPemFile(bundle, seen, out)
        } catch (e: Exception) {
            debugln(TAG) { "Failed to run trust extract: ${e.message}" }
            return 0
        } finally {
            bundle.delete()
        }
    }

    /** Parses a PEM file and appends new (unseen) DER-encoded certs to [out]. Returns the count added. */
    private fun tryReadPemFile(
        file: File,
        seen: MutableSet<String>,
        out: MutableList<ByteArray>,
    ): Int {
        @Suppress("TooGenericExceptionCaught")
        try {
            return file.inputStream().use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.US_ASCII))
                collectNewCerts(parsePemBundle(reader), seen, out)
            }
        } catch (e: Exception) {
            debugln(TAG) { "Failed to read ${file.path}: ${e.message}" }
        }
        return 0
    }

    private fun collectNewCerts(
        certs: List<ByteArray>,
        seen: MutableSet<String>,
        out: MutableList<ByteArray>,
    ): Int {
        var added = 0
        for (der in certs) {
            val key = Base64.getEncoder().encodeToString(der)
            if (seen.add(key)) {
                out.add(der)
                added++
            }
        }
        return added
    }

    private fun parsePemBundle(reader: BufferedReader): List<ByteArray> {
        val certs = mutableListOf<ByteArray>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.trim() == PEM_BEGIN) {
                parsePemBlock(reader)?.let { certs.add(it) }
            }
        }
        return certs
    }

    private fun parsePemBlock(reader: BufferedReader): ByteArray? {
        val base64 = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val inner = line!!.trim()
            when {
                inner.isEmpty() -> Unit
                inner == PEM_END -> return decodePemBase64(base64)
                else -> base64.append(inner)
            }
        }
        return null
    }

    private fun decodePemBase64(base64: StringBuilder): ByteArray? {
        if (base64.isEmpty()) return null
        @Suppress("TooGenericExceptionCaught")
        return try {
            Base64.getDecoder().decode(base64.toString())
        } catch (e: Exception) {
            debugln(TAG) { "Skipping malformed PEM block: ${e.message}" }
            null
        }
    }
}

package dev.nucleusframework.lab.probes.updater.network

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.hex
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.nativessl.NativeTrustManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Immutable
data class Anchor(
    val subject: String,
    val issuer: String,
    val notAfter: String,
    val fingerprint: String,
)

@Immutable
data class TrustStoreReport(
    val jdkCount: Int,
    val combinedCount: Int,
    /** Anchors the OS trusts and the JDK's cacerts does not: what native-ssl adds. */
    val osOnly: List<Anchor>,
    val loadMillis: Long,
)

/** Port over `native-ssl`: compares the JDK's default anchors with the merged OS + JDK set. */
interface TrustStoreGateway {
    fun read(): TrustStoreReport
}

@ContributesBinding(AppScope::class)
@Inject
class NativeTrustStoreGateway : TrustStoreGateway {
    override fun read(): TrustStoreReport {
        val jdk = jdkTrustManager().acceptedIssuers.toList()
        val (combined, loadMillis) = timedMillis { NativeTrustManager.trustManager.acceptedIssuers.toList() }
        val jdkKeys = jdk.map { it.encoded.contentHashCode() to it.serialNumber }.toSet()
        val osOnly =
            combined
                .filterNot { (it.encoded.contentHashCode() to it.serialNumber) in jdkKeys }
                .map(::anchor)
                .sortedBy { it.subject.lowercase() }
        return TrustStoreReport(jdk.size, combined.size, osOnly, loadMillis)
    }

    private fun jdkTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun anchor(certificate: X509Certificate): Anchor =
        Anchor(
            subject = certificate.subjectX500Principal.name.commonName(),
            issuer = certificate.issuerX500Principal.name.commonName(),
            notAfter =
                certificate.notAfter
                    .toInstant()
                    .toString()
                    .substringBefore('T'),
            fingerprint = sha256(certificate.encoded),
        )
}

/** `CN=…` when there is one, the full DN otherwise. */
internal fun String.commonName(): String =
    split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: this

/** The first [FINGERPRINT_BYTES] of the SHA-256: enough to tell anchors apart on screen. */
internal fun sha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .copyOf(FINGERPRINT_BYTES)
        .hex()

private const val FINGERPRINT_BYTES = 8

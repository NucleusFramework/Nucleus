package dev.nucleusframework.nucleus.nativessl

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "NativeTrustManager"

object NativeTrustManager {
    private val combined: X509TrustManager by lazy { buildCombinedTrustManager() }

    val trustManager: X509TrustManager get() = combined

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(combined), null)
        }
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        sslContext.socketFactory
    }

    private fun buildCombinedTrustManager(): X509TrustManager {
        val defaultTm = getDefaultTrustManager()

        val nativeCerts = NativeCertificateProvider.getSystemCertificates()
        if (nativeCerts.isEmpty()) {
            debugln(TAG) { "No native OS certificates found, using JVM defaults only" }
            return defaultTm
        }

        debugln(TAG) { "Merging ${nativeCerts.size} native OS certificates with JVM defaults" }

        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)

                // Add JVM default certs
                defaultTm.acceptedIssuers.forEachIndexed { i, cert ->
                    setCertificateEntry("jvm-$i", cert)
                }

                // Add native OS certs
                nativeCerts.forEachIndexed { i, cert ->
                    val alias = "native-$i"
                    if (!containsAlias(alias)) {
                        setCertificateEntry(alias, cert)
                    }
                }
            }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun getDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

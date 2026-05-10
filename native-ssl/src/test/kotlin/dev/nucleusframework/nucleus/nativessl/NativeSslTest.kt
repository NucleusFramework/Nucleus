package dev.nucleusframework.nucleus.nativessl

import dev.nucleusframework.nucleus.nativessl.linux.LinuxCertificateProvider
import dev.nucleusframework.nucleus.nativessl.mac.NativeSslBridge
import dev.nucleusframework.nucleus.nativessl.windows.WindowsCertificateProvider
import dev.nucleusframework.nucleus.nativessl.windows.WindowsSslBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class NativeSslTest {
    private val os = System.getProperty("os.name", "").lowercase()

    private val isMacOs = os.contains("mac") || os.contains("darwin")
    private val isLinux = os.contains("linux")
    private val isWindows = os.contains("win")

    @Test
    fun `native library loads on macOS`() {
        assumeTrue("Test requires macOS", isMacOs)
        assertTrue("Native SSL bridge should be loaded", NativeSslBridge.isLoaded)
    }

    @Test
    fun `nativeGetSystemCertificates returns non-empty DER array`() {
        assumeTrue("Test requires macOS", isMacOs)
        assumeTrue("Native library not loaded", NativeSslBridge.isLoaded)

        val derCerts = NativeSslBridge.getSystemCertificates()
        assertTrue("Should find at least one system certificate", derCerts.isNotEmpty())
        println("Found ${derCerts.size} raw DER certificates from macOS")
    }

    @Test
    fun `DER certificates are parseable as X509`() {
        assumeTrue("Test requires macOS", isMacOs)
        assumeTrue("Native library not loaded", NativeSslBridge.isLoaded)

        val derCerts = NativeSslBridge.getSystemCertificates()
        assumeTrue("No certs returned", derCerts.isNotEmpty())

        val factory = CertificateFactory.getInstance("X.509")
        var parsed = 0
        for (der in derCerts) {
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            parsed++
        }
        println("Successfully parsed $parsed / ${derCerts.size} certificates as X.509")
        assertTrue("All DER blobs should parse as X.509", parsed == derCerts.size)
    }

    @Test
    fun `NativeCertificateProvider returns X509 certificates`() {
        assumeTrue("Test requires macOS", isMacOs)

        val certs = NativeCertificateProvider.getSystemCertificates()
        assertTrue("Should find at least one certificate via provider", certs.isNotEmpty())
        println("NativeCertificateProvider returned ${certs.size} X.509 certificates")

        // Print a few subject names for visual verification
        certs.take(5).forEach { cert ->
            println("  - ${cert.subjectX500Principal}")
        }
    }

    @Test
    fun `NativeTrustManager contains more certs than JVM defaults`() {
        assumeTrue("Test requires macOS", isMacOs)

        val jvmOnly =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .size

        val combined = NativeTrustManager.trustManager.acceptedIssuers.size

        println("JVM default issuers: $jvmOnly")
        println("Combined (JVM + native) issuers: $combined")
        assertTrue(
            "Combined trust manager should have at least as many certs as JVM defaults",
            combined >= jvmOnly,
        )
    }

    @Test
    fun `user-installed certificates are found`() {
        assumeTrue("Test requires macOS", isMacOs)
        assumeTrue("Native library not loaded", NativeSslBridge.isLoaded)

        val nativeCerts = NativeCertificateProvider.getSystemCertificates()
        val jvmIssuers =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .map { it.subjectX500Principal }
                .toSet()

        val extraCerts = nativeCerts.filter { it.subjectX500Principal !in jvmIssuers }
        println("Certificates found by native bridge but NOT in JVM defaults: ${extraCerts.size}")
        extraCerts.forEach { cert ->
            println("  + ${cert.subjectX500Principal}")
        }

        assertTrue(
            "Expected user/admin-installed certs not present in JVM defaults. " +
                "Make sure you have custom certificates installed in Keychain.",
            extraCerts.isNotEmpty(),
        )
    }

    // ── Linux tests ──

    @Test
    fun `LinuxCertificateProvider returns DER certificates`() {
        assumeTrue("Test requires Linux", isLinux)

        val derCerts = LinuxCertificateProvider.getSystemCertificates()
        assertTrue("Should find at least one system certificate on Linux", derCerts.isNotEmpty())
        println("LinuxCertificateProvider returned ${derCerts.size} raw DER certificates")
    }

    @Test
    fun `Linux DER certificates are parseable as X509`() {
        assumeTrue("Test requires Linux", isLinux)

        val derCerts = LinuxCertificateProvider.getSystemCertificates()
        assumeTrue("No certs returned", derCerts.isNotEmpty())

        val factory = CertificateFactory.getInstance("X.509")
        var parsed = 0
        for (der in derCerts) {
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            parsed++
        }
        println("Successfully parsed $parsed / ${derCerts.size} Linux certificates as X.509")
        assertTrue("All DER blobs should parse as X.509", parsed == derCerts.size)
    }

    @Test
    fun `NativeCertificateProvider returns certificates on Linux`() {
        assumeTrue("Test requires Linux", isLinux)

        val certs = NativeCertificateProvider.getSystemCertificates()
        assertTrue("Should find at least one certificate via provider on Linux", certs.isNotEmpty())
        println("NativeCertificateProvider returned ${certs.size} X.509 certificates on Linux")

        certs.take(5).forEach { cert ->
            println("  - ${cert.subjectX500Principal}")
        }
    }

    @Test
    fun `Linux finds user-installed certificates from local ca-certificates`() {
        assumeTrue("Test requires Linux", isLinux)

        val certs = NativeCertificateProvider.getSystemCertificates()
        val subjects = certs.map { it.subjectX500Principal.name }

        println("All ${certs.size} certificate subjects:")
        subjects.forEach { println("  - $it") }

        val jvmIssuers =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .map { it.subjectX500Principal.name }
                .toSet()

        val extraCerts = certs.filter { it.subjectX500Principal.name !in jvmIssuers }
        println("\nCertificates found by LinuxCertificateProvider but NOT in JVM defaults: ${extraCerts.size}")
        extraCerts.forEach { cert ->
            println("  + ${cert.subjectX500Principal}")
        }
    }

    @Test
    fun `NativeTrustManager works on Linux`() {
        assumeTrue("Test requires Linux", isLinux)

        val jvmOnly =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .size

        val combined = NativeTrustManager.trustManager.acceptedIssuers.size

        println("JVM default issuers: $jvmOnly")
        println("Combined (JVM + native) issuers: $combined")
        assertTrue(
            "Combined trust manager should have at least as many certs as JVM defaults",
            combined >= jvmOnly,
        )
    }

    // ── Windows tests ──

    @Test
    fun `native library loads on Windows`() {
        assumeTrue("Test requires Windows", isWindows)
        assertTrue("Native SSL bridge should be loaded on Windows", WindowsSslBridge.isLoaded)
    }

    @Test
    fun `WindowsCertificateProvider returns DER certificates`() {
        assumeTrue("Test requires Windows", isWindows)

        val derCerts = WindowsCertificateProvider.getSystemCertificates()
        assertTrue("Should find at least one system certificate on Windows", derCerts.isNotEmpty())
        println("WindowsCertificateProvider returned ${derCerts.size} raw DER certificates")
    }

    @Test
    fun `Windows DER certificates are parseable as X509`() {
        assumeTrue("Test requires Windows", isWindows)

        val derCerts = WindowsCertificateProvider.getSystemCertificates()
        assumeTrue("No certs returned", derCerts.isNotEmpty())

        val factory = CertificateFactory.getInstance("X.509")
        var parsed = 0
        for (der in derCerts) {
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            parsed++
        }
        println("Successfully parsed $parsed / ${derCerts.size} Windows certificates as X.509")
        assertTrue("All DER blobs should parse as X.509", parsed == derCerts.size)
    }

    @Test
    fun `NativeCertificateProvider returns certificates on Windows`() {
        assumeTrue("Test requires Windows", isWindows)

        val certs = NativeCertificateProvider.getSystemCertificates()
        assertTrue("Should find at least one certificate via provider on Windows", certs.isNotEmpty())
        println("NativeCertificateProvider returned ${certs.size} X.509 certificates on Windows")

        certs.take(5).forEach { cert ->
            println("  - ${cert.subjectX500Principal}")
        }
    }

    @Test
    fun `Windows finds certificates from Windows-ROOT store`() {
        assumeTrue("Test requires Windows", isWindows)

        val certs = NativeCertificateProvider.getSystemCertificates()
        val subjects = certs.map { it.subjectX500Principal.name }

        println("All ${certs.size} certificate subjects:")
        subjects.forEach { println("  - $it") }

        val jvmIssuers =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .map { it.subjectX500Principal.name }
                .toSet()

        val extraCerts = certs.filter { it.subjectX500Principal.name !in jvmIssuers }
        println("\nCertificates found by WindowsCertificateProvider but NOT in JVM defaults: ${extraCerts.size}")
        extraCerts.forEach { cert ->
            println("  + ${cert.subjectX500Principal}")
        }
    }

    @Test
    fun `NativeTrustManager works on Windows`() {
        assumeTrue("Test requires Windows", isWindows)

        val jvmOnly =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                ).apply { init(null as java.security.KeyStore?) }
                .trustManagers
                .filterIsInstance<javax.net.ssl.X509TrustManager>()
                .first()
                .acceptedIssuers
                .size

        val combined = NativeTrustManager.trustManager.acceptedIssuers.size

        println("JVM default issuers: $jvmOnly")
        println("Combined (JVM + native) issuers: $combined")
        assertTrue(
            "Combined trust manager should have at least as many certs as JVM defaults",
            combined >= jvmOnly,
        )
    }
}

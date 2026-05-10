# Native SSL

JVM applications shipped with a bundled JRE use only the certificates baked into that JRE. Certificates added by the user, an enterprise IT policy, or a corporate proxy — such as a custom root CA or an inspection proxy — are invisible to the JVM, causing `SSLHandshakeException` failures on machines where those certificates are required.

The `native-ssl` module solves this by reading trusted certificates directly from the OS trust store on all three platforms and merging them with the JVM's default trust anchors, producing a combined `X509TrustManager` that accepts both.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.native-ssl:<version>")
}
```

## Usage

The entire API surface is the `NativeTrustManager` singleton. All properties are lazy and thread-safe.

```kotlin
import dev.nucleusframework.nucleus.nativessl.NativeTrustManager

// Ready-to-use X509TrustManager (JVM defaults + OS native certs)
val trustManager: X509TrustManager = NativeTrustManager.trustManager

// TLS SSLContext initialised with the combined trust manager
val sslContext: SSLContext = NativeTrustManager.sslContext

// SSLSocketFactory derived from sslContext
val sslSocketFactory: SSLSocketFactory = NativeTrustManager.sslSocketFactory
```

Pass these directly to your HTTP client of choice. If you use OkHttp, Ktor, or `java.net.http.HttpClient`, the purpose-built integration modules below handle the wiring for you.

## Platform Details

### macOS

Uses the Security framework via a JNI bridge (`libnucleus_ssl.dylib`, bundled in the JAR for `arm64` and `x86_64`). Two passes are performed:

1. **System anchor certificates** — `SecTrustCopyAnchorCertificates()` returns all Apple-shipped root CAs baked into macOS.
2. **User and admin domain** — `SecTrustSettingsCopyCertificates()` enumerates certificates with explicit trust settings. Each certificate is evaluated through `isTrustedRoot()`, which mirrors the logic from [JetBrains jvm-native-trusted-roots](https://github.com/JetBrains/jvm-native-trusted-roots):
    - Checks user trust settings domain first, then admin.
    - No trust settings found → live evaluation via `SecTrustEvaluateWithError`.
    - Empty trust settings array → always trusted (per Apple docs).
    - `kSecTrustSettingsResult` must be `TrustRoot`, and the certificate must be self-signed (DN equality **and** cryptographic signature verified against its own public key).
    - `kSecTrustSettingsPolicy`, if present, must be `kSecPolicyAppleSSL`.
    - Constraints with unknown keys are rejected to avoid silently misinterpreting stricter rules.

### Windows

Uses Crypt32 via a JNI bridge (`nucleus_ssl.dll`, bundled for `x64` and `ARM64`). Scans five store locations across two store types:

| Store type | What it includes |
|------------|-----------------|
| `ROOT` | Trusted root CAs — all certificates included unconditionally |
| `CA` | Intermediate CAs — validated via `CertGetCertificateChain` + `CertVerifyCertificateChainPolicy(CERT_CHAIN_POLICY_BASE)` with cache-only revocation |

Store locations scanned: `CURRENT_USER`, `LOCAL_MACHINE`, `CURRENT_USER_GROUP_POLICY`, `LOCAL_MACHINE_GROUP_POLICY`, `LOCAL_MACHINE_ENTERPRISE`. This covers certificates deployed via Group Policy and Active Directory that `SunMSCAPI` does not reach.

Deduplication is performed via SHA-1 thumbprint (`CERT_HASH_PROP_ID`), the standard Windows certificate identity.

If the JNI library fails to load, the module falls back to `SunMSCAPI` `KeyStore` (`Windows-ROOT`, `Windows-CA`, `Windows-MY`).

### Linux

Pure-JVM implementation — no native library required. Reads PEM bundle files and per-certificate directories using the same discovery paths as Go's `crypto/x509`:

| Path | Distribution |
|------|-------------|
| `/etc/ssl/certs/ca-certificates.crt` | Debian, Ubuntu, Gentoo |
| `/etc/pki/tls/certs/ca-bundle.crt` | Fedora, RHEL 6 |
| `/etc/ssl/ca-bundle.pem` | openSUSE |
| `/etc/pki/tls/cacert.pem` | OpenELEC |
| `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem` | CentOS, RHEL 7 |
| `/etc/ssl/cert.pem` | Alpine Linux |
| `/etc/ssl/certs/` *(directory)* | SLES10/SLES11 |
| `/etc/pki/tls/certs/` *(directory)* | Fedora, RHEL |
| `/system/etc/security/cacerts/` *(directory)* | Android |

Certificates are deduplicated by DER content across all sources.

## ProGuard

The `native-ssl` module uses JNI native libraries on macOS and Windows. When ProGuard is enabled, the bridge classes must be preserved. The Nucleus Gradle plugin includes these rules automatically; if you need them manually:

```proguard
-keep class dev.nucleusframework.nucleus.nativessl.mac.NativeSslBridge {
    native <methods>;
}

-keep class dev.nucleusframework.nucleus.nativessl.windows.WindowsSslBridge {
    native <methods>;
}
```

## Logging

Debug messages are emitted under the tags `NativeCertificateProvider`, `NativeSslBridge`, `WindowsCertificateProvider`, `LinuxCertificateProvider`, etc. Logging is off by default. To enable it, set the global flag from `core-runtime`:

```kotlin
import dev.nucleusframework.nucleus.core.runtime.tools.allowNucleusRuntimeLogging

allowNucleusRuntimeLogging = true
```

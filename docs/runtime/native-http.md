# Native HTTP

The native HTTP modules provide ready-to-use HTTP clients pre-configured with [`NativeTrustManager`](native-ssl.md) from the `native-ssl` module. They handle the SSL wiring so you can make HTTPS requests to hosts that use enterprise, corporate, or user-installed certificates without any manual trust store configuration.

Three integration modules are available — pick the one that matches your HTTP client:

| Module | Artifact | Client |
|--------|----------|--------|
| `native-http` | `dev.nucleusframework:nucleus.native-http` | `java.net.http.HttpClient` (JDK 11+) |
| `native-http-okhttp` | `dev.nucleusframework:nucleus.native-http-okhttp` | OkHttp 4 |
| `native-http-ktor` | `dev.nucleusframework:nucleus.native-http-ktor` | Ktor Client (engine-agnostic) |

All three pull in `native-ssl` transitively — no need to declare it separately.

---

## `native-http` — java.net.http.HttpClient

### Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.native-http:<version>")
}
```

### Usage

```kotlin
import dev.nucleusframework.nucleus.nativehttp.NativeHttpClient

// Option 1 — pre-built client
val client = NativeHttpClient.create()

// Option 2 — builder extension
val client = HttpClient.newBuilder()
    .withNativeSsl()
    .connectTimeout(Duration.ofSeconds(30))
    .build()
```

`NativeHttpClient.create()` returns a `java.net.http.HttpClient` configured with `NativeTrustManager.sslContext` and `followRedirects(NORMAL)`. The `withNativeSsl()` extension lets you compose it into an existing builder chain.

> **Note:** `create()` enables redirect following by default. If you use `withNativeSsl()` directly, remember to call `.followRedirects(HttpClient.Redirect.NORMAL)` yourself — without it, HTTP 302 responses (common with GitHub Releases, CDNs, etc.) will be treated as errors instead of being followed automatically.

---

## `native-http-okhttp` — OkHttp

### Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.native-http-okhttp:<version>")
}
```

OkHttp 4.x is pulled in transitively.

### Usage

```kotlin
import dev.nucleusframework.nucleus.nativehttp.okhttp.NativeOkHttpClient

// Option 1 — pre-built client
val client = NativeOkHttpClient.create()

// Option 2 — builder extension
val client = OkHttpClient.Builder()
    .withNativeSsl()
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
```

`NativeOkHttpClient.create()` configures `sslSocketFactory` and `trustManager` on the `OkHttpClient.Builder` using `NativeTrustManager`.

---

## `native-http-ktor` — Ktor Client

### Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.native-http-ktor:<version>")
    // Add exactly one Ktor engine:
    implementation("io.ktor:ktor-client-cio:<ktor-version>")       // CIO (coroutine-based)
    // or: ktor-client-java, ktor-client-okhttp, ktor-client-apache5
}
```

`ktor-client-core` is pulled in transitively. The module supports **CIO, Java, OkHttp, and Apache5** engines — add whichever engine you use and `installNativeSsl()` configures it automatically at runtime.

### Usage

```kotlin
import dev.nucleusframework.nucleus.nativehttp.ktor.installNativeSsl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

val client = HttpClient(CIO) {
    installNativeSsl()
}
```

`installNativeSsl()` is an `HttpClientConfig` extension. It probes at runtime for the active engine and applies the correct SSL configuration:

| Engine | Configuration applied |
|--------|-----------------------|
| CIO | `https { trustManager = NativeTrustManager.trustManager }` |
| Java | `config { sslContext(NativeTrustManager.sslContext) }` |
| OkHttp | `config { sslSocketFactory(..., NativeTrustManager.trustManager) }` |
| Apache5 | `sslContext = NativeTrustManager.sslContext` |

Engine JARs are `compileOnly` in `native-http-ktor` — only the one you declare at runtime is required.

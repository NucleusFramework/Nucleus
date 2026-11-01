package dev.nucleusframework.lab.probes.updater.network

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.nativehttp.NativeHttpClient
import dev.nucleusframework.nativehttp.ktor.installNativeSsl
import dev.nucleusframework.nativehttp.okhttp.NativeOkHttpClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.TimeUnit
import io.ktor.client.HttpClient as KtorClient

/** Each client the trust stores can be plugged into, with and without native-ssl. */
enum class LabHttpClient(
    val label: String,
    val native: Boolean,
) {
    Jdk("JDK HttpClient", native = false),
    JdkNative("JDK HttpClient + native-http", native = true),
    OkHttp("OkHttp", native = false),
    OkHttpNative("OkHttp + native-http-okhttp", native = true),
    KtorCio("Ktor CIO", native = false),
    KtorCioNative("Ktor CIO + installNativeSsl", native = true),
    KtorJavaNative("Ktor Java + installNativeSsl", native = true),
}

@Immutable
data class HttpOutcome(
    val client: LabHttpClient,
    val millis: Long,
    val status: Int? = null,
    val tls: String? = null,
    val chain: List<String> = emptyList(),
    val error: String? = null,
)

/** Port over `native-http`, `native-http-okhttp` and `native-http-ktor`. */
interface HttpClientsGateway {
    suspend fun request(
        client: LabHttpClient,
        url: String,
    ): HttpOutcome
}

@ContributesBinding(AppScope::class)
@Inject
class NativeHttpClientsGateway : HttpClientsGateway {
    override suspend fun request(
        client: LabHttpClient,
        url: String,
    ): HttpOutcome {
        val (result, millis) =
            timedMillis {
                runCatching {
                    when (client) {
                        LabHttpClient.Jdk -> jdk(HttpClient.newBuilder(), url)
                        LabHttpClient.JdkNative ->
                            jdk(
                                with(NativeHttpClient) { HttpClient.newBuilder().withNativeSsl() },
                                url,
                            )
                        LabHttpClient.OkHttp -> okHttp(OkHttpClient.Builder(), url)
                        LabHttpClient.OkHttpNative ->
                            okHttp(
                                with(NativeOkHttpClient) { OkHttpClient.Builder().withNativeSsl() },
                                url,
                            )
                        LabHttpClient.KtorCio -> ktor(KtorClient(CIO), url)
                        LabHttpClient.KtorCioNative -> ktor(KtorClient(CIO) { installNativeSsl() }, url)
                        LabHttpClient.KtorJavaNative -> ktor(KtorClient(Java) { installNativeSsl() }, url)
                    }
                }
            }
        return result.fold(
            onSuccess = { it.copy(client = client, millis = millis) },
            onFailure = { HttpOutcome(client, millis, error = it.rootCauseDescription()) },
        )
    }

    private fun jdk(
        builder: HttpClient.Builder,
        url: String,
    ): HttpOutcome {
        val response =
            builder.connectTimeout(TIMEOUT).build().use { http ->
                http.send(
                    HttpRequest
                        .newBuilder(URI(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }
        val session = response.sslSession().orElse(null)
        return HttpOutcome(
            client = LabHttpClient.Jdk,
            millis = 0,
            status = response.statusCode(),
            tls = session?.let { "${it.protocol} ${it.cipherSuite}" },
            chain =
                session
                    ?.peerCertificates
                    ?.filterIsInstance<X509Certificate>()
                    ?.map {
                        it.subjectX500Principal.name.commonName()
                    }.orEmpty(),
        )
    }

    private fun okHttp(
        builder: OkHttpClient.Builder,
        url: String,
    ): HttpOutcome {
        val client = builder.callTimeout(TIMEOUT.seconds, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val handshake = response.handshake
            return HttpOutcome(
                client = LabHttpClient.OkHttp,
                millis = 0,
                status = response.code,
                tls = handshake?.let { "${it.tlsVersion.javaName} ${it.cipherSuite.javaName}" },
                chain =
                    handshake
                        ?.peerCertificates
                        ?.filterIsInstance<X509Certificate>()
                        ?.map {
                            it.subjectX500Principal.name.commonName()
                        }.orEmpty(),
            )
        }
    }

    private suspend fun ktor(
        client: KtorClient,
        url: String,
    ): HttpOutcome = client.use { HttpOutcome(LabHttpClient.KtorCio, 0, status = it.get(url).status.value) }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** The innermost cause, which is where TLS failures say what they mean (PKIX path building failed…). */
private fun Throwable.rootCauseDescription(): String {
    var root: Throwable = this
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val outer = if (root === this) "" else " (via ${this::class.simpleName})"
    return root.summary + outer
}

package dev.nucleusframework.nucleus.nativehttp

import dev.nucleusframework.nucleus.nativessl.NativeTrustManager
import java.net.http.HttpClient
import javax.net.ssl.SSLParameters

object NativeHttpClient {
    fun create(): HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .withNativeSsl()
            .build()

    fun HttpClient.Builder.withNativeSsl(): HttpClient.Builder =
        sslContext(NativeTrustManager.sslContext)
            .sslParameters(
                SSLParameters().apply {
                    needClientAuth = false
                },
            )
}

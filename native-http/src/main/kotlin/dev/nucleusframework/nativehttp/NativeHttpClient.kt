package dev.nucleusframework.nativehttp

import dev.nucleusframework.nativessl.NativeTrustManager
import java.net.http.HttpClient
import javax.net.ssl.SSLParameters

public object NativeHttpClient {
    public fun create(): HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .withNativeSsl()
            .build()

    public fun HttpClient.Builder.withNativeSsl(): HttpClient.Builder =
        sslContext(NativeTrustManager.sslContext)
            .sslParameters(
                SSLParameters().apply {
                    needClientAuth = false
                },
            )
}

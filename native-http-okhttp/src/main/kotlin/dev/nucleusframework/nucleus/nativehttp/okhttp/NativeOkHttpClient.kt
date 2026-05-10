package dev.nucleusframework.nucleus.nativehttp.okhttp

import dev.nucleusframework.nucleus.nativessl.NativeTrustManager
import okhttp3.OkHttpClient

object NativeOkHttpClient {
    fun create(): OkHttpClient =
        OkHttpClient
            .Builder()
            .withNativeSsl()
            .build()

    fun OkHttpClient.Builder.withNativeSsl(): OkHttpClient.Builder =
        sslSocketFactory(NativeTrustManager.sslSocketFactory, NativeTrustManager.trustManager)
}

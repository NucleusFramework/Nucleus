package dev.nucleusframework.nativehttp.okhttp

import dev.nucleusframework.nativessl.NativeTrustManager
import okhttp3.OkHttpClient

public object NativeOkHttpClient {
    public fun create(): OkHttpClient =
        OkHttpClient
            .Builder()
            .withNativeSsl()
            .build()

    public fun OkHttpClient.Builder.withNativeSsl(): OkHttpClient.Builder =
        sslSocketFactory(NativeTrustManager.sslSocketFactory, NativeTrustManager.trustManager)
}

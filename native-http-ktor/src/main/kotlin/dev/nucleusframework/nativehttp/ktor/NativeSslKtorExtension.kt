package dev.nucleusframework.nativehttp.ktor

import dev.nucleusframework.nativessl.NativeTrustManager
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig

public fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installNativeSsl() {
    engine {
        tryConfigureCio(this)
        tryConfigureJava(this)
        tryConfigureOkHttp(this)
        tryConfigureApache5(this)
    }
}

private fun tryConfigureCio(config: Any) {
    try {
        if (config is io.ktor.client.engine.cio.CIOEngineConfig) {
            config.https {
                trustManager = NativeTrustManager.trustManager
            }
        }
    } catch (_: NoClassDefFoundError) {
        // CIO engine not on classpath
    }
}

private fun tryConfigureJava(config: Any) {
    try {
        if (config is io.ktor.client.engine.java.JavaHttpConfig) {
            config.config {
                sslContext(NativeTrustManager.sslContext)
            }
        }
    } catch (_: NoClassDefFoundError) {
        // Java engine not on classpath
    }
}

private fun tryConfigureOkHttp(config: Any) {
    try {
        if (config is io.ktor.client.engine.okhttp.OkHttpConfig) {
            config.config {
                sslSocketFactory(NativeTrustManager.sslSocketFactory, NativeTrustManager.trustManager)
            }
        }
    } catch (_: NoClassDefFoundError) {
        // OkHttp engine not on classpath
    }
}

private fun tryConfigureApache5(config: Any) {
    try {
        if (config is io.ktor.client.engine.apache5.Apache5EngineConfig) {
            config.sslContext = NativeTrustManager.sslContext
        }
    } catch (_: NoClassDefFoundError) {
        // Apache5 engine not on classpath
    }
}

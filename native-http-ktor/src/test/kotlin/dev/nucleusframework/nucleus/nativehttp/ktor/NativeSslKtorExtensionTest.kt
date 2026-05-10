package dev.nucleusframework.nucleus.nativehttp.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSslKtorExtensionTest {
    @Test
    fun cioEngineHttpsGetReturns200() =
        runBlocking {
            val client =
                HttpClient(CIO) {
                    installNativeSsl()
                }
            val response = client.get("https://www.google.com")
            assertEquals(200, response.status.value)
            assertTrue(response.bodyAsText().isNotEmpty())
            client.close()
        }

    @Test
    fun javaEngineHttpsGetReturns200() =
        runBlocking {
            val client =
                HttpClient(Java) {
                    installNativeSsl()
                }
            val response = client.get("https://www.google.com")
            assertEquals(200, response.status.value)
            assertTrue(response.bodyAsText().isNotEmpty())
            client.close()
        }
}

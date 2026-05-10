package dev.nucleusframework.nucleus.nativehttp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class NativeHttpClientTest {
    @Test
    fun httpsGetReturns200() {
        val client = NativeHttpClient.create()
        val request =
            HttpRequest
                .newBuilder(URI.create("https://www.google.com"))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertTrue(response.body().isNotEmpty())
    }
}

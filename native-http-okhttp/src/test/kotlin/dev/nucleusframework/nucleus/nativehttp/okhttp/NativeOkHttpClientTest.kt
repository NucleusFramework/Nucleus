package dev.nucleusframework.nucleus.nativehttp.okhttp

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOkHttpClientTest {
    @Test
    fun httpsGetReturns200() {
        val client = NativeOkHttpClient.create()
        val request =
            Request
                .Builder()
                .url("https://www.google.com")
                .get()
                .build()
        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
        assertTrue(response.body!!.string().isNotEmpty())
    }
}

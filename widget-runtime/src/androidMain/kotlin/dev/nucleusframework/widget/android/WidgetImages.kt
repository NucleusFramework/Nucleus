@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.WidgetElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes the images of a render before it is turned into [android.widget.RemoteViews], which only
 * take bitmaps. An [ImageProvider.appResource] path is read from the app's `assets/`; URLs are
 * downloaded once and cached. SVG is not decoded: such an image is left out.
 */
internal class WidgetImages(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    /** When each failing source last failed: a dead URL must not stall every render on its timeout. */
    private val failures = ConcurrentHashMap<String, Long>()

    /**
     * The bitmap of every image of [root], by provider, loaded in parallel; a missing entry is an
     * image that failed. Never throws for an image: a source that cannot be read is left out.
     */
    suspend fun load(root: WidgetElement): Map<ImageProvider, Bitmap> =
        withContext(io) {
            val providers = mutableListOf<ImageProvider>()
            collect(root) { provider -> if (providers.none { it === provider }) providers += provider }
            val bitmaps = providers.map { provider -> async { decode(provider) } }.awaitAll()
            IdentityHashMap<ImageProvider, Bitmap>().apply {
                providers.zip(bitmaps).forEach { (provider, bitmap) -> if (bitmap != null) put(provider, bitmap) }
            }
        }

    private inline fun collect(
        element: WidgetElement,
        onImage: (ImageProvider) -> Unit,
    ) {
        val pending = ArrayDeque<WidgetElement>().apply { add(element) }
        while (pending.isNotEmpty()) {
            when (val next = pending.removeFirst()) {
                is WidgetElement.Image -> onImage(next.provider)
                is WidgetElement.Column -> pending.addAll(next.children)
                is WidgetElement.Row -> pending.addAll(next.children)
                else -> Unit
            }
        }
    }

    private fun decode(provider: ImageProvider): Bitmap? {
        val key =
            provider.appResource?.let { "asset:$it" }
                ?: provider.url?.let { "url:$it" }
                ?: return provider.bytes?.takeIf { provider.mimeType != SVG }?.let(::decodeBytes)
        cache.get(key)?.let { return it }
        val failed = failures[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_AFTER_MS) return null
        val bitmap =
            try {
                provider.appResource?.let(::decodeAsset) ?: provider.url?.let { url -> decodeBytes(download(url)) }
            } catch (
                // Anything a source can throw: IOException, SecurityException without the INTERNET
                // permission, IllegalArgumentException for a URL Java cannot parse.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Widget image $key could not be read", e)
                null
            }
        if (bitmap == null) failures[key] = SystemClock.elapsedRealtime() else failures.remove(key)
        return bitmap?.also { cache.put(key, it) }
    }

    private fun decodeAsset(path: String): Bitmap? =
        sampled { options -> context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) } }

    private fun decodeBytes(bytes: ByteArray): Bitmap? =
        sampled { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    /** Decodes with a sample size that keeps the longest side under [MAX_SIDE]: RemoteViews have a bitmap budget. */
    private inline fun sampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Downloads [url] once (decoding needs two passes), refusing anything larger than [MAX_DOWNLOAD]. */
    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode !in HTTP_OK_RANGE) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_DOWNLOAD) throw IOException("Image larger than $MAX_DOWNLOAD bytes")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "NucleusWidgets"
        const val SVG = "image/svg+xml"
        const val MAX_SIDE = 512
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val TIMEOUT_MS = 5_000
        const val RETRY_AFTER_MS = 60_000L
        const val MAX_DOWNLOAD = 10 * 1024 * 1024
        const val BUFFER = 16 * 1024
        val HTTP_OK_RANGE = 200..299
    }
}

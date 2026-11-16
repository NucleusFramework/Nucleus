package dev.nucleusframework.widget.macos.host

import dev.nucleusframework.widget.ImageProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * Puts every image a render shows into the store, where the sandboxed extension can read it: it
 * cannot reach the app's resources (an app under `~/Applications` is outside its sandbox), nor the
 * network (no client entitlement, and WidgetKit draws no `AsyncImage`). Files are keyed by content
 * or URL, so an unchanged image is written once.
 */
internal class StoreImages(
    private val store: WidgetStore,
    private val resourcesDir: File?,
) {
    /** The store-relative path of [provider], or `null` when it cannot be had. */
    fun resolve(provider: ImageProvider): String? =
        try {
            provider.appResource?.let(::resource)
                ?: provider.bytes?.let { bytes(it, provider.mimeType) }
                ?: provider.url?.let(::download)
        } catch (e: IOException) {
            logger.warning { "Widget image unavailable: ${e.message}" }
            null
        }

    private fun resource(path: String): String? {
        val source = resourcesDir?.resolve(path)
        if (source == null || !source.isFile) {
            logger.warning {
                "Widget image resource '$path' not found in ${resourcesDir ?: "(no app resources directory)"}"
            }
            return null
        }
        val relative = "images/res/$path"
        val target = store.root.resolve(relative)
        if (!target.isFile || target.length() != source.length() || target.lastModified() < source.lastModified()) {
            WidgetStore.writeAtomically(target, source.readBytes())
        }
        return relative
    }

    private fun bytes(
        bytes: ByteArray,
        mimeType: String?,
    ): String {
        val relative = "images/data/${sha256(bytes)}.${extension(mimeType)}"
        val target = store.root.resolve(relative)
        if (!target.isFile) WidgetStore.writeAtomically(target, bytes)
        return relative
    }

    private fun download(url: String): String? {
        val relative = "images/url/${sha256(url.toByteArray())}"
        val target = store.root.resolve(relative)
        if (target.isFile) return relative
        val connection = URI(url).toURL().openConnection() as? HttpURLConnection ?: return null
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        try {
            if (connection.responseCode !in SUCCESS) {
                logger.warning { "Widget image $url: HTTP ${connection.responseCode}" }
                return null
            }
            WidgetStore.writeAtomically(target, connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
        return relative
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
        const val HASH_CHARS = 32
        val SUCCESS = 200..299
        val logger: Logger = Logger.getLogger(StoreImages::class.java.name)

        fun sha256(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(HASH_CHARS)

        fun extension(mimeType: String?): String =
            when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/svg+xml" -> "svg"
                "image/gif" -> "gif"
                else -> "png"
            }
    }
}

package dev.nucleusframework.updater.internal.delta

import dev.nucleusframework.updater.UpdateFile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A ready-to-run differential download, plus the block map to cache once it succeeds. */
internal class ResolvedDelta(
    val download: DeltaDownload,
    val blockMapGzip: ByteArray?,
)

/**
 * Decides whether the artifact about to be downloaded can be assembled from a copy already on this
 * machine, and gathers the two block maps needed to do it. Returns `null` whenever the prerequisites
 * are missing — no local artifact, no cached block map, mismatched format — which is the normal case
 * for a first update and never an error.
 *
 * electron-builder emits two flavours of block map and the flavour dictates where both maps come
 * from. AppImages and nsis-web packages carry their map in their own tail: the new map is read with a
 * single ranged request on that tail and the old map is read from the local file. Every other format
 * publishes a standalone `<artifact>.blockmap` (NSIS installers, macOS ZIPs, DMGs): the new map is
 * downloaded and the old one comes from the cache, where the previous update left it.
 *
 * The flavour is keyed on the artifact format, not on `blockMapSize`: the manifests this project
 * publishes set `blockMapSize` to the length of the standalone companion file for *every* artifact
 * that has one (see `UpdateYmlGenerator` and `.github/actions/generate-update-yml`), so its presence
 * says nothing about where the map lives.
 */
internal class DeltaResolver(
    private val httpClient: HttpClient,
    private val authHeaders: Map<String, String>,
    private val cache: UpdateCache,
    private val appImagePath: () -> String? = { System.getenv(APPIMAGE_ENV) },
) {
    fun resolve(
        target: UpdateFile,
        blockMapUrl: String,
        destination: File,
    ): ResolvedDelta? {
        val oldFile = resolveOldArtifact(target) ?: return null
        return if (embedsBlockMap(target)) {
            resolveEmbedded(target, oldFile, destination)
        } else {
            resolveStandalone(target, blockMapUrl, oldFile, destination)
        }
    }

    private fun resolveEmbedded(
        target: UpdateFile,
        oldFile: File,
        destination: File,
    ): ResolvedDelta {
        val trailerSize = target.blockMapSize!! + BlockMapCodec.EMBEDDED_HEADER_SIZE
        if (trailerSize <= 0 || trailerSize >= target.size) {
            throw DeltaUnavailableException("Manifest declares an implausible blockMapSize for ${target.fileName}")
        }
        val downloader = DifferentialDownloader(httpClient, authHeaders)
        val trailer = downloader.readRange(target.url, target.size - trailerSize, target.size - 1)
        val newMap = BlockMapCodec.parseDeflateRaw(trailer.copyOf(trailer.size - BlockMapCodec.EMBEDDED_HEADER_SIZE))
        val plan = DeltaPlan.compute(BlockMapCodec.readEmbedded(oldFile), newMap)
        return ResolvedDelta(deltaDownload(target, oldFile, destination, plan, trailer), blockMapGzip = null)
    }

    private fun resolveStandalone(
        target: UpdateFile,
        blockMapUrl: String,
        oldFile: File,
        destination: File,
    ): ResolvedDelta? {
        val oldMapGzip = cache.blockMap.takeIf { it.isFile && it.length() > 0 }?.readBytes() ?: return null
        val newMapGzip = fetch(blockMapUrl)
        val plan = DeltaPlan.compute(BlockMapCodec.parseGzip(oldMapGzip), BlockMapCodec.parseGzip(newMapGzip))
        return ResolvedDelta(deltaDownload(target, oldFile, destination, plan, trailer = null), newMapGzip)
    }

    private fun deltaDownload(
        target: UpdateFile,
        oldFile: File,
        destination: File,
        plan: List<Operation>,
        trailer: ByteArray?,
    ) = DeltaDownload(
        url = target.url,
        oldFile = oldFile,
        target = destination,
        operations = plan,
        expectedSize = target.size,
        expectedSha512 = target.sha512,
        trailer = trailer,
    )

    /**
     * The local copy to reuse: the running AppImage when updating one — it is always present, so the
     * very first update is already differential — and otherwise the artifact the previous update
     * cached, provided it is of the same format as the one being fetched.
     */
    private fun resolveOldArtifact(target: UpdateFile): File? {
        val extension = target.fileName.substringAfterLast('.', "").lowercase()
        if (extension == APPIMAGE_EXTENSION) {
            appImagePath()?.let { path ->
                val running = File(path)
                if (running.isFile && running.length() > 0) return running
            }
        }
        val cached = cache.read() ?: return null
        if (cached.extension != extension) return null
        return cache.artifact.takeIf { it.isFile && it.length() > 0 }
    }

    /** Downloads a small resource (a block map) fully into memory. */
    private fun fetch(url: String): ByteArray {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
        authHeaders.forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != HTTP_OK) {
            throw DeltaUnavailableException("HTTP ${response.statusCode()} for $url")
        }
        if (response.body().isEmpty()) throw DeltaUnavailableException("Empty block map at $url")
        return response.body()
    }

    internal companion object {
        private const val HTTP_OK = 200
        private const val APPIMAGE_ENV = "APPIMAGE"
        private const val APPIMAGE_EXTENSION = "appimage"

        /**
         * Formats that carry their block map appended to the artifact. Everything else publishes a
         * standalone `<artifact>.blockmap` — and still declares a `blockMapSize` in the manifest,
         * which is that companion file's length, so the flavour cannot be inferred from it.
         *
         * For these formats `blockMapSize` is instead the length of the appended payload, which is
         * also written as the artifact's own last four bytes. electron-builder reports it that way,
         * and `.github/actions/generate-update-yml` reads it back off the tail for AppImages, since
         * they have no companion file to measure.
         */
        private val EMBEDDED_BLOCK_MAP_EXTENSIONS = setOf(APPIMAGE_EXTENSION, "7z")

        fun embedsBlockMap(target: UpdateFile): Boolean =
            target.blockMapSize != null &&
                target.fileName.substringAfterLast('.', "").lowercase() in EMBEDDED_BLOCK_MAP_EXTENSIONS
    }
}

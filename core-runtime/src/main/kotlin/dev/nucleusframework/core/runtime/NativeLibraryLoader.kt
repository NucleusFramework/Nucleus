package dev.nucleusframework.core.runtime

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Centralized native library loader with persistent caching.
 *
 * Extracts native libraries from JAR resources into a stable cache directory
 * (`~/.cache/nucleus/native/` on macOS/Linux, `%LOCALAPPDATA%/nucleus/native/` on Windows)
 * so that subsequent launches skip the extraction I/O entirely.
 *
 * The cache is content-addressed: a fingerprint derived from the JAR entry
 * CRC-32 and size (read from ZIP headers — zero I/O cost) is part of the
 * extraction path (`<cacheDir>/<platform>/<fingerprint>/<library>`). Different
 * library versions therefore never share a file, so a concurrently running
 * application using another version can never swap the library between
 * validation and load (issue #304).
 */
public object NativeLibraryLoader {
    private val logger = Logger.getLogger(NativeLibraryLoader::class.java.simpleName)
    private val loadedLibraries = mutableSetOf<String>()
    private val lock = Any()

    /**
     * Loads a native library by name.
     *
     * @param libraryName the base library name (e.g. "nucleus_systemcolor")
     * @param callerClass a class from the module's JAR, used to locate the resource
     * @param resourcePrefix the JAR resource prefix (default: "/nucleus/native")
     * @param sidecarFiles platform-bare filenames (without `lib`/`.dll`/`.so`/`.dylib`
     *     mapping) extracted to the same cache directory as [libraryName] before
     *     it is loaded, so the OS DLL search path can resolve them. Used for
     *     vendor-supplied helper DLLs (e.g. `WebView2Loader.dll` next to the
     *     WebView2-using sample DLL on Windows).
     * @return true if the library was loaded successfully
     */
    public fun load(
        libraryName: String,
        callerClass: Class<*>,
        resourcePrefix: String = "/nucleus/native",
        sidecarFiles: List<String> = emptyList(),
    ): Boolean {
        synchronized(lock) {
            if (libraryName in loadedLibraries) return true

            // Try system library path first (packaged app with native libs on java.library.path)
            if (trySystemLoad(libraryName)) return true

            // Fallback: extract from JAR with persistent cache
            return tryJarExtraction(libraryName, callerClass, resourcePrefix, sidecarFiles)
        }
    }

    private fun trySystemLoad(libraryName: String): Boolean =
        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun tryJarExtraction(
        libraryName: String,
        callerClass: Class<*>,
        resourcePrefix: String,
        sidecarFiles: List<String>,
    ): Boolean {
        try {
            val platform = resolvePlatform()
            val fileName = mapLibraryFileName(libraryName, platform)
            val resourcePath = "$resourcePrefix/${platform.resourceDir}/$fileName"

            val resourceUrl =
                callerClass.getResource(resourcePath) ?: run {
                    logger.fine("Native library not available on this platform: $resourcePath")
                    return false
                }

            // Sidecars must sit next to the main library so the dynamic linker
            // can find them (Windows: SetDllDirectory or implicit search;
            // Linux: rpath/$ORIGIN; macOS: @loader_path). They therefore share
            // the content-addressed directory, whose key covers the main
            // library and all sidecars.
            val sidecarUrls =
                sidecarFiles.mapNotNull { sidecar ->
                    callerClass
                        .getResource("$resourcePrefix/${platform.resourceDir}/$sidecar")
                        ?.let { sidecar to it }
                }

            val fingerprint =
                (listOf(resourceUrl) + sidecarUrls.map { it.second })
                    .joinToString("_") { resolveFingerprint(it) }
            val cacheDir = resolveCacheDir().resolve(platform.resourceDir).resolve(fingerprint)
            Files.createDirectories(cacheDir)

            for ((sidecar, url) in sidecarUrls) {
                extractIfAbsent(url, cacheDir.resolve(sidecar))
            }
            val loadPath = extractIfAbsent(resourceUrl, cacheDir.resolve(fileName))

            System.load(loadPath.toAbsolutePath().toString())
            loadedLibraries += libraryName
            return true
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to load $libraryName native library", e)
            return false
        }
    }

    /**
     * Ensures [target] holds the resource content and returns the path to load.
     * The parent directory is content-addressed, so an existing file already
     * has the right content and extraction is skipped. A concurrent extraction
     * by another process writes the same bytes: if the atomic move loses that
     * race, the existing target (or, failing that, our temp copy) is used.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun extractIfAbsent(
        resourceUrl: URL,
        target: Path,
    ): Path {
        if (Files.exists(target)) return target

        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        resourceUrl.openStream().use { input ->
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
        }
        return try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, target)
            }
            target
        } catch (_: Exception) {
            if (Files.exists(target)) {
                // Another process extracted the same content first
                try {
                    Files.deleteIfExists(tmp)
                } catch (_: Exception) {
                    // Best effort — stale .tmp files are harmless
                }
                target
            } else {
                logger.fine("Cache move failed, loading from temp: $tmp")
                tmp
            }
        }
    }

    /**
     * Builds a fingerprint string from JAR entry metadata, safe for use as a
     * directory name on all platforms.
     * For `jar:` URLs the CRC-32 and size come straight from the ZIP central directory.
     * For `file:` URLs (IDE dev mode) we use file size and last-modified timestamp.
     */
    internal fun resolveFingerprint(resourceUrl: URL): String {
        val connection = resourceUrl.openConnection()
        if (connection is JarURLConnection) {
            val entry = connection.jarEntry
            return "${entry.crc}-${entry.size}"
        }
        // file: URL fallback (running from IDE classes dir)
        return "${connection.contentLengthLong}-${connection.lastModified}"
    }

    private fun resolveCacheDir(): Path {
        val os = System.getProperty("os.name", "").lowercase()
        val base =
            when {
                os.contains("win") -> {
                    val localAppData = System.getenv("LOCALAPPDATA")
                    if (localAppData != null) {
                        Path.of(localAppData)
                    } else {
                        Path.of(System.getProperty("user.home"), "AppData", "Local")
                    }
                }
                os.contains("mac") -> {
                    Path.of(System.getProperty("user.home"), "Library", "Caches")
                }
                else -> {
                    val xdgCache = System.getenv("XDG_CACHE_HOME")
                    if (xdgCache != null) {
                        Path.of(xdgCache)
                    } else {
                        Path.of(System.getProperty("user.home"), ".cache")
                    }
                }
            }
        return base.resolve("nucleus").resolve("native")
    }

    private fun resolvePlatform(): NativePlatform {
        val os = System.getProperty("os.name", "").lowercase()
        val arch =
            System.getProperty("os.arch").let {
                if (it == "aarch64" || it == "arm64") "aarch64" else "x64"
            }
        return when {
            os.contains("mac") || os.contains("darwin") -> NativePlatform("darwin", arch)
            os.contains("win") -> NativePlatform("win32", arch)
            else -> NativePlatform("linux", arch)
        }
    }

    private fun mapLibraryFileName(
        libraryName: String,
        platform: NativePlatform,
    ): String =
        when {
            platform.os == "win32" -> "$libraryName.dll"
            platform.os == "darwin" -> "lib$libraryName.dylib"
            else -> "lib$libraryName.so"
        }

    private data class NativePlatform(
        val os: String,
        val arch: String,
    ) {
        val resourceDir: String get() = "$os-$arch"
    }
}

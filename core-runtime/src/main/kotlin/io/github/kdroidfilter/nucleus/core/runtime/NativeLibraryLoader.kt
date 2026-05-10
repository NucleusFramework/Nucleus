package io.github.kdroidfilter.nucleus.core.runtime

import java.net.JarURLConnection
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
 * The cache is invalidated per-library using a fingerprint derived from the
 * JAR entry CRC-32 and size (read from ZIP headers — zero I/O cost).
 */
object NativeLibraryLoader {
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
    fun load(
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

            // Read fingerprint from JAR entry metadata (CRC-32 + size from ZIP header, no I/O)
            val fingerprint = resolveFingerprint(resourceUrl)

            val cacheDir = resolveCacheDir().resolve(platform.resourceDir)
            Files.createDirectories(cacheDir)
            val target = cacheDir.resolve(fileName)
            val fingerprintFile = cacheDir.resolve("$fileName.fingerprint")

            // Extract sidecars regardless of cache state — they share the
            // canonical cache directory so the dynamic linker can find
            // them next to the main library (Windows: SetDllDirectory or
            // implicit search; Linux: rpath/$ORIGIN; macOS: @loader_path).
            extractSidecars(callerClass, resourcePrefix, platform, cacheDir, sidecarFiles)

            if (Files.exists(target) && isCacheValid(fingerprintFile, fingerprint)) {
                System.load(target.toAbsolutePath().toString())
                loadedLibraries += libraryName
                return true
            }

            // Cache miss — extract from JAR into a temp file
            val tmp = Files.createTempFile(cacheDir, libraryName, ".tmp")
            resourceUrl.openStream().use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }

            // Try to move into the canonical cache location.
            // On Windows the target may be locked by another process that loaded
            // the previous version — in that case, load directly from the temp file.
            val loadPath =
                try {
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: Exception) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    writeFingerprint(fingerprintFile, fingerprint)
                    target
                } catch (_: Exception) {
                    // Target locked — load from temp, clean up on next launch
                    logger.fine("Cache file locked, loading from temp: $tmp")
                    tmp
                }

            System.load(loadPath.toAbsolutePath().toString())
            loadedLibraries += libraryName
            return true
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to load $libraryName native library", e)
            return false
        }
    }

    /**
     * Extracts auxiliary files from the JAR into [cacheDir]. Each entry in
     * [sidecarFiles] is a bare filename (e.g. `WebView2Loader.dll`) located
     * under the same per-platform resource subdirectory as the main library.
     * Files are only re-extracted when their JAR fingerprint changes, so
     * subsequent launches skip the I/O.
     */
    private fun extractSidecars(
        callerClass: Class<*>,
        resourcePrefix: String,
        platform: NativePlatform,
        cacheDir: Path,
        sidecarFiles: List<String>,
    ) {
        for (sidecar in sidecarFiles) {
            try {
                val resourcePath = "$resourcePrefix/${platform.resourceDir}/$sidecar"
                val resourceUrl = callerClass.getResource(resourcePath) ?: continue
                val fingerprint = resolveFingerprint(resourceUrl)
                val target = cacheDir.resolve(sidecar)
                val fingerprintFile = cacheDir.resolve("$sidecar.fingerprint")
                if (Files.exists(target) && isCacheValid(fingerprintFile, fingerprint)) continue

                val tmp = Files.createTempFile(cacheDir, sidecar, ".tmp")
                resourceUrl.openStream().use { input ->
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
                try {
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: Exception) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    writeFingerprint(fingerprintFile, fingerprint)
                } catch (_: Exception) {
                    // Sidecar locked by another process — leave the existing
                    // file in place, the dynamic linker will find it.
                    logger.fine("Sidecar $sidecar move failed, existing copy retained")
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Failed to extract sidecar $sidecar", e)
            }
        }
    }

    /**
     * Builds a fingerprint string from JAR entry metadata.
     * For `jar:` URLs the CRC-32 and size come straight from the ZIP central directory.
     * For `file:` URLs (IDE dev mode) we use file size and last-modified timestamp.
     */
    private fun resolveFingerprint(resourceUrl: java.net.URL): String {
        val connection = resourceUrl.openConnection()
        if (connection is JarURLConnection) {
            val entry = connection.jarEntry
            return "${entry.crc}:${entry.size}"
        }
        // file: URL fallback (running from IDE classes dir)
        return "${connection.contentLengthLong}:${connection.lastModified}"
    }

    private fun isCacheValid(
        fingerprintFile: Path,
        currentFingerprint: String,
    ): Boolean =
        try {
            Files.exists(fingerprintFile) &&
                Files.readString(fingerprintFile).trim() == currentFingerprint
        } catch (_: Exception) {
            false
        }

    private fun writeFingerprint(
        fingerprintFile: Path,
        fingerprint: String,
    ) {
        try {
            Files.writeString(fingerprintFile, fingerprint)
        } catch (_: Exception) {
            // Non-critical — worst case we re-extract next time
        }
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

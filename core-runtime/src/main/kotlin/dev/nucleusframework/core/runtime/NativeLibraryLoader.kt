package dev.nucleusframework.core.runtime

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Centralized native library loader with persistent caching.
 *
 * Extracts native libraries from JAR resources into a stable cache directory
 * so that subsequent launches skip the extraction I/O entirely. The default is
 * `~/Library/Caches/nucleus/native/` on macOS, `$XDG_CACHE_HOME/nucleus/native/`
 * (`~/.cache/...`) on Linux and `%LOCALAPPDATA%\nucleus\native\` on Windows.
 *
 * Applications that keep all their data under one directory can relocate the
 * cache (issue #303). Each candidate below is tried in turn, the next one taking
 * over when the previous cannot be created or written to:
 * 1. the `nucleus.native.cacheDir` system property ([CACHE_DIR_PROPERTY]),
 *    e.g. `-Dnucleus.native.cacheDir=/var/lib/acme/native` in the launcher's JVM
 *    options. The value is used verbatim — neither the JVM nor the jpackage
 *    launcher expands `${user.home}`, so a path computed at run time goes through
 *    [cacheDirectory] instead. It must name a per-user, writable location: an
 *    install directory (`$APPDIR`, `/opt/...`, `C:\Program Files\...`) is
 *    read-only for a standard user, and writing inside a macOS `.app` bundle
 *    breaks its signature;
 * 2. [cacheDirectory], set from `main()` before the first native library loads;
 * 3. the platform default above.
 *
 * The directory is resolved once, at the first extraction, and the
 * content-addressed layout described below is kept under it. A configured
 * directory that cannot be created or written to is logged and replaced by
 * the platform default rather than failing the load.
 *
 * Packaged applications built by the Nucleus Gradle plugin never extract
 * anything: the plugin moves the libraries out of the JARs into the directory
 * named by the `nucleus.native.libraryPath` system property (sandboxed store
 * builds put them on `java.library.path` instead). This setting only matters
 * for fat JARs, IDE runs and distributions that bypass the plugin.
 *
 * The cache is content-addressed: a fingerprint derived from the JAR entry
 * CRC-32 and size (read from ZIP headers — zero I/O cost) is part of the
 * extraction path (`<cacheDir>/<platform>/<fingerprint>/<library>`). Different
 * library versions therefore never share a file, so a concurrently running
 * application using another version can never swap the library between
 * validation and load (issue #304).
 */
@Suppress("TooManyFunctions")
public object NativeLibraryLoader {
    /**
     * System property naming the directory native libraries are extracted to.
     * Takes precedence over [cacheDirectory]. A relative path is resolved
     * against the working directory; a blank value is ignored.
     */
    public const val CACHE_DIR_PROPERTY: String = "nucleus.native.cacheDir"

    private val logger = Logger.getLogger(NativeLibraryLoader::class.java.name)
    private val loadedLibraries = mutableSetOf<String>()
    private val lock = Any()

    /** Programmatic override, see [cacheDirectory]. Guarded by [lock]. */
    private var configuredCacheDir: Path? = null

    /** The directory in use once the first extraction happened. Guarded by [lock]. */
    private var resolvedCacheDir: Path? = null

    /**
     * Directory native libraries are extracted to, overriding the platform
     * default. The [CACHE_DIR_PROPERTY] system property, when set, still wins.
     *
     * Must be set before the first native library is extracted — typically the
     * first statement of `main()`. Later assignments cannot move libraries the
     * process already loaded, so they are ignored with a warning.
     * `null` restores the platform default.
     *
     * The getter echoes this override only. It reports `null` when the cache was
     * relocated through [CACHE_DIR_PROPERTY], and still reports the requested
     * path when that path turned out to be unusable and the platform default was
     * used instead.
     */
    public var cacheDirectory: Path?
        get() = synchronized(lock) { configuredCacheDir }
        set(value) {
            synchronized(lock) {
                if (resolvedCacheDir != null) {
                    logger.warning(
                        "Ignoring cacheDirectory=$value: native libraries were already " +
                            "extracted to $resolvedCacheDir. Set it before the first native load.",
                    )
                    return
                }
                configuredCacheDir = value
            }
        }

    /**
     * Directory the Nucleus Gradle plugin moved the packaged application's
     * libraries to. The plugin only moves them when it finds
     * `META-INF/nucleus/bundled-native-libraries` (shipped by this module) on
     * the classpath, since an older loader would not look here.
     */
    private const val LIBRARY_PATH_PROPERTY = "nucleus.native.libraryPath"

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

            // Packaged app: the plugin moved the library out of its JAR
            if (tryBundledLoad(libraryName)) return true

            // Sandboxed packaged app: native libs on java.library.path
            if (trySystemLoad(libraryName)) return true

            // Fallback: extract from JAR with persistent cache
            return tryJarExtraction(libraryName, callerClass, resourcePrefix, sidecarFiles)
        }
    }

    /**
     * Loads [libraryName] from [LIBRARY_PATH_PROPERTY]. Sidecars need no
     * handling: the plugin moved them to the same directory.
     */
    @Suppress("SwallowedException")
    private fun tryBundledLoad(libraryName: String): Boolean {
        val dir = System.getProperty(LIBRARY_PATH_PROPERTY)?.takeIf { it.isNotBlank() } ?: return false
        val file =
            try {
                Path.of(dir, mapLibraryFileName(libraryName, resolvePlatform()))
            } catch (_: InvalidPathException) {
                return false
            }
        if (!Files.isRegularFile(file)) return false
        return try {
            System.load(file.toAbsolutePath().toString())
            loadedLibraries += libraryName
            true
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "Failed to load bundled $file, falling back to the JAR", e)
            false
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
            val cacheDir = cacheRoot().resolve(platform.resourceDir).resolve(fingerprint)
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

    /**
     * The extraction root for this process: the first directory the application
     * asked for that proves usable, else the platform default. Fixed at the
     * first call, so every library of a run shares one root.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun cacheRoot(): Path =
        synchronized(lock) {
            resolvedCacheDir?.let { return@synchronized it }

            fun usable(dir: Path): Path? =
                try {
                    Files.createDirectories(dir)
                    // Files.isWritable is advisory on Windows, where an
                    // install-directory ACL can still reject the write. Probe
                    // for real, since this decision is fixed for the process.
                    Files.delete(Files.createTempFile(dir, "nucleus", ".probe"))
                    dir
                } catch (e: Exception) {
                    logger.log(
                        Level.WARNING,
                        "Native library cache directory $dir is unusable, trying the next candidate",
                        e,
                    )
                    null
                }

            // Each candidate is tried in turn: a property naming a read-only
            // directory must not discard the one the application set itself.
            val root =
                requestedCacheDirs(System.getProperty(CACHE_DIR_PROPERTY), configuredCacheDir)
                    .firstNotNullOfOrNull(::usable)
                    ?: defaultCacheDir()
            resolvedCacheDir = root
            root
        }

    /** Test seam: clears [cacheDirectory] and the resolved root, as at startup. */
    internal fun resetCacheDirForTesting() {
        synchronized(lock) {
            configuredCacheDir = null
            resolvedCacheDir = null
        }
    }

    /**
     * The directories an application asked for, most preferred first:
     * [property] ([CACHE_DIR_PROPERTY]) then [override] ([cacheDirectory]).
     * Relative paths are made absolute; a value the platform cannot parse as a
     * path is dropped rather than failing every load.
     */
    @Suppress("SwallowedException")
    internal fun requestedCacheDirs(
        property: String?,
        override: Path?,
    ): List<Path> {
        val fromProperty =
            try {
                property?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            } catch (_: java.nio.file.InvalidPathException) {
                null
            }
        return listOfNotNull(fromProperty, override).map { it.toAbsolutePath().normalize() }.distinct()
    }

    /** The per-user cache location of the current platform, `<base>/nucleus/native`. */
    @Suppress("SwallowedException")
    internal fun defaultCacheDir(
        os: String = System.getProperty("os.name", ""),
        userHome: String = System.getProperty("user.home"),
        env: (String) -> String? = System::getenv,
    ): Path {
        // An empty or relative value would make the cache root the relative
        // `nucleus/native`, i.e. put native libraries under the process working
        // directory. The XDG spec mandates ignoring a relative XDG_CACHE_HOME,
        // and a drive-relative LOCALAPPDATA has the same effect on Windows.
        fun envPath(name: String): Path? =
            try {
                env(name)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                    ?.takeIf { it.isAbsolute }
            } catch (_: java.nio.file.InvalidPathException) {
                null
            }

        val base =
            when {
                os.lowercase().contains("win") ->
                    envPath("LOCALAPPDATA") ?: Path.of(userHome, "AppData", "Local")
                os.lowercase().contains("mac") -> Path.of(userHome, "Library", "Caches")
                else -> envPath("XDG_CACHE_HOME") ?: Path.of(userHome, ".cache")
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

package dev.nucleusframework.lab.probes.system.zstd

import com.squareup.zstd.ZstdCompressor
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.format.hex
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.probes.system.ToolReadBack
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** Port over the zstd-kmp extract-and-load JNI library and what the OS says about it. Blocking. */
interface ZstdGateway {
    fun runtime(): RuntimeFacts

    fun resource(): JarResource

    fun manifest(resource: JarResource): SandboxManifest

    /** Loads the library (first compressor) and reports what appeared in the temp dir. */
    fun load(): LoadObservation

    fun roundTrip(size: Int): RoundTrip

    /** Library files mapped into this process whose name mentions zstd. */
    fun mappedLibraries(): ToolReadBack
}

@ContributesBinding(AppScope::class)
@Inject
class ZstdKmpGateway : ZstdGateway {
    private val pid = ProcessHandle.current().pid()

    override fun runtime(): RuntimeFacts =
        RuntimeFacts(
            ExecutableRuntime.type().name,
            ExecutableRuntime.isSandboxed(),
            ExecutableRuntime.isGraalVmNativeImage,
        )

    override fun resource(): JarResource {
        val path = resourcePath()
        val url = ZstdCompressor::class.java.getResource(path)
        val bytes = url?.openStream()?.use { it.readBytes() }
        return JarResource(path, url?.toString(), bytes?.size?.toLong(), bytes?.let(::sha256))
    }

    override fun manifest(resource: JarResource): SandboxManifest {
        // Same search order as NucleusSandboxLoader: java.library.path, then the app resources dir.
        val dirs =
            System
                .getProperty("java.library.path")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() } +
                listOfNotNull(System.getProperty("compose.application.resources.dir"))
        val manifestFile =
            System.getProperty("nucleus.sandbox.manifest")?.let(::File)?.takeIf { it.isFile }
                ?: dirs.map { File(it, MANIFEST) }.firstOrNull { it.isFile }
        val bundledName =
            manifestFile?.let { file ->
                val properties = Properties().apply { file.inputStream().use { load(it) } }
                resource.sha256?.let { properties.getProperty(it) }
            }
        val bundledPath =
            bundledName?.let { name ->
                dirs.map { File(it, name) }.firstOrNull { it.isFile }?.absolutePath
            }
        return SandboxManifest(manifestFile?.absolutePath, dirs, bundledName, bundledPath)
    }

    override fun load(): LoadObservation {
        val before = tempExtractions()
        val (_, millis) = timedMillis { ZstdCodec.compress(ByteArray(1)) }
        val fresh = (tempExtractions() - before).map { it.absolutePath }
        return LoadObservation(fresh, loadedNow = !loadedOnce.getAndSet(true), millis, Thread.currentThread().name)
    }

    override fun roundTrip(size: Int): RoundTrip {
        val input = ZstdCodec.sample(size)
        return runCatching {
            val (output, millis) =
                timedMillis {
                    val compressed = ZstdCodec.compress(input)
                    compressed to ZstdCodec.decompress(compressed, input.size)
                }
            val (compressed, decompressed) = output
            RoundTrip(
                System.currentTimeMillis(),
                input.size,
                compressed.size,
                millis,
                input.contentEquals(decompressed),
            )
        }.getOrElse {
            RoundTrip(System.currentTimeMillis(), input.size, 0, 0, identical = false, error = it.summary)
        }
    }

    override fun mappedLibraries(): ToolReadBack =
        when (Platform.Current) {
            Platform.Linux -> {
                val lines = runCatching { File("/proc/self/maps").readLines() }.getOrNull()
                val paths =
                    lines
                        ?.mapNotNull { line ->
                            line.substringAfter('/', "").takeIf { "zstd" in it }?.let { "/$it" }
                        }?.distinct()
                ToolReadBack("/proc/self/maps", paths, if (lines == null) "/proc/self/maps not readable" else null)
            }
            Platform.MacOS ->
                ToolReadBack.of("lsof", "-p", pid.toString(), "-Fn") { "zstd" in it && it.startsWith("n") }.let { r ->
                    r.copy(lines = r.lines?.map { it.removePrefix("n") }?.distinct())
                }
            Platform.Windows ->
                ToolReadBack.of(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "(Get-Process -Id $pid).Modules | Where-Object { \$_.ModuleName -like '*zstd*' } | " +
                        "ForEach-Object { \$_.FileName }",
                    timeoutMillis = 15_000,
                )
            else -> ToolReadBack.unsupported("no module list for this OS")
        }

    /** zstd-kmp extracts with `Files.createTempFile("zstd-kmp", null)` into java.io.tmpdir. */
    private fun tempExtractions(): Set<File> =
        File(System.getProperty("java.io.tmpdir"))
            .listFiles { file ->
                file.name.startsWith("zstd-kmp")
            }?.toSet()
            .orEmpty()

    private fun resourcePath(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val file =
            when {
                "windows" in os -> "zstd-kmp.dll"
                "linux" in os -> "libzstd-kmp.so"
                else -> "libzstd-kmp.dylib"
            }
        return "/jni/$arch/$file"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private companion object {
        const val MANIFEST = "nucleus-sandbox-manifest.properties"

        /** Process-wide: the JNI library loads once per JVM whatever the probe instance. */
        val loadedOnce = AtomicBoolean(false)
    }
}

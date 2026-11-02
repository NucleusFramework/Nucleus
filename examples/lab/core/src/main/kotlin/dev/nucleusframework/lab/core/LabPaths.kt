package dev.nucleusframework.lab.core

import dev.nucleusframework.core.runtime.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** Per-user data directory of the Lab (check results, reports, scratch files). */
object LabPaths {
    val dataDir: Path by lazy {
        val home = System.getProperty("user.home")
        val dir =
            when (Platform.Current) {
                Platform.MacOS -> Path(home, "Library", "Application Support", "NucleusLab")
                Platform.Windows -> Path(System.getenv("APPDATA") ?: home, "NucleusLab")
                else -> Path(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "nucleus-lab")
            }
        ensureDirectory(dir)
    }

    /**
     * Creates [dir], first moving aside a plain file that sits where it should be (a botched copy, a
     * restore gone wrong) — kept as `<name>.not-a-dir-<millis>` so nothing is lost — instead of
     * failing every probe with `FileAlreadyExistsException`.
     */
    internal fun ensureDirectory(dir: Path): Path {
        if (dir.exists() && !dir.isDirectory()) {
            val aside = dir.resolveSibling("${dir.fileName}.not-a-dir-${System.currentTimeMillis()}")
            Files.move(dir, aside)
            logger.warning("$dir was a file, not a directory: moved it to $aside")
        }
        return dir.createDirectories()
    }

    private val logger: Logger = Logger.getLogger(LabPaths::class.java.name)

    /** A fresh scratch directory under [dataDir], for probes that need real files. */
    fun scratch(name: String): Path = dataDir.resolve("scratch").resolve(name).createDirectories()

    /** Writes [text] to a file named [name] in the scratch area and returns it (drag sources, clipboard files). */
    fun scratchFile(
        name: String,
        text: String,
    ): Path = scratch("files").resolve(name).also { it.writeText(text) }

    private val extracted = ConcurrentHashMap.newKeySet<Path>()

    /**
     * Copies classpath resource [path] to a file and returns it, for APIs that take a file
     * (tray and media icons, a dylib loaded by path). Rewritten once per process, so a rebuilt
     * resource is never served from a previous run's copy.
     */
    fun extractResource(
        path: String,
        owner: Class<*> = LabPaths::class.java,
    ): Path {
        val target = scratch("resources").resolve(path.trimStart('/').replace('/', '_'))
        if (extracted.add(target)) {
            val stream = checkNotNull(owner.getResourceAsStream(path)) { "missing resource $path" }
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        return target
    }
}

/**
 * An append-only text log under [LabPaths.dataDir] that another process (a launch agent, a
 * scheduled task, a fixture) writes and the Lab tails.
 */
class LabLog(
    name: String,
) {
    val file: Path =
        LabPaths.dataDir
            .resolve("logs")
            .createDirectories()
            .resolve("$name.log")

    fun append(line: String) {
        file.appendText("$line\n")
    }

    fun tail(lines: Int = 50): List<String> = if (file.exists()) file.readLines().takeLast(lines) else emptyList()

    fun clear() {
        file.deleteIfExists()
    }
}

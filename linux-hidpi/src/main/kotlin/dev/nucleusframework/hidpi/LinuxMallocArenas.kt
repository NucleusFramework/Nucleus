package dev.nucleusframework.hidpi

import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

/**
 * System property carrying the arena cap, set by `nucleusOptimization { mallocArenas }`.
 * Keep in sync with the plugin's `NUCLEUS_MALLOC_ARENA_MAX_PROPERTY`.
 */
public const val MALLOC_ARENA_MAX_PROPERTY: String = "nucleus.optimization.mallocArenaMax"

/** Native-image carrier of the same value. Keep in sync with `NUCLEUS_MALLOC_ARENA_MAX_RESOURCE_KEY`. */
private const val MALLOC_ARENA_MAX_RESOURCE_KEY = "optimization.mallocArenaMax"
private const val APP_PROPERTIES = "nucleus/nucleus-app.properties"

private val logger = Logger.getLogger("dev.nucleusframework.hidpi.LinuxMallocArenas")

/**
 * Caps the number of glibc malloc arenas on Linux (`mallopt(M_ARENA_MAX, n)`) when the app
 * opted in with `nucleusOptimization { mallocArenas = true }` (or the `nucleusOptimization`
 * master), which sets [MALLOC_ARENA_MAX_PROPERTY] to 2 and bakes it into
 * `nucleus/nucleus-app.properties` for native images. Off otherwise.
 *
 * glibc creates up to `8 × cores` arenas and gives a thread its own as soon as threads
 * contend; each keeps the memory it once held, so a many-threaded desktop app (JVM, Skia,
 * GPU driver, coroutine and library threads) ends up with an RSS far above what it uses
 * (−900 MB measured with a cap of 2 on a 20-core machine, #757). Applies to JVM and
 * native-image apps alike: every native library allocates through glibc malloc.
 *
 * Call it as early as possible in `main`: threads that already own an arena keep it, and
 * glibc latches its own limit once more than 8 arenas exist, after which the cap is ignored.
 * `nucleusApplication` and `GraalVmInitializer.initialize()` already call it.
 *
 * Skipped when the process already chose a value (`MALLOC_ARENA_MAX`, or
 * `glibc.malloc.arena_max` in `GLIBC_TUNABLES`), for a cap of 0 or less, and off glibc
 * (musl has no arenas). No-op on other platforms.
 */
public fun capLinuxMallocArenas() {
    if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return

    val max =
        resolveMallocArenaMax(
            configured = System.getProperty(MALLOC_ARENA_MAX_PROPERTY) ?: readAppProperty(),
            arenaMaxEnv = System.getenv("MALLOC_ARENA_MAX"),
            tunablesEnv = System.getenv("GLIBC_TUNABLES"),
        ) ?: return

    val result =
        try {
            LinuxStartupBridge.nativeSetMallocArenaMax(max)
        } catch (_: Throwable) {
            // JNI unavailable — leave glibc's default
            return
        }
    when (result) {
        1 -> logger.fine { "glibc malloc arenas capped at $max" }
        0 -> logger.warning { "mallopt(M_ARENA_MAX, $max) was refused by glibc" }
        else -> logger.fine { "Not a glibc process, malloc arenas left alone" }
    }
}

/** The cap to apply, or `null` when it is off or the process already chose one. */
internal fun resolveMallocArenaMax(
    configured: String?,
    arenaMaxEnv: String?,
    tunablesEnv: String?,
): Int? {
    if (configured == null) return null
    if (arenaMaxEnv != null) return null
    if (tunablesEnv?.contains("glibc.malloc.arena_max") == true) return null

    val max = configured.trim().toIntOrNull()
    if (max == null) {
        logger.log(Level.WARNING, "Ignoring invalid $MALLOC_ARENA_MAX_PROPERTY=$configured")
        return null
    }
    return max.takeIf { it > 0 }
}

@Suppress("TooGenericExceptionCaught")
private fun readAppProperty(): String? =
    try {
        LinuxStartupBridge::class.java.classLoader
            ?.getResourceAsStream(APP_PROPERTIES)
            ?.use { Properties().apply { load(it) } }
            ?.getProperty(MALLOC_ARENA_MAX_RESOURCE_KEY)
    } catch (_: Exception) {
        null
    }

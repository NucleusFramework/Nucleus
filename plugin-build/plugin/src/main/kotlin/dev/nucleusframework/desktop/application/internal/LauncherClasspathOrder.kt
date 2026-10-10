package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File

/**
 * Puts the `app.classpath=` entries of jpackage launcher `.cfg` files back in classpath order.
 *
 * jpackage has no classpath option: it lists every file of `--input` (sorted by name) after the
 * main jar. When two JARs define the same classes, the one that sorts first wins at run time,
 * while `./gradlew run` resolves them in Gradle's runtime-classpath order — so a packaged app
 * could load different classes than the one tested. Seen with Jewel: the IntelliJ icon
 * libraries pull `kotlinx-coroutines-core-jvm-1.10.2-intellij-2`, which sorts before
 * `kotlinx-coroutines-core-jvm-1.11.0` and made the packaged app fail with `NoSuchMethodError`.
 */
internal object LauncherClasspathOrder {
    private const val CLASSPATH_PREFIX = "app.classpath="

    /**
     * Rewrites every launcher `.cfg` under [appImageRoot] so its classpath follows [order]
     * (JAR file names, first wins). Entries not in [order] keep their relative place, after it.
     *
     * @return number of `.cfg` files rewritten
     */
    fun apply(
        appImageRoot: File,
        order: List<String>,
        logger: Logger,
    ): Int {
        if (order.isEmpty() || !appImageRoot.exists()) return 0
        var rewritten = 0
        appImageRoot
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("cfg", ignoreCase = true) && it.name != "jvm.cfg" }
            .forEach { cfg ->
                val text = cfg.readText()
                val reordered = reorder(text, order) ?: return@forEach
                cfg.writeText(reordered)
                rewritten++
                logger.info("Restored classpath order in ${cfg.name}")
            }
        return rewritten
    }

    /** [cfgText] with its classpath in [order], or `null` when it already is. */
    internal fun reorder(
        cfgText: String,
        order: List<String>,
    ): String? {
        val lineSeparator = if (cfgText.contains("\r\n")) "\r\n" else "\n"
        val lines = cfgText.split(lineSeparator)
        val slots = lines.indices.filter { lines[it].trimStart().startsWith(CLASSPATH_PREFIX) }
        if (slots.size < 2) return null

        val rank = order.withIndex().associate { (index, name) -> name to index }
        val entries = slots.map { lines[it].trim().removePrefix(CLASSPATH_PREFIX) }
        // sortedBy is stable: unknown entries (rank MAX) keep jpackage's relative order.
        val sorted = entries.sortedBy { rank[fileName(it)] ?: Int.MAX_VALUE }
        if (sorted == entries) return null

        val out = lines.toMutableList()
        slots.forEachIndexed { i, slot -> out[slot] = CLASSPATH_PREFIX + sorted[i] }
        return out.joinToString(lineSeparator)
    }

    private fun fileName(entry: String): String = entry.substringAfterLast('/').substringAfterLast('\\')
}

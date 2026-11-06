package dev.nucleusframework.application

import java.util.concurrent.TimeUnit

/**
 * Toggles GNOME/portal color-scheme for live E2E tests. [isAvailable] is false
 * when the host cannot round-trip a toggle: no `gsettings`, or no XDG Desktop
 * Portal on the session bus (CI runners ship `gsettings` with its in-memory
 * backend, but the dark-mode detector only reads through the portal).
 */
internal object LinuxColorSchemeToggle {
    private const val SCHEMA = "org.gnome.desktop.interface"
    private const val KEY = "color-scheme"

    val isAvailable: Boolean by lazy {
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("linux") &&
            succeeds("gsettings", "get", SCHEMA, KEY) &&
            succeeds(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance",
                KEY,
            )
    }

    private fun succeeds(vararg command: String): Boolean =
        runCatching {
            val p =
                ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)

    fun read(): String {
        val p =
            ProcessBuilder("gsettings", "get", SCHEMA, KEY)
                .redirectErrorStream(true)
                .start()
        check(p.waitFor(3, TimeUnit.SECONDS)) { "gsettings get timed out" }
        check(p.exitValue() == 0) { "gsettings get failed: ${p.inputStream.bufferedReader().readText()}" }
        // gsettings prints e.g. 'prefer-dark'
        return p.inputStream
            .bufferedReader()
            .readText()
            .trim()
            .trim('\'')
    }

    fun write(value: String) {
        val p =
            ProcessBuilder("gsettings", "set", SCHEMA, KEY, value)
                .redirectErrorStream(true)
                .start()
        check(p.waitFor(3, TimeUnit.SECONDS)) { "gsettings set timed out" }
        check(p.exitValue() == 0) {
            "gsettings set $value failed: ${p.inputStream.bufferedReader().readText()}"
        }
    }

    inline fun <T> withScheme(
        scheme: String,
        block: () -> T,
    ): T {
        val previous = read()
        write(scheme)
        try {
            return block()
        } finally {
            write(previous)
        }
    }

    /** Flip between prefer-dark and prefer-light. */
    fun oppositeOf(current: String): String = if (current == "prefer-dark") "prefer-light" else "prefer-dark"

    fun isDarkScheme(scheme: String): Boolean = scheme == "prefer-dark"
}

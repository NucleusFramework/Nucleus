package dev.nucleusframework.application

import java.util.concurrent.TimeUnit

/**
 * Toggles GNOME/portal color-scheme for live E2E tests. [isAvailable] is false
 * without a desktop session (e.g. CI without a session bus).
 */
internal object LinuxColorSchemeToggle {
    private const val SCHEMA = "org.gnome.desktop.interface"
    private const val KEY = "color-scheme"

    /**
     * Whether the scheme can be toggled here *and* the change observed the way the
     * detector observes it: through the XDG desktop portal on the session bus.
     *
     * `gsettings` alone is not enough. Without a session bus it still exits 0 — reads
     * come from the on-disk dconf database or the schema default, and writes are
     * dropped with only a warning — so the toggle would silently do nothing and the
     * test would time out waiting for a change no portal can report.
     */
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
                "--timeout",
                "3",
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
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                false
            } else {
                p.exitValue() == 0
            }
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

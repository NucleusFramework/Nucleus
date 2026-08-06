package dev.nucleusframework.application

import java.util.concurrent.TimeUnit

/**
 * Toggles GNOME/portal color-scheme for live E2E tests. No-op helpers when
 * `gsettings` is unavailable (CI without a session bus).
 */
internal object LinuxColorSchemeToggle {
    private const val SCHEMA = "org.gnome.desktop.interface"
    private const val KEY = "color-scheme"

    val isAvailable: Boolean by lazy {
        System.getProperty("os.name").orEmpty().lowercase().contains("linux") &&
            runCatching {
                ProcessBuilder("gsettings", "get", SCHEMA, KEY)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false).let { started ->
                // waitFor returns true if finished; check exit 0
                started &&
                    runCatching {
                        val p =
                            ProcessBuilder("gsettings", "get", SCHEMA, KEY)
                                .redirectErrorStream(true)
                                .start()
                        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
                    }.getOrDefault(false)
            }
    }

    fun read(): String {
        val p =
            ProcessBuilder("gsettings", "get", SCHEMA, KEY)
                .redirectErrorStream(true)
                .start()
        check(p.waitFor(3, TimeUnit.SECONDS)) { "gsettings get timed out" }
        check(p.exitValue() == 0) { "gsettings get failed: ${p.inputStream.bufferedReader().readText()}" }
        // gsettings prints e.g. 'prefer-dark'
        return p.inputStream.bufferedReader().readText().trim().trim('\'')
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
    fun oppositeOf(current: String): String =
        if (current == "prefer-dark") "prefer-light" else "prefer-dark"

    fun isDarkScheme(scheme: String): Boolean = scheme == "prefer-dark"
}

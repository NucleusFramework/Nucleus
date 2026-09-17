package dev.nucleusframework.core.runtime

import java.util.Locale

public enum class Platform {
    Linux,
    Windows,
    MacOS,
    Unknown,
    ;

    public companion object {
        public val Current: Platform by lazy {
            val os = System.getProperty("os.name", "unknown").lowercase(Locale.ENGLISH)
            when {
                os.contains("mac") || os.contains("darwin") -> MacOS
                os.contains("win") -> Windows
                os.contains("nux") || os.contains("nix") || os.contains("aix") -> Linux
                else -> Unknown
            }
        }

        /** `true` when running on a Wayland session (Linux only). */
        public val isWayland: Boolean by lazy {
            Current == Linux &&
                (System.getenv("XDG_SESSION_TYPE") == "wayland" || System.getenv("WAYLAND_DISPLAY") != null)
        }
    }
}

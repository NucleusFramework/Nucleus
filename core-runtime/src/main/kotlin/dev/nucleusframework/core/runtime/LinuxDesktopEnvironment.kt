package dev.nucleusframework.core.runtime

import java.util.Locale

public enum class LinuxDesktopEnvironment {
    Gnome,
    KDE,
    XFCE,
    Cinnamon,
    Mate,
    Unknown,
    ;

    public companion object {
        public val Current: LinuxDesktopEnvironment by lazy {
            val desktop = System.getenv("XDG_CURRENT_DESKTOP")?.lowercase(Locale.ENGLISH) ?: ""
            val session = System.getenv("DESKTOP_SESSION")?.lowercase(Locale.ENGLISH) ?: ""
            when {
                desktop.contains("gnome") || session.contains("gnome") -> Gnome
                desktop.contains("kde") || session.contains("kde") || session.contains("plasma") -> KDE
                desktop.contains("xfce") || session.contains("xfce") -> XFCE
                desktop.contains("cinnamon") || session.contains("cinnamon") -> Cinnamon
                desktop.contains("mate") || session.contains("mate") -> Mate
                else -> Unknown
            }
        }
    }
}

/**
 * Widget toolkit the Linux session is built on. Distinct from
 * [LinuxDesktopEnvironment]: LXQt and Deepin are not Plasma but they are
 * still Qt shells, while GNOME / XFCE / Cinnamon / MATE are GTK.
 *
 * Used to pick matching chrome such as the context-menu flyout (Adwaita vs
 * Breeze).
 */
public enum class LinuxUiToolkit {
    /** GTK shells: GNOME, XFCE, Cinnamon, MATE, Budgie, Pantheon, … */
    Gtk,

    /** Qt shells: KDE Plasma, LXQt, Deepin, Trinity, … */
    Qt,
    ;

    /** Resolves [Current] from the process environment. */
    public companion object {
        /**
         * Toolkit of the current session, from `XDG_CURRENT_DESKTOP` /
         * `DESKTOP_SESSION` / `XDG_SESSION_DESKTOP`, then `KDE_FULL_SESSION`
         * and `QT_QPA_PLATFORMTHEME`. Defaults to [Gtk] when nothing matches
         * (tiling WMs, unknown DEs).
         */
        public val Current: LinuxUiToolkit by lazy {
            linuxUiToolkit(
                xdgCurrentDesktop = System.getenv("XDG_CURRENT_DESKTOP"),
                desktopSession = System.getenv("DESKTOP_SESSION"),
                xdgSessionDesktop = System.getenv("XDG_SESSION_DESKTOP"),
                kdeFullSession = System.getenv("KDE_FULL_SESSION"),
                qtPlatformTheme = System.getenv("QT_QPA_PLATFORMTHEME"),
            )
        }
    }
}

private val QtDesktopIds =
    setOf(
        "kde",
        "plasma",
        "plasmawayland",
        "lxqt",
        "deepin",
        "dde",
        "trinity",
        "tde",
        "lumina",
        "ukui",
        "cutefish",
        "lingmo",
    )

private val GtkDesktopIds =
    setOf(
        "gnome",
        "ubuntu",
        "unity",
        "xfce",
        "x-cinnamon",
        "cinnamon",
        "mate",
        "budgie",
        "pantheon",
        "lxde",
        "enlightenment",
        "pop",
        "cosmic",
        "phosh",
    )

internal fun linuxUiToolkit(
    xdgCurrentDesktop: String?,
    desktopSession: String?,
    xdgSessionDesktop: String?,
    kdeFullSession: String?,
    qtPlatformTheme: String?,
): LinuxUiToolkit {
    val tokens =
        buildList {
            addDesktopTokens(xdgCurrentDesktop)
            addDesktopTokens(desktopSession)
            addDesktopTokens(xdgSessionDesktop)
        }
    for (token in tokens) {
        if (token.matchesAnyDesktopId(QtDesktopIds)) return LinuxUiToolkit.Qt
        if (token.matchesAnyDesktopId(GtkDesktopIds)) return LinuxUiToolkit.Gtk
    }
    if (kdeFullSession.equals("true", ignoreCase = true)) return LinuxUiToolkit.Qt
    return when (qtPlatformTheme?.trim()?.lowercase(Locale.ENGLISH)) {
        "kde",
        "lxqt",
        "qt5ct",
        "qt6ct",
        "kvantum",
        "plasma",
        -> LinuxUiToolkit.Qt
        else -> LinuxUiToolkit.Gtk
    }
}

private fun MutableList<String>.addDesktopTokens(value: String?) {
    if (value.isNullOrBlank()) return
    value.split(':', ';', ',', ' ').mapNotNullTo(this) { token ->
        token.trim().lowercase(Locale.ENGLISH).ifEmpty { null }
    }
}

private fun String.matchesAnyDesktopId(ids: Set<String>): Boolean =
    ids.any { id ->
        this == id || startsWith("$id-") || startsWith("${id}_")
    }

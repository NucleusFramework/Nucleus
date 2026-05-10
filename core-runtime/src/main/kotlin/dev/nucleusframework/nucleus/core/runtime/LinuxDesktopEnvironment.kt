package dev.nucleusframework.nucleus.core.runtime

import java.util.Locale

enum class LinuxDesktopEnvironment {
    Gnome,
    KDE,
    XFCE,
    Cinnamon,
    Mate,
    Unknown,
    ;

    companion object {
        val Current: LinuxDesktopEnvironment by lazy {
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

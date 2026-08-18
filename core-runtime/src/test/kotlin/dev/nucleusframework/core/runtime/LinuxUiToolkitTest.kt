package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LinuxUiToolkitTest(
    private val desktop: String?,
    private val session: String?,
    private val sessionDesktop: String?,
    private val kdeFullSession: String?,
    private val qtTheme: String?,
    private val expected: LinuxUiToolkit,
) {
    @Test
    fun `classifies gtk vs qt from session hints`() {
        assertEquals(
            expected,
            linuxUiToolkit(
                xdgCurrentDesktop = desktop,
                desktopSession = session,
                xdgSessionDesktop = sessionDesktop,
                kdeFullSession = kdeFullSession,
                qtPlatformTheme = qtTheme,
            ),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1} / {2} / kde={3} qt={4} → {5}")
        fun data(): Collection<Array<Any?>> =
            listOf(
                row("GNOME", null, null, null, null, LinuxUiToolkit.Gtk),
                row("ubuntu:GNOME", null, null, null, null, LinuxUiToolkit.Gtk),
                row("Budgie:GNOME", null, null, null, null, LinuxUiToolkit.Gtk),
                row("XFCE", null, null, null, null, LinuxUiToolkit.Gtk),
                row("X-Cinnamon", null, null, null, null, LinuxUiToolkit.Gtk),
                row("MATE", null, null, null, null, LinuxUiToolkit.Gtk),
                row("Pantheon", null, null, null, null, LinuxUiToolkit.Gtk),
                row("pop:GNOME", null, null, null, null, LinuxUiToolkit.Gtk),
                row("KDE", null, null, null, null, LinuxUiToolkit.Qt),
                row(null, "plasma", null, null, null, LinuxUiToolkit.Qt),
                row(null, "plasmawayland", null, null, null, LinuxUiToolkit.Qt),
                row("LXQt", null, null, null, null, LinuxUiToolkit.Qt),
                row("lxqt:LXQt", null, null, null, null, LinuxUiToolkit.Qt),
                row("Deepin", null, null, null, null, LinuxUiToolkit.Qt),
                row("UKUI", null, null, null, null, LinuxUiToolkit.Qt),
                row(null, null, null, "true", null, LinuxUiToolkit.Qt),
                row(null, null, null, null, "qt6ct", LinuxUiToolkit.Qt),
                row(null, null, null, null, "kde", LinuxUiToolkit.Qt),
                row("Hyprland", null, null, null, "kvantum", LinuxUiToolkit.Qt),
                row("Hyprland", null, null, null, null, LinuxUiToolkit.Gtk),
                row("sway", null, null, null, "gtk3", LinuxUiToolkit.Gtk),
                row(null, null, null, null, null, LinuxUiToolkit.Gtk),
                row("KDE:GNOME", null, null, null, null, LinuxUiToolkit.Qt),
                row("ubuntu", null, null, null, null, LinuxUiToolkit.Gtk),
                row("Unity", null, null, null, null, LinuxUiToolkit.Gtk),
                row("LXDE", null, null, null, null, LinuxUiToolkit.Gtk),
                row("Enlightenment", null, null, null, null, LinuxUiToolkit.Gtk),
                row("cosmic", null, null, null, null, LinuxUiToolkit.Gtk),
                row("phosh", null, null, null, null, LinuxUiToolkit.Gtk),
                row("Trinity", null, null, null, null, LinuxUiToolkit.Qt),
                row("TDE", null, null, null, null, LinuxUiToolkit.Qt),
                row("Lumina", null, null, null, null, LinuxUiToolkit.Qt),
                row("Cutefish", null, null, null, null, LinuxUiToolkit.Qt),
                row("Lingmo", null, null, null, null, LinuxUiToolkit.Qt),
                row("kde-plasma", null, null, null, null, LinuxUiToolkit.Qt),
                row(null, "gnome-xorg", null, null, null, LinuxUiToolkit.Gtk),
                row(null, null, "plasma", null, null, LinuxUiToolkit.Qt),
                row(null, null, null, "TRUE", null, LinuxUiToolkit.Qt),
                row(null, null, null, null, "qt5ct", LinuxUiToolkit.Qt),
                row(null, null, null, null, "plasma", LinuxUiToolkit.Qt),
                row(null, null, null, null, "lxqt", LinuxUiToolkit.Qt),
                row("  ", "  ", "", null, "", LinuxUiToolkit.Gtk),
            )

        private fun row(
            desktop: String?,
            session: String?,
            sessionDesktop: String?,
            kdeFullSession: String?,
            qtTheme: String?,
            expected: LinuxUiToolkit,
        ): Array<Any?> = arrayOf(desktop, session, sessionDesktop, kdeFullSession, qtTheme, expected)
    }
}

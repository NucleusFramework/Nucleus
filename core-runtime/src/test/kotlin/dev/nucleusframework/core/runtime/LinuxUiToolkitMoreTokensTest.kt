package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxUiToolkitMoreTokensTest {
    @Test
    fun `prefix and extra desktop ids classify gtk vs qt`() {
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("cosmic", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("phosh", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("enlightenment", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("lxde", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("cutefish", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("lingmo", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("trinity", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("tde", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("lumina", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit("dde", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("gnome-foo", "plasma-something", null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("gnome-xorg", null, null, null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit(null, null, "lxqt", null, null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit(null, null, null, "TRUE", null),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit(null, null, null, null, "qt5ct"),
        )
        assertEquals(
            LinuxUiToolkit.Qt,
            linuxUiToolkit(null, null, null, null, "plasma"),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("  ", "", null, "false", "gtk3"),
        )
        assertEquals(
            LinuxUiToolkit.Gtk,
            linuxUiToolkit("Hyprland:something", null, null, null, null),
        )
    }
}

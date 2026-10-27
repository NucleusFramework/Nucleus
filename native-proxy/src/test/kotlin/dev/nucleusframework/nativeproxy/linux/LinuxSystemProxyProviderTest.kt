package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.linux.LinuxSystemProxyProvider.DesktopFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxSystemProxyProviderTest {
    private fun family(vararg env: Pair<String, String>): DesktopFamily =
        LinuxSystemProxyProvider.desktopFamily(mapOf(*env)::get)

    @Test
    fun `GNOME-family sessions read GSettings`() {
        assertEquals(DesktopFamily.GNOME, family("XDG_CURRENT_DESKTOP" to "ubuntu:GNOME"))
        assertEquals(DesktopFamily.GNOME, family("XDG_CURRENT_DESKTOP" to "X-Cinnamon"))
        assertEquals(DesktopFamily.GNOME, family("XDG_CURRENT_DESKTOP" to "Budgie:GNOME"))
        assertEquals(DesktopFamily.GNOME, family("DESKTOP_SESSION" to "mate"))
    }

    @Test
    fun `KDE sessions read kioslaverc`() {
        assertEquals(DesktopFamily.KDE, family("XDG_CURRENT_DESKTOP" to "KDE"))
        assertEquals(DesktopFamily.KDE, family("DESKTOP_SESSION" to "plasmawayland", "GDMSESSION" to "plasma"))
    }

    @Test
    fun `other sessions fall back to the environment`() {
        assertEquals(DesktopFamily.OTHER, family())
        assertEquals(DesktopFamily.OTHER, family("XDG_CURRENT_DESKTOP" to "XFCE"))
        assertEquals(DesktopFamily.OTHER, family("XDG_CURRENT_DESKTOP" to "sway"))
        assertEquals(DesktopFamily.OTHER, family("XDG_CURRENT_DESKTOP" to "LXQt"))
    }
}

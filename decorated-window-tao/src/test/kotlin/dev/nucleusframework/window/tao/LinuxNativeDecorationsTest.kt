package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxNativeDecorationsTest {
    @Test
    fun `KDE keeps native decorations when undecorated is false`() {
        if (Platform.Current != Platform.Linux) return
        assertTrue(linuxKeepsNativeWindowDecorations(undecorated = false, desktop = LinuxDesktopEnvironment.KDE))
    }

    @Test
    fun `KDE still allows a borderless overlay`() {
        if (Platform.Current != Platform.Linux) return
        assertFalse(linuxKeepsNativeWindowDecorations(undecorated = true, desktop = LinuxDesktopEnvironment.KDE))
    }

    @Test
    fun `GNOME keeps the hidden-titlebar CSD path`() {
        if (Platform.Current != Platform.Linux) return
        assertFalse(linuxKeepsNativeWindowDecorations(undecorated = false, desktop = LinuxDesktopEnvironment.Gnome))
    }
}

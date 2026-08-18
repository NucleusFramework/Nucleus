package dev.nucleusframework.window.utils.linux

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class LinuxTitleBarIconSetTest {
    @Test
    fun `gnome and kde icon sets resolve in both themes`() =
        runComposeUiTest {
            var gnomeLight: LinuxTitleBarIconSet? = null
            var gnomeDark: LinuxTitleBarIconSet? = null
            var kdeLight: LinuxTitleBarIconSet? = null
            var kdeDark: LinuxTitleBarIconSet? = null
            setContent {
                gnomeLight = linuxTitleBarIcons(LinuxDesktopEnvironment.Gnome, isDark = false)
                gnomeDark = linuxTitleBarIcons(LinuxDesktopEnvironment.Gnome, isDark = true)
                kdeLight = linuxTitleBarIcons(LinuxDesktopEnvironment.KDE, isDark = false)
                kdeDark = linuxTitleBarIcons(LinuxDesktopEnvironment.KDE, isDark = true)
            }
            waitForIdle()
            val gnome = gnomeLight!!
            val gnomeNight = gnomeDark!!
            val kde = kdeLight!!
            val kdeNight = kdeDark!!
            assertNotNull(gnome.close)
            assertNotNull(gnome.closeHover)
            assertNotNull(gnome.closePressed)
            assertNotNull(gnome.closeHoverFocused)
            assertNotNull(gnome.closePressedFocused)
            assertNotNull(gnome.closeInactive)
            assertNotNull(gnome.minimize)
            assertNotNull(gnome.minimizeHover)
            assertNotNull(gnome.minimizePressed)
            assertNotNull(gnome.minimizeInactive)
            assertNotNull(gnome.maximize)
            assertNotNull(gnome.maximizeHover)
            assertNotNull(gnome.maximizePressed)
            assertNotNull(gnome.maximizeInactive)
            assertNotNull(gnome.restore)
            assertNotNull(gnome.restoreHover)
            assertNotNull(gnome.restorePressed)
            assertNotNull(gnome.restoreInactive)
            assertNotNull(kde.closeHoverFocused)
            assertNotNull(kde.closePressedFocused)
            assertNotEquals(gnome.close, kde.close)
            assertNotEquals(gnomeNight.close, gnome.close)
            assertNotEquals(kdeNight.close, kde.close)
        }
}

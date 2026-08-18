package dev.nucleusframework.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.window.internal.isDark
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowChromeKotlinTest {
    @Test
    fun `control buttons direction resolves ltr rtl and auto`() =
        runComposeUiTest {
            var auto: LayoutDirection? = null
            var ltr: LayoutDirection? = null
            var rtl: LayoutDirection? = null
            var system: LayoutDirection? = null
            setContent {
                auto = ControlButtonsDirection.Auto.resolve()
                ltr = ControlButtonsDirection.Ltr.resolve()
                rtl = ControlButtonsDirection.Rtl.resolve()
                system = ControlButtonsDirection.System.resolve()
                ControlButtonsDirection.SystemNative.resolve()
            }
            waitForIdle()
            assertEquals(LayoutDirection.Ltr, ltr)
            assertEquals(LayoutDirection.Rtl, rtl)
            assertEquals(LayoutDirection.Ltr, auto)
            assertTrue(system == LayoutDirection.Ltr || system == LayoutDirection.Rtl)
            assertEquals(nativeSystemLayoutDirection(), system)
        }

    @Test
    fun `fullscreen and large-corner modifiers round-trip on the modifier chain`() {
        val on = Modifier.newFullscreenControls(true).macOSLargeCornerRadius(true)
        val off = Modifier.newFullscreenControls(false).macOSLargeCornerRadius(false)
        assertTrue(on.hasNewFullscreenControls())
        assertTrue(on.hasMacOSLargeCornerRadius())
        assertFalse(off.hasNewFullscreenControls())
        assertFalse(off.hasMacOSLargeCornerRadius())
        assertFalse(Modifier.hasNewFullscreenControls())
        assertFalse(Modifier.hasMacOSLargeCornerRadius())
        val a = NewFullscreenControlsElement(true) {}
        val b = NewFullscreenControlsElement(true) {}
        val c = NewFullscreenControlsElement(false) {}
        assertEquals(a, b)
        assertTrue(a.hashCode() == b.hashCode())
        assertFalse(a == c)
        val d = MacOSLargeCornerRadiusElement(true) {}
        val e = MacOSLargeCornerRadiusElement(false) {}
        assertEquals(d, MacOSLargeCornerRadiusElement(true) {})
        assertFalse(d == e)
    }

    @Test
    fun `kde padding is zero off kde`() {
        val padding = kdePaddingForButtonLayout()
        if (LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) {
            assertTrue(
                padding == PaddingValues(start = 4.dp) || padding == PaddingValues(end = 4.dp),
            )
        } else {
            assertEquals(PaddingValues(0.dp), padding)
        }
    }

    @Test
    fun `window controls side local defaults to unspecified`() =
        runComposeUiTest {
            var side: WindowControlsSide? = null
            setContent {
                side = LocalWindowControlsSide.current
                Box(Modifier)
            }
            waitForIdle()
            assertEquals(WindowControlsSide.Unspecified, side)
            assertEquals(3, WindowControlsSide.entries.size)
        }

    @Test
    fun `createLinuxTitleBarStyle clears icon hover backgrounds`() =
        runComposeUiTest {
            setContent {
                val styled = createLinuxTitleBarStyle(LocalTitleBarStyle.current)
                assertEquals(
                    androidx.compose.ui.graphics.Color.Transparent,
                    styled.colors.iconButtonHoveredBackground,
                )
                assertEquals(
                    androidx.compose.ui.graphics.Color.Transparent,
                    styled.colors.iconButtonPressedBackground,
                )
            }
            waitForIdle()
        }

    @Test
    fun `decorated window state copy and toString`() {
        val state =
            DecoratedWindowState.of(
                fullscreen = true,
                minimized = false,
                maximized = true,
                active = false,
                tiled = true,
                resizable = false,
            )
        assertTrue(state.isFullscreen)
        assertFalse(state.isMinimized)
        assertTrue(state.isMaximized)
        assertFalse(state.isActive)
        assertTrue(state.isTiled)
        assertFalse(state.isResizable)
        val copied = state.copy(minimized = true, active = true)
        assertTrue(copied.isMinimized)
        assertTrue(copied.isActive)
        assertTrue(copied.isFullscreen)
        assertTrue(state.toString().contains("isFullscreen=true"))
        assertEquals(TITLE_BAR_LAYOUT_ID, "__TITLE_BAR_CONTENT__")
        assertEquals(TITLE_BAR_BORDER_LAYOUT_ID, "__TITLE_BAR_BORDER__")
        assertTrue(TITLE_BAR_COMPONENT_LAYOUT_ID_PREFIX.startsWith("__TITLE_BAR_"))
    }

    @Test
    fun `dialog state only tracks the active bit`() {
        val active = DecoratedDialogState.of(active = true)
        val inactive = DecoratedDialogState.of(active = false)
        assertTrue(active.isActive)
        assertFalse(inactive.isActive)
        assertTrue(inactive.copy(active = true).isActive)
        assertFalse(active.toDecoratedWindowState().isFullscreen)
        assertTrue(active.toDecoratedWindowState().isActive)
        assertTrue(active.toString().contains("isActive=true"))
        assertEquals(1UL, DecoratedDialogState.Active)
    }

    @Test
    fun `title bar info mutates title icon and client regions`() {
        val info = TitleBarInfo("first", null)
        assertEquals("first", info.title)
        assertEquals(null, info.icon)
        info.title = "second"
        info.icon = null
        info.clientRegions["drag"] = androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 4f)
        assertEquals("second", info.title)
        assertEquals(10f, info.clientRegions.getValue("drag").width)
        val scope = TitleBarScopeImpl("Hello", null)
        assertEquals("Hello", scope.title)
        assertEquals(null, scope.icon)
    }

    @Test
    fun `default light and dark styles are distinct`() {
        val lightWindow = DecoratedWindowDefaults.lightWindowStyle()
        val darkWindow = DecoratedWindowDefaults.darkWindowStyle()
        val lightBar = DecoratedWindowDefaults.lightTitleBarStyle()
        val darkBar = DecoratedWindowDefaults.darkTitleBarStyle()
        assertTrue(lightWindow.colors.background != darkWindow.colors.background)
        assertTrue(lightBar.colors.content != darkBar.colors.content)
        assertEquals(40.dp, lightBar.metrics.height)
        assertEquals(40.dp, darkBar.metrics.height)
    }

    @Test
    fun `theme provider publishes dark and light locals`() =
        runComposeUiTest {
            var darkSeen: Boolean? = null
            var lightSeen: Boolean? = null
            setContent {
                NucleusDecoratedWindowTheme(isDark = true) {
                    darkSeen = LocalIsDarkTheme.current
                    assertTrue(
                        LocalTitleBarStyle.current.colors.background ==
                            DecoratedWindowDefaults.darkTitleBarStyle().colors.background,
                    )
                }
                NucleusDecoratedWindowTheme(isDark = false) {
                    lightSeen = LocalIsDarkTheme.current
                    assertTrue(
                        LocalTitleBarStyle.current.colors.background ==
                            DecoratedWindowDefaults.lightTitleBarStyle().colors.background,
                    )
                    val border = LocalDecoratedWindowStyle.current.colors.borderFor(DecoratedWindowState.of())
                    val inactive =
                        LocalDecoratedWindowStyle.current.colors.borderFor(
                            DecoratedWindowState.of(active = false),
                        )
                    assertEquals(border.value, LocalDecoratedWindowStyle.current.colors.border)
                    assertEquals(inactive.value, LocalDecoratedWindowStyle.current.colors.borderInactive)
                    val activeBg = LocalTitleBarStyle.current.colors.backgroundFor(DecoratedWindowState.of())
                    val inactiveBg =
                        LocalTitleBarStyle.current.colors.backgroundFor(
                            DecoratedWindowState.of(active = false),
                        )
                    assertEquals(activeBg.value, LocalTitleBarStyle.current.colors.background)
                    assertEquals(inactiveBg.value, LocalTitleBarStyle.current.colors.inactiveBackground)
                }
            }
            waitForIdle()
            assertEquals(true, darkSeen)
            assertEquals(false, lightSeen)
        }

    @Test
    fun `color luminance helper treats black as dark and white as light`() {
        assertTrue(androidx.compose.ui.graphics.Color.Black.isDark())
        assertFalse(androidx.compose.ui.graphics.Color.White.isDark())
        assertTrue(androidx.compose.ui.graphics.Color(0xFF202020).isDark())
        assertFalse(androidx.compose.ui.graphics.Color(0xFFF5F5F5).isDark())
    }
}

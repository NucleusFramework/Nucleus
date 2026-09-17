package dev.nucleusframework.window

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.WindowClearColorLayers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromeLogicTest {
    @Test
    fun `window control type and double-click action keep their entries`() {
        assertEquals(
            listOf(
                WindowControlType.Minimize,
                WindowControlType.Maximize,
                WindowControlType.Restore,
                WindowControlType.Close,
                WindowControlType.ExitFullscreen,
            ),
            WindowControlType.entries,
        )
        assertEquals(
            listOf(WindowDoubleClickAction.ToggleMaximize, WindowDoubleClickAction.None),
            WindowDoubleClickAction.entries,
        )
        assertEquals(0, WindowAppearanceMode.System.nativeValue)
        assertEquals(1, WindowAppearanceMode.Light.nativeValue)
        assertEquals(2, WindowAppearanceMode.Dark.nativeValue)
        assertEquals(TitleBarPlacement.Docked, TitleBarPlacement.Docked)
        val overlay = TitleBarPlacement.Overlay(autoHideInFullscreen = false, passThroughToContent = true)
        assertFalse(overlay.autoHideInFullscreen)
        assertTrue(overlay.passThroughToContent)
    }

    @Test
    fun `resolveWindowControl follows maximize restore fullscreen and close`() {
        val window = TaoWindow(handle = 0L, isResizable = true)
        val idle = DecoratedWindowState.of()
        val maximized = DecoratedWindowState.of(maximized = true)

        val minimize =
            resolveWindowControl(WindowControlSlot.Minimize, window, idle, isFullscreen = false, null)
        assertNotNull(minimize)
        assertEquals(WindowControlType.Minimize, minimize.type)

        val maximize =
            resolveWindowControl(WindowControlSlot.Maximize, window, idle, isFullscreen = false, null)
        assertNotNull(maximize)
        assertEquals(WindowControlType.Maximize, maximize.type)

        val restore =
            resolveWindowControl(WindowControlSlot.Maximize, window, maximized, isFullscreen = false, null)
        assertNotNull(restore)
        assertEquals(WindowControlType.Restore, restore.type)

        var exited = false
        val exitFs =
            resolveWindowControl(
                WindowControlSlot.Maximize,
                window,
                idle,
                isFullscreen = true,
            ) { exited = true }
        assertNotNull(exitFs)
        assertEquals(WindowControlType.ExitFullscreen, exitFs.type)
        exitFs.onClick()
        assertTrue(exited)

        val noFsHandler =
            resolveWindowControl(
                WindowControlSlot.Maximize,
                window,
                idle,
                isFullscreen = true,
                onExitFullscreen = null,
            )
        assertEquals(WindowControlType.Maximize, noFsHandler?.type)

        val close = resolveWindowControl(WindowControlSlot.Close, window, idle, isFullscreen = false, null)
        assertNotNull(close)
        assertEquals(WindowControlType.Close, close.type)
        var closed = false
        window.onCloseRequested { closed = true }
        close.onClick()
        assertTrue(closed)
    }

    @Test
    fun `resolveWindowControl hides maximize when the window is not resizable`() {
        val fixed = TaoWindow(handle = 0L, isResizable = false)
        val idle = DecoratedWindowState.of(resizable = false)
        assertNull(
            resolveWindowControl(WindowControlSlot.Maximize, fixed, idle, isFullscreen = false, null),
        )
        val stillFullscreen =
            resolveWindowControl(
                WindowControlSlot.Maximize,
                fixed,
                idle,
                isFullscreen = true,
            ) { }
        assertEquals(WindowControlType.ExitFullscreen, stillFullscreen?.type)
    }

    @Test
    fun `resolveWindowControl hides minimize when the window is not minimizable`() {
        val idle = DecoratedWindowState.of(resizable = true)
        val pinned = TaoWindow(handle = 0L, isMinimizable = false)
        assertNull(
            resolveWindowControl(WindowControlSlot.Minimize, pinned, idle, isFullscreen = false, null),
        )
        val regular = TaoWindow(handle = 0L)
        assertEquals(
            WindowControlType.Minimize,
            resolveWindowControl(WindowControlSlot.Minimize, regular, idle, isFullscreen = false, null)?.type,
        )
    }

    @Test
    fun `resolveWindowControl hides maximize when the window is resizable but not maximizable`() {
        val idle = DecoratedWindowState.of(resizable = true)
        val palette = TaoWindow(handle = 0L, isResizable = true, isMaximizable = false)
        assertNull(
            resolveWindowControl(WindowControlSlot.Maximize, palette, idle, isFullscreen = false, null),
        )
        val stillFullscreen =
            resolveWindowControl(
                WindowControlSlot.Maximize,
                palette,
                idle,
                isFullscreen = true,
            ) { }
        assertEquals(WindowControlType.ExitFullscreen, stillFullscreen?.type)
        val regular = TaoWindow(handle = 0L)
        assertEquals(
            WindowControlType.Maximize,
            resolveWindowControl(WindowControlSlot.Maximize, regular, idle, isFullscreen = false, null)?.type,
        )
        // A window the WM maximized anyway must still offer Restore.
        val maximized = DecoratedWindowState.of(resizable = true).copy(maximized = true)
        assertEquals(
            WindowControlType.Restore,
            resolveWindowControl(WindowControlSlot.Maximize, palette, maximized, isFullscreen = false, null)?.type,
        )
    }

    @Test
    fun `titleBarPadding matches the host platform chrome contract`() {
        val regular = titleBarPadding(40.dp, isFullscreen = false, controlIsRtl = false, linuxControlsOnRight = true)
        val fullscreen = titleBarPadding(40.dp, isFullscreen = true, controlIsRtl = true, linuxControlsOnRight = false)
        val noLinuxSide =
            titleBarPadding(
                28.dp,
                isFullscreen = false,
                controlIsRtl = false,
                linuxControlsOnRight = null,
            )
        when (Platform.Current) {
            Platform.Linux -> {
                assertEquals(0.dp, regular.calculateLeftPadding(LayoutDirection.Ltr))
                assertEquals(0.dp, regular.calculateRightPadding(LayoutDirection.Ltr))
                assertEquals(0.dp, fullscreen.calculateLeftPadding(LayoutDirection.Ltr))
                assertEquals(0.dp, noLinuxSide.calculateLeftPadding(LayoutDirection.Ltr))
            }
            Platform.MacOS -> {
                assertTrue(regular.calculateLeftPadding(LayoutDirection.Ltr) > 0.dp)
                assertEquals(0.dp, regular.calculateRightPadding(LayoutDirection.Ltr))
                assertTrue(fullscreen.calculateRightPadding(LayoutDirection.Ltr) >= 80.dp)
            }
            else -> {
                assertEquals(0.dp, regular.calculateLeftPadding(LayoutDirection.Ltr))
                assertEquals(0.dp, fullscreen.calculateRightPadding(LayoutDirection.Ltr))
            }
        }
    }

    @Test
    fun `clear color layers resolve style then content and coerce opaque when transparent`() {
        val host = mutableStateOf(0xFFFFFFFF.toInt())
        val layers = WindowClearColorLayers(host, fullyTransparent = false)
        layers.setStyle(0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), layers.resolved)
        assertEquals(0xFF112233.toInt(), host.value)

        val titleBar = Any()
        val background = Any()
        layers.setContent(background, Color.Red.toArgb())
        layers.setContent(titleBar, Color.Blue.toArgb())
        assertEquals(Color.Blue.toArgb(), layers.resolved)

        layers.clearContent(titleBar)
        assertEquals(Color.Red.toArgb(), layers.resolved)
        layers.clearContent(background)
        assertEquals(0xFF112233.toInt(), layers.resolved)

        val transparentHost = mutableStateOf(0)
        val transparent = WindowClearColorLayers(transparentHost, fullyTransparent = true)
        transparent.setStyle(0xFFFFFFFF.toInt())
        assertEquals(0, transparent.resolved)
        transparent.setContent(Any(), 0x80AABBCC.toInt())
        assertEquals(0x80AABBCC.toInt(), transparent.resolved)
    }
}

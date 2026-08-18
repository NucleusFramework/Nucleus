package dev.nucleusframework.launcher.windows

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.Platform
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsLauncherNativeTest {
    private var frame: Frame? = null

    @AfterTest
    fun tearDown() {
        frame?.let { window ->
            if (WindowsThumbnailToolbar.isAvailable) {
                WindowsThumbnailToolbar.unregister(window)
            }
            if (WindowsOverlayIcon.isAvailable) {
                WindowsOverlayIcon.clearIcon(window)
            }
            window.dispose()
        }
        if (WindowsJumpListManager.isAvailable) {
            WindowsJumpListManager.clearJumpList()
        }
        WindowsBadgeManager.uninitialize()
    }

    @Test
    fun `jump list setProcessAppId and setJumpList succeed unpackaged`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(WindowsJumpListManager.isAvailable, "nucleus_launcher_windows must load on Windows")
        assertTrue(
            WindowsJumpListManager.setProcessAppId("dev.nucleusframework.kover"),
            "setProcessAppId: ${WindowsJumpListManager.lastError}",
        )
        assertTrue(
            WindowsJumpListManager.setJumpList(
                categories =
                    listOf(
                        JumpListCategory(
                            "Kover",
                            listOf(
                                JumpListItem(
                                    title = "Open",
                                    arguments = "--kover-open",
                                    icon = TaskbarIconSource.FromStock(StockIcon.FOLDER),
                                ),
                            ),
                        ),
                    ),
                tasks =
                    listOf(
                        JumpListItem(title = "New", arguments = "--kover-new"),
                        JumpListItem.SEPARATOR,
                    ),
                knownCategories = listOf(KnownCategory.RECENT),
            ),
            "setJumpList: ${WindowsJumpListManager.lastError}",
        )
        assertTrue(
            WindowsJumpListManager.clearJumpList(),
            "clearJumpList: ${WindowsJumpListManager.lastError}",
        )
    }

    @Test
    fun `badge initialize is documented as APPX-only when unpackaged`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(WindowsBadgeManager.isAvailable)
        if (ExecutableRuntime.isAppX()) {
            assertTrue(WindowsBadgeManager.initialize("dev.nucleusframework.kover"))
            assertTrue(WindowsBadgeManager.setCount(3), WindowsBadgeManager.lastError)
            assertTrue(WindowsBadgeManager.setGlyph(BadgeGlyph.ALERT), WindowsBadgeManager.lastError)
            assertTrue(WindowsBadgeManager.clear(), WindowsBadgeManager.lastError)
        } else {
            assertFalse(WindowsBadgeManager.initialize("dev.nucleusframework.kover"))
            val error = WindowsBadgeManager.lastError
            assertTrue(
                error != null && (error.contains("APPX") || error.contains("MSIX")),
                "unpackaged initialize must report the APPX/MSIX requirement, got: $error",
            )
            assertFalse(WindowsBadgeManager.setCount(1))
            assertFalse(WindowsBadgeManager.setGlyph(BadgeGlyph.NONE))
        }
    }

    @Test
    fun `overlay icon and thumbnail toolbar drive a real HWND`() {
        if (Platform.Current != Platform.Windows) return
        if (GraphicsEnvironment.isHeadless()) return
        assertTrue(WindowsOverlayIcon.isAvailable)
        assertTrue(WindowsThumbnailToolbar.isAvailable)

        val window = Frame("kover-launcher").also { frame = it }
        window.setSize(80, 60)
        window.isUndecorated = true
        window.isVisible = true
        val hwnd = WindowsWindowHandle.of(window)
        assertTrue(hwnd != 0L, "visible AWT Frame must have an HWND")

        assertTrue(
            WindowsOverlayIcon.setIcon(hwnd, TaskbarIconSource.FromStock(StockIcon.INFO), "kover"),
            "setIcon: ${WindowsOverlayIcon.lastError}",
        )
        assertTrue(WindowsOverlayIcon.clearIcon(hwnd), "clearIcon: ${WindowsOverlayIcon.lastError}")

        val buttons = listOf(ThumbnailToolbarButton(0, "Play", TaskbarIconSource.FromStock(StockIcon.APPLICATION)))
        assertTrue(
            WindowsThumbnailToolbar.setButtons(hwnd, buttons) { },
            "setButtons: ${WindowsThumbnailToolbar.lastError}",
        )
        assertTrue(
            WindowsThumbnailToolbar.updateButtons(hwnd, buttons),
            "updateButtons: ${WindowsThumbnailToolbar.lastError}",
        )
        assertTrue(
            WindowsThumbnailToolbar.unregister(hwnd),
            "unregister: ${WindowsThumbnailToolbar.lastError}",
        )
    }
}

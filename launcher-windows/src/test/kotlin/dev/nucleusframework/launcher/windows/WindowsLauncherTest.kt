package dev.nucleusframework.launcher.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsLauncherTest {
    @Test
    fun `badge glyphs and known categories keep their schema values`() {
        assertEquals("none", BadgeGlyph.NONE.value)
        assertEquals("newMessage", BadgeGlyph.NEW_MESSAGE.value)
        assertEquals("playing", BadgeGlyph.PLAYING.value)
        assertEquals(BadgeGlyph.entries.size, 13)
        assertEquals(1, KnownCategory.FREQUENT.value)
        assertEquals(2, KnownCategory.RECENT.value)
    }

    @Test
    fun `jump list models include separators and categories`() {
        val item =
            JumpListItem(
                title = "Project",
                arguments = "--open p",
                description = "Open project",
                icon = TaskbarIconSource.FromStock(StockIcon.FOLDER),
            )
        val category = JumpListCategory("Recent Projects", listOf(item, JumpListItem.SEPARATOR))
        assertEquals("Recent Projects", category.name)
        assertEquals(2, category.items.size)
        assertTrue(JumpListItem.SEPARATOR.isSeparator)
        assertEquals("", JumpListItem.SEPARATOR.title)
        assertEquals(StockIcon.FOLDER, (item.icon as TaskbarIconSource.FromStock).stockIcon)
        assertEquals(3, StockIcon.FOLDER.id)
        assertEquals(78, StockIcon.WARNING.id)
        assertEquals(0, StockIcon.DOCUMENT_NO_ASSOCIATION.id)
    }

    @Test
    fun `taskbar icon sources expose native encoding`() {
        val stock = TaskbarIconSource.FromStock(StockIcon.ERROR)
        val file = TaskbarIconSource.FromFile("C:\\\\icons\\\\app.ico")
        val resource = TaskbarIconSource.FromResource("C:\\\\Windows\\\\System32\\\\shell32.dll", 12)
        assertEquals(0, stock.nativeType())
        assertEquals("", stock.nativePath())
        assertEquals(StockIcon.ERROR.id, stock.nativeIndex())
        assertEquals(1, file.nativeType())
        assertEquals("C:\\\\icons\\\\app.ico", file.nativePath())
        assertEquals(0, file.nativeIndex())
        assertEquals(2, resource.nativeType())
        assertEquals("C:\\\\Windows\\\\System32\\\\shell32.dll", resource.nativePath())
        assertEquals(12, resource.nativeIndex())
    }

    @Test
    fun `thumbnail buttons validate id tooltip and pack flags`() {
        val enabled = ThumbnailToolbarButton(id = 0, tooltip = "Play")
        assertEquals(0, enabled.toNativeFlags())

        val disabled =
            ThumbnailToolbarButton(
                id = 6,
                tooltip = "x".repeat(259),
                enabled = false,
                hidden = true,
                noBackground = true,
                dismissOnClick = true,
                nonInteractive = true,
            )
        assertEquals(0x01 or 0x02 or 0x04 or 0x08 or 0x10, disabled.toNativeFlags())
        assertEquals(7, ThumbnailToolbarButton.MAX_BUTTONS)

        assertFailsWith<IllegalArgumentException> { ThumbnailToolbarButton(id = 7) }
        assertFailsWith<IllegalArgumentException> { ThumbnailToolbarButton(id = -1) }
        assertFailsWith<IllegalArgumentException> { ThumbnailToolbarButton(id = 0, tooltip = "x".repeat(260)) }
    }

    @Test
    fun `jump list manager is a documented no-op without native code`() {
        if (WindowsJumpListManager.isAvailable) return
        assertFalse(WindowsJumpListManager.isAvailable)
        assertFalse(WindowsJumpListManager.setProcessAppId("com.example.App"))
        assertEquals("Native library not available", WindowsJumpListManager.lastError)

        val ok =
            WindowsJumpListManager.setJumpList(
                categories =
                    listOf(
                        JumpListCategory(
                            "Projects",
                            listOf(
                                JumpListItem("A", "--a", icon = TaskbarIconSource.FromStock(StockIcon.FOLDER)),
                                JumpListItem.SEPARATOR,
                            ),
                        ),
                        JumpListCategory("Empty", emptyList()),
                    ),
                tasks =
                    listOf(
                        JumpListItem("New", "--new", icon = TaskbarIconSource.FromFile("x.ico")),
                        JumpListItem.SEPARATOR,
                        JumpListItem("Res", "--r", icon = TaskbarIconSource.FromResource("shell32.dll", 1)),
                    ),
                knownCategories = listOf(KnownCategory.RECENT, KnownCategory.FREQUENT),
            )
        assertFalse(ok)
        assertFalse(WindowsJumpListManager.clearJumpList())
    }

    @Test
    fun `badge manager is a documented no-op without native code`() {
        if (WindowsBadgeManager.isAvailable) return
        assertFalse(WindowsBadgeManager.isAvailable)
        assertFalse(WindowsBadgeManager.initialize("com.example.App"))
        assertEquals("Native library not available", WindowsBadgeManager.lastError)
        assertFalse(WindowsBadgeManager.setCount(3))
        assertEquals("Not available on this platform", WindowsBadgeManager.lastError)
        assertFalse(WindowsBadgeManager.setGlyph(BadgeGlyph.ALERT))
        assertFalse(WindowsBadgeManager.clear())
        WindowsBadgeManager.uninitialize()
    }

    @Test
    fun `overlay icon and thumbnail toolbar report missing native library`() {
        if (WindowsOverlayIcon.isAvailable || WindowsThumbnailToolbar.isAvailable) return
        assertFalse(WindowsOverlayIcon.isAvailable)
        assertFalse(WindowsOverlayIcon.setIcon(1L, TaskbarIconSource.FromStock(StockIcon.INFO), "info"))
        assertEquals("Native library not available", WindowsOverlayIcon.lastError)
        assertFalse(WindowsOverlayIcon.clearIcon(1L))

        assertFalse(WindowsThumbnailToolbar.isAvailable)
        val buttons = listOf(ThumbnailToolbarButton(0, "Play", TaskbarIconSource.FromStock(StockIcon.APPLICATION)))
        assertFalse(WindowsThumbnailToolbar.setButtons(1L, buttons) { })
        assertEquals("Native library not available", WindowsThumbnailToolbar.lastError)
        assertFalse(WindowsThumbnailToolbar.updateButtons(1L, buttons))
        assertFalse(WindowsThumbnailToolbar.unregister(1L))
    }

    @Test
    fun `badge initialize without aumid and remaining glyphs stay no-ops`() {
        if (WindowsBadgeManager.isAvailable) return
        assertFalse(WindowsBadgeManager.initialize(null))
        assertEquals("Native library not available", WindowsBadgeManager.lastError)
        assertFalse(WindowsBadgeManager.setCount(0))
        assertFalse(WindowsBadgeManager.setGlyph(BadgeGlyph.NONE))
        BadgeGlyph.entries.forEach { glyph ->
            assertTrue(glyph.value.isNotEmpty() || glyph == BadgeGlyph.NONE)
        }
        WindowsBadgeManager.uninitialize()
        assertFalse(WindowsJumpListManager.setProcessAppId(null))
        assertFalse(WindowsOverlayIcon.setIcon(0L, TaskbarIconSource.FromStock(StockIcon.ERROR)))
        assertFalse(WindowsOverlayIcon.clearIcon(0L))
        assertFalse(WindowsThumbnailToolbar.setButtons(0L, emptyList()))
        assertFalse(WindowsThumbnailToolbar.updateButtons(0L, emptyList()))
    }

    @Test
    fun `jump list items default to empty optional fields`() {
        val item = JumpListItem("Bare")
        assertEquals("", item.arguments)
        assertEquals("", item.description)
        assertNull(item.icon)
        assertFalse(item.isSeparator)
        assertEquals(BadgeGlyph.ACTIVITY.value, "activity")
        assertEquals(BadgeGlyph.AVAILABLE.value, "available")
        assertEquals(BadgeGlyph.AWAY.value, "away")
        assertEquals(BadgeGlyph.BUSY.value, "busy")
        assertEquals(BadgeGlyph.ERROR.value, "error")
        assertEquals(BadgeGlyph.NEW_MESSAGE.value, "newMessage")
        assertEquals(BadgeGlyph.PAUSED.value, "paused")
        assertEquals(BadgeGlyph.UNAVAILABLE.value, "unavailable")
        assertEquals(BadgeGlyph.ALARM.value, "alarm")
        assertEquals(BadgeGlyph.ATTENTION.value, "attention")
    }
}

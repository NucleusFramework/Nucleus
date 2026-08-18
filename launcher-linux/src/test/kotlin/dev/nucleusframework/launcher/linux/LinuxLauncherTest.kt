package dev.nucleusframework.launcher.linux

import dev.nucleusframework.freedesktop.icons.FreedesktopIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxLauncherTest {
    @Test
    fun `app uri prefixes the desktop file id`() {
        assertEquals("application://firefox.desktop", LinuxLauncherEntry.appUri("firefox.desktop"))
        assertEquals("application://", LinuxLauncherEntry.appUri(""))
    }

    @Test
    fun `launcher properties keep optional fields`() {
        val empty = LauncherProperties()
        assertNull(empty.count)
        assertNull(empty.progress)
        assertNull(empty.urgent)
        assertNull(empty.quicklist)

        val props =
            LauncherProperties(
                count = 7L,
                countVisible = true,
                progress = 0.4,
                progressVisible = true,
                urgent = true,
                quicklist = "/com/example/Menu",
                updating = false,
            )
        assertEquals(7L, props.count)
        assertEquals(0.4, props.progress)
        assertEquals(true, props.urgent)
        assertEquals("/com/example/Menu", props.quicklist)
        assertEquals(false, props.updating)
    }

    @Test
    fun `dbusmenu item types separators and children`() {
        val child =
            DbusmenuItem(
                id = 3,
                label = "_Open",
                icon = FreedesktopIcon.Custom("document-open"),
                toggleType = DbusmenuItem.ToggleType.CHECKBOX,
                toggleState = 1,
                shortcut = listOf("Control", "O"),
                disposition = DbusmenuItem.Disposition.INFORMATIONAL,
            )
        val root =
            DbusmenuItem(
                id = 1,
                label = "File",
                children = listOf(child, DbusmenuItem.separator(2)),
            )
        val separator = DbusmenuItem.separator(2)
        assertEquals(DbusmenuItem.ItemType.SEPARATOR, separator.type)
        assertEquals("separator", separator.type.value)
        assertEquals("standard", DbusmenuItem.ItemType.STANDARD.value)
        assertEquals("", DbusmenuItem.ToggleType.NONE.value)
        assertEquals("checkmark", DbusmenuItem.ToggleType.CHECKBOX.value)
        assertEquals("radio", DbusmenuItem.ToggleType.RADIO.value)
        assertEquals("normal", DbusmenuItem.Disposition.NORMAL.value)
        assertEquals("warning", DbusmenuItem.Disposition.WARNING.value)
        assertEquals("alert", DbusmenuItem.Disposition.ALERT.value)
        assertEquals(1, root.children.first().toggleState)
        assertEquals(listOf("Control", "O"), child.shortcut)
        assertEquals("document-open", child.icon?.value)
    }

    @Test
    fun `launcher entry methods are false when native is missing`() {
        assertFalse(LinuxLauncherEntry.isAvailable)
        val uri = LinuxLauncherEntry.appUri("myapp.desktop")
        assertFalse(LinuxLauncherEntry.update(uri, LauncherProperties(count = 1L, countVisible = true)))
        assertFalse(LinuxLauncherEntry.update(uri, LauncherProperties(progress = 0.2, progressVisible = false)))
        assertFalse(LinuxLauncherEntry.update(uri, LauncherProperties(urgent = true, updating = true, quicklist = "")))
        assertFalse(LinuxLauncherEntry.setCount(uri, 3))
        assertFalse(LinuxLauncherEntry.setCount(uri, 3, visible = false))
        assertFalse(LinuxLauncherEntry.clearCount(uri))
        assertFalse(LinuxLauncherEntry.setProgress(uri, 0.5))
        assertFalse(LinuxLauncherEntry.setProgress(uri, 1.0, visible = false))
        assertFalse(LinuxLauncherEntry.clearProgress(uri))
        assertFalse(LinuxLauncherEntry.setUrgent(uri, true))
        assertFalse(LinuxLauncherEntry.setUrgent(uri, false))
        assertFalse(LinuxLauncherEntry.setUpdating(uri, true))
        assertFalse(LinuxLauncherEntry.registerQueryHandler(uri))
        LinuxLauncherEntry.unregister()
    }

    @Test
    fun `quicklist setMenu and dispose are no-ops without native code`() {
        val quicklist = LinuxQuicklist("/com/example/MyApp/Menu")
        assertEquals("/com/example/MyApp/Menu", quicklist.objectPath)
        var clicked = -1
        quicklist.listener = LinuxQuicklist.Listener { clicked = it }
        val items =
            listOf(
                DbusmenuItem(id = 1, label = "Open", children = listOf(DbusmenuItem(id = 4, label = "Nested"))),
                DbusmenuItem.separator(id = 2),
                DbusmenuItem(
                    id = 3,
                    label = "Quit",
                    enabled = false,
                    visible = false,
                    toggleType = DbusmenuItem.ToggleType.RADIO,
                    disposition = DbusmenuItem.Disposition.ALERT,
                ),
            )
        assertFalse(quicklist.setMenu(items))
        quicklist.dispose()
        LinuxQuicklist.onItemEvent("/missing", 1)
        assertEquals(-1, clicked)
        NativeLinuxLauncherBridge.onMenuItemEvent("/missing", 9)
        assertEquals(-1, clicked)
    }
}

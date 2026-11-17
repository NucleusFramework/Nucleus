package dev.nucleusframework.tray.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Black-box view of the Windows notification area from the test JVM: the hidden tray windows,
 * their Shell icons, the popup menu the shell would show, and the process GDI/USER handle counts.
 * Test-only (JNA is never a runtime dependency of the tray module).
 */
internal object Win32Probe {
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface User32 : StdCallLibrary {
        fun EnumWindows(
            cb: WndEnumProc,
            data: Pointer?,
        ): Boolean

        fun GetClassNameW(
            hwnd: HWND,
            buf: CharArray,
            max: Int,
        ): Int

        fun GetWindowThreadProcessId(
            hwnd: HWND,
            pid: IntByReference,
        ): Int

        fun IsWindow(hwnd: HWND?): Boolean

        fun IsWindowVisible(hwnd: HWND): Boolean

        fun PostMessageW(
            hwnd: HWND,
            msg: Int,
            w: WPARAM,
            l: LPARAM,
        ): Boolean

        fun SendMessageTimeoutW(
            hwnd: HWND,
            msg: Int,
            w: WPARAM,
            l: LPARAM,
            flags: Int,
            timeoutMs: Int,
            result: Pointer?,
        ): Long

        fun RegisterWindowMessageW(name: String): Int

        fun GetMenuItemCount(menu: Pointer): Int

        fun GetMenuItemID(
            menu: Pointer,
            pos: Int,
        ): Int

        fun GetMenuState(
            menu: Pointer,
            id: Int,
            flags: Int,
        ): Int

        fun GetMenuStringW(
            menu: Pointer,
            item: Int,
            buf: CharArray,
            max: Int,
            flags: Int,
        ): Int

        fun GetSubMenu(
            menu: Pointer,
            pos: Int,
        ): Pointer?

        fun IsMenu(menu: Pointer?): Boolean

        fun GetGuiResources(
            process: HANDLE,
            flags: Int,
        ): Int
    }

    private interface Shell32 : StdCallLibrary {
        fun Shell_NotifyIconGetRect(
            id: NotifyIconIdentifier,
            rect: RECT,
        ): Int
    }

    internal fun interface WndEnumProc : StdCallLibrary.StdCallCallback {
        fun callback(
            hwnd: HWND,
            data: Pointer?,
        ): Boolean
    }

    @Structure.FieldOrder("cbSize", "hWnd", "uID", "guidItem")
    internal class NotifyIconIdentifier : Structure() {
        @JvmField var cbSize: Int = 0

        @JvmField var hWnd: HWND? = null

        @JvmField var uID: Int = 0

        @JvmField var guidItem: ByteArray = ByteArray(16)

        init {
            cbSize = size()
        }
    }

    private val user32: User32 = Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    private val shell32: Shell32 = Native.load("shell32", Shell32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    private val pid: Int = Kernel32.INSTANCE.GetCurrentProcessId()

    const val WM_USER: Int = 0x0400

    // Must match WM_TRAY_CALLBACK in tray_windows.c.
    const val WM_TRAY_CALLBACK: Int = 0x8000 + 1
    const val WM_COMMAND: Int = 0x0111
    const val WM_CANCELMODE: Int = 0x001F
    const val WM_KEYDOWN: Int = 0x0100
    const val WM_LBUTTONUP: Int = 0x0202
    const val WM_RBUTTONUP: Int = 0x0205
    private const val MN_GETHMENU = 0x01E1
    private const val SMTO_ABORTIFHUNG = 0x0002
    private const val MF_BYPOSITION = 0x400
    const val MF_DISABLED: Int = 0x2
    const val MF_GRAYED: Int = 0x1
    const val MF_CHECKED: Int = 0x8
    const val MF_SEPARATOR: Int = 0x800
    const val MF_POPUP: Int = 0x10

    fun hwnd(raw: Long): HWND = HWND(Pointer(raw))

    /** Top-level windows of this process whose class is [className]. */
    fun windowsOfClass(className: String): List<HWND> {
        val found = mutableListOf<HWND>()
        user32.EnumWindows(
            { hwnd, _ ->
                val owner = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value == pid && className(hwnd) == className) found += hwnd
                true
            },
            null,
        )
        return found
    }

    fun className(hwnd: HWND): String {
        val buf = CharArray(256)
        val n = user32.GetClassNameW(hwnd, buf, buf.size)
        return String(buf, 0, n.coerceAtLeast(0))
    }

    fun isWindow(raw: Long): Boolean = user32.IsWindow(hwnd(raw))

    /** True when the shell currently shows an icon for ([hwnd], [uid]). */
    fun shellHasIcon(
        hwnd: Long,
        uid: Int,
    ): Boolean {
        val id = NotifyIconIdentifier()
        id.hWnd = hwnd(hwnd)
        id.uID = uid
        id.write()
        return shell32.Shell_NotifyIconGetRect(id, RECT()) == 0
    }

    fun post(
        hwnd: Long,
        msg: Int,
        w: Long = 0,
        l: Long = 0,
    ): Boolean = user32.PostMessageW(hwnd(hwnd), msg, WPARAM(w), LPARAM(l))

    /** SendMessage with a timeout; returns false when the target thread did not answer in time. */
    fun sendWithTimeout(
        hwnd: Long,
        msg: Int,
        w: Long = 0,
        l: Long = 0,
        timeoutMs: Int,
    ): Boolean {
        val result = Memory(8)
        return user32.SendMessageTimeoutW(
            hwnd(hwnd),
            msg,
            WPARAM(w),
            LPARAM(l),
            SMTO_ABORTIFHUNG,
            timeoutMs,
            result,
        ) != 0L
    }

    fun registerMessage(name: String): Int = user32.RegisterWindowMessageW(name)

    /** The open popup menu (`#32768`) of this process, with its HMENU, or null. */
    fun openPopupMenu(): Pair<HWND, Pointer>? {
        for (w in windowsOfClass("#32768")) {
            if (!user32.IsWindowVisible(w)) continue
            val result = Memory(8)
            val ok =
                user32.SendMessageTimeoutW(w, MN_GETHMENU, WPARAM(0), LPARAM(0), SMTO_ABORTIFHUNG, 1000, result)
            val hmenu = result.getPointer(0)
            if (ok != 0L && hmenu != null && Pointer.nativeValue(hmenu) != 0L) return w to hmenu
        }
        return null
    }

    /** A menu item as the user would see it. */
    data class Item(
        val text: String?,
        val id: Int,
        val separator: Boolean,
        val disabled: Boolean,
        val checked: Boolean,
        val children: List<Item>,
    )

    fun readMenu(menu: Pointer): List<Item> {
        val count = user32.GetMenuItemCount(menu)
        return (0 until count).map { pos ->
            val sub = user32.GetSubMenu(menu, pos)
            // For a submenu entry the high byte holds the child count, not flags.
            val raw = user32.GetMenuState(menu, pos, MF_BYPOSITION)
            val state = if (sub != null) raw and 0xFF else raw
            val buf = CharArray(1024)
            val n = user32.GetMenuStringW(menu, pos, buf, buf.size, MF_BYPOSITION)
            Item(
                text = if (state and MF_SEPARATOR != 0) null else String(buf, 0, n.coerceAtLeast(0)),
                id = user32.GetMenuItemID(menu, pos),
                separator = state and MF_SEPARATOR != 0,
                disabled = state and (MF_DISABLED or MF_GRAYED) != 0,
                checked = state and MF_CHECKED != 0,
                children = if (sub != null) readMenu(sub) else emptyList(),
            )
        }
    }

    /** GDI objects (flags = 0) and USER objects (flags = 1) owned by this process. */
    fun guiResources(): Pair<Int, Int> {
        val self = Kernel32.INSTANCE.GetCurrentProcess()
        return user32.GetGuiResources(self, 0) to user32.GetGuiResources(self, 1)
    }
}

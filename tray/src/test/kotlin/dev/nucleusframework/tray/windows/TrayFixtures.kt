package dev.nucleusframework.tray.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties

internal object TrayFixtures {
    /** A valid multi-frame ICO, rendered once per test JVM. */
    val icoPath: String by lazy {
        ComposableIconUtils.renderComposableToIcoFile(IconRenderProperties.forCurrentOperatingSystem()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2E7D32)))
        }
    }

    /** Window class of the hidden per-tray message window. */
    fun windowClass(): String = System.getProperty("nucleus.tray.test.windowClass") ?: "NucleusTrayWindow"

    @Suppress("FunctionName")
    private interface Shell32 : StdCallLibrary {
        fun Shell_NotifyIconW(
            message: Int,
            data: Pointer,
        ): Boolean
    }

    private val shell32: Shell32 = Native.load("shell32", Shell32::class.java, W32APIOptions.DEFAULT_OPTIONS)

    /** What a crashing explorer.exe does to our icon: it is simply gone. */
    fun deleteShellIcon(
        hwnd: Long,
        uid: Int,
    ) {
        check(Native.POINTER_SIZE == 8) { "x64 layout only" }
        // NOTIFYICONDATAW (x64): cbSize@0, hWnd@8, uID@16; NIM_DELETE only reads these.
        val size = 976
        val nid = Memory(size.toLong()).apply { clear() }
        nid.setInt(0, size)
        nid.setLong(8, hwnd)
        nid.setInt(16, uid)
        shell32.Shell_NotifyIconW(2, nid)
    }
}

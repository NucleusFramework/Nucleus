package dev.nucleusframework.nucleus.launcher.macos

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import javax.swing.SwingUtilities

private const val LIBRARY_NAME = "nucleus_launcher_macos"

internal object NativeMacOsDockMenuBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacOsDockMenuBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeSetDockMenu(
        ids: IntArray,
        titles: Array<String>,
        enabled: BooleanArray,
        parentIndices: IntArray,
        separators: BooleanArray,
    )

    @JvmStatic
    external fun nativeClearDockMenu()

    @JvmStatic
    fun onMenuItemClicked(itemId: Int) {
        val listener = MacOsDockMenu.listener ?: return
        SwingUtilities.invokeLater { listener.onItemClicked(itemId) }
    }
}

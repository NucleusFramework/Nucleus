package dev.nucleusframework.launcher.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.NucleusUiThread

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
        NucleusUiThread.post { listener.onItemClicked(itemId) }
    }
}

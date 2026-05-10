package dev.nucleusframework.nucleus.launcher.windows

/**
 * Source for a Windows taskbar icon (overlay icons, thumbnail toolbar buttons, jump list items).
 */
sealed class TaskbarIconSource {
    /** Use a Windows Shell stock icon (available on all Windows Vista+ systems). */
    data class FromStock(
        val stockIcon: StockIcon,
    ) : TaskbarIconSource()

    /** Load an icon from an `.ico` file on disk. */
    data class FromFile(
        val path: String,
    ) : TaskbarIconSource()

    /**
     * Extract an icon from a resource DLL (e.g., `shell32.dll`, `imageres.dll`).
     *
     * @param dllPath Absolute path to the DLL.
     * @param index   Zero-based icon index within the DLL.
     */
    data class FromResource(
        val dllPath: String,
        val index: Int,
    ) : TaskbarIconSource()
}

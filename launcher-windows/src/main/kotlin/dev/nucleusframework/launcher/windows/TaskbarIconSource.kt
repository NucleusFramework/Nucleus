package dev.nucleusframework.launcher.windows

/**
 * Source for a Windows taskbar icon (overlay icons, thumbnail toolbar buttons, jump list items).
 */
public sealed class TaskbarIconSource {
    /** Use a Windows Shell stock icon (available on all Windows Vista+ systems). */
    public data class FromStock(
        val stockIcon: StockIcon,
    ) : TaskbarIconSource()

    /** Load an icon from an `.ico` file on disk. */
    public data class FromFile(
        val path: String,
    ) : TaskbarIconSource()

    /**
     * Extract an icon from a resource DLL (e.g., `shell32.dll`, `imageres.dll`).
     *
     * @param dllPath Absolute path to the DLL.
     * @param index   Zero-based icon index within the DLL.
     */
    public data class FromResource(
        val dllPath: String,
        val index: Int,
    ) : TaskbarIconSource()
}

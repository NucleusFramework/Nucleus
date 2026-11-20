package dev.nucleusframework.launcher.macos

/**
 * macOS dock context menu integration via JNI.
 *
 * Intercepts `applicationDockMenu:` on the existing `NSApplicationDelegate`
 * via method swizzling. Items appear when the user right-clicks (or
 * Ctrl-clicks) the application's dock icon.
 *
 * All methods are no-op on non-macOS platforms (check [isAvailable]).
 */
public object MacOsDockMenu {
    /** Whether the native library is loaded and the module is functional. */
    public val isAvailable: Boolean
        get() = NativeMacOsDockMenuBridge.isLoaded

    /**
     * Listener for dock menu item clicks.
     *
     * Callbacks are dispatched on the host's UI thread (the Tao main thread
     * under Nucleus, the AWT EDT in a plain Swing / Compose Desktop host).
     */
    public var listener: DockMenuListener? = null

    /**
     * Sets the dock context menu items.
     *
     * On first call, installs a method swizzle on the existing
     * `NSApplicationDelegate` to intercept `applicationDockMenu:`.
     *
     * Item clicks are reported via [listener] on the host's UI thread.
     *
     * @param items The menu items to display. Supports hierarchical menus via [DockMenuItem.children].
     */
    public fun setDockMenu(items: List<DockMenuItem>) {
        if (!isAvailable) return

        val flatIds = mutableListOf<Int>()
        val flatTitles = mutableListOf<String>()
        val flatEnabled = mutableListOf<Boolean>()
        val flatParentIndices = mutableListOf<Int>()
        val flatSeparators = mutableListOf<Boolean>()

        fun flatten(
            items: List<DockMenuItem>,
            parentIndex: Int,
        ) {
            for (item in items) {
                val currentIndex = flatIds.size
                flatIds.add(item.id)
                flatTitles.add(item.title)
                flatEnabled.add(item.enabled)
                flatParentIndices.add(parentIndex)
                flatSeparators.add(item.title == "-")
                if (item.children.isNotEmpty()) {
                    flatten(item.children, currentIndex)
                }
            }
        }

        flatten(items, -1)

        NativeMacOsDockMenuBridge.nativeSetDockMenu(
            ids = flatIds.toIntArray(),
            titles = flatTitles.toTypedArray(),
            enabled = flatEnabled.toBooleanArray(),
            parentIndices = flatParentIndices.toIntArray(),
            separators = flatSeparators.toBooleanArray(),
        )
    }

    /** Removes the dock context menu. */
    public fun clearDockMenu() {
        if (!isAvailable) return
        NativeMacOsDockMenuBridge.nativeClearDockMenu()
    }
}

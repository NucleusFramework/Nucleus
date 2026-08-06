package dev.nucleusframework.launcher.linux

import javax.swing.SwingUtilities

/**
 * Dynamic quicklist server implementing `com.canonical.dbusmenu` over D-Bus.
 *
 * Registers a D-Bus object that exposes a menu layout. Desktop environments
 * (Unity, GNOME, KDE, Plank) query this object to show a right-click context
 * menu on the launcher icon.
 *
 * Usage:
 * ```kotlin
 * val quicklist = LinuxQuicklist("/com/example/MyApp/Menu")
 * quicklist.listener = LinuxQuicklist.Listener { id -> println("Clicked: $id") }
 * quicklist.setMenu(listOf(
 *     DbusmenuItem(id = 1, label = "Open"),
 *     DbusmenuItem.separator(id = 2),
 *     DbusmenuItem(id = 3, label = "Quit"),
 * ))
 *
 * // Set quicklist on the launcher entry
 * LinuxLauncherEntry.update(appUri, LauncherProperties(quicklist = quicklist.objectPath))
 *
 * // On shutdown
 * LinuxLauncherEntry.update(appUri, LauncherProperties(quicklist = ""))
 * quicklist.dispose()
 * ```
 *
 * @param objectPath The D-Bus object path for this menu server
 *   (e.g. `"/com/example/MyApp/Menu"`).
 */
public class LinuxQuicklist(
    public val objectPath: String,
) {
    public fun interface Listener {
        public fun onItemClicked(itemId: Int)
    }

    @Volatile
    public var listener: Listener? = null

    private var registered = false

    /**
     * Sets the full menu layout. Replaces any previous layout.
     *
     * @param items The top-level menu items. Each item may have [DbusmenuItem.children]
     *   for sub-menus.
     * @return `true` if the layout was set and the D-Bus object registered successfully.
     */
    public fun setMenu(items: List<DbusmenuItem>): Boolean {
        if (!NativeLinuxLauncherBridge.isLoaded) return false

        // Flatten the tree into parallel arrays for JNI
        val ids = mutableListOf<Int>()
        val parentIds = mutableListOf<Int>()
        val labels = mutableListOf<String>()
        val iconNames = mutableListOf<String>()
        val types = mutableListOf<String>()
        val enabledFlags = mutableListOf<Boolean>()
        val visibleFlags = mutableListOf<Boolean>()
        val toggleTypes = mutableListOf<String>()
        val toggleStates = mutableListOf<Int>()
        val dispositions = mutableListOf<String>()

        fun flatten(
            item: DbusmenuItem,
            parentId: Int,
        ) {
            ids.add(item.id)
            parentIds.add(parentId)
            labels.add(item.label)
            iconNames.add(item.icon?.value ?: "")
            types.add(item.type.value)
            enabledFlags.add(item.enabled)
            visibleFlags.add(item.visible)
            toggleTypes.add(item.toggleType.value)
            toggleStates.add(item.toggleState)
            dispositions.add(item.disposition.value)
            for (child in item.children) {
                flatten(child, item.id)
            }
        }

        for (item in items) {
            flatten(item, 0) // 0 = root parent
        }

        Companion.register(objectPath, this)

        val ok =
            NativeLinuxLauncherBridge.nativeSetMenu(
                objectPath,
                ids.toIntArray(),
                parentIds.toIntArray(),
                labels.toTypedArray(),
                iconNames.toTypedArray(),
                types.toTypedArray(),
                enabledFlags.toBooleanArray(),
                visibleFlags.toBooleanArray(),
                toggleTypes.toTypedArray(),
                toggleStates.toIntArray(),
                dispositions.toTypedArray(),
            )

        if (ok) registered = true
        return ok
    }

    /**
     * Unregisters the D-Bus menu object and releases native resources.
     */
    public fun dispose() {
        if (!registered) return
        if (!NativeLinuxLauncherBridge.isLoaded) return
        NativeLinuxLauncherBridge.nativeDestroyMenu(objectPath)
        Companion.unregister(objectPath)
        registered = false
    }

    internal companion object {
        /** Global registry of active quicklists, keyed by object path. */
        private val registry = mutableMapOf<String, LinuxQuicklist>()

        internal fun register(
            path: String,
            quicklist: LinuxQuicklist,
        ) {
            registry[path] = quicklist
        }

        internal fun unregister(path: String) {
            registry.remove(path)
        }

        /** Called from native code when a menu item is clicked. */
        @JvmStatic
        fun onItemEvent(
            objectPath: String,
            itemId: Int,
        ) {
            val quicklist = registry[objectPath] ?: return
            SwingUtilities.invokeLater {
                quicklist.listener?.onItemClicked(itemId)
            }
        }
    }
}

package dev.nucleusframework.tray

import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.menu.TrayMenuBuilder

// The backends' diagnostic hooks, for tray-app's tests: they stay internal to the tray module.

/** Linux backend hooks. */
public object LinuxTrayHooks {
    /** Ids of the live trays. */
    public fun debugIds(): List<String> = LinuxTrayInitializer.debugIds()
}

/** Windows backend hooks. */
public object WindowsTrayHooks {
    /** Hidden window and Shell icon id of every live tray, by id. */
    public fun nativeIdentities(): Map<String, Pair<Long, Int>> = WindowsTrayInitializer.nativeIdentities()
}

/** macOS backend hooks. */
public object MacTrayHooks {
    /** Creates tray [id], or updates it. */
    public fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
    ) {
        MacTrayInitializer.initialize(id, iconPath, tooltip, menuContent = menuContent)
    }

    /** Removes tray [id]. */
    public fun dispose(id: String) {
        MacTrayInitializer.dispose(id)
    }

    /** Ids of the live trays. */
    public fun debugIds(): List<String> = MacTrayInitializer.debugIds()

    /** Status items still alive. */
    public fun debugStatusItemCount(): Int = MacTrayInitializer.debugStatusItemCount()

    /** Queues a real mouse click on tray [id]. */
    public fun debugClick(
        id: String,
        secondary: Boolean,
    ) {
        MacTrayInitializer.debugClick(id, secondary)
    }

    /** Queues the selection of the menu item at [path] of tray [id]. */
    public fun debugPerformItem(
        id: String,
        path: IntArray,
    ) {
        MacTrayInitializer.debugPerformItem(id, path)
    }

    /** Queues the dismissal of tray [id]'s open menu. */
    public fun debugCancelMenu(id: String) {
        MacTrayInitializer.debugCancelMenu(id)
    }
}

package dev.nucleusframework.nucleus.launcher.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_launcher_linux"

internal object NativeLinuxLauncherBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxLauncherBridge::class.java)
    val isLoaded: Boolean get() = loaded

    // ---- LauncherEntry native methods ------------------------------------

    @JvmStatic
    external fun nativeUpdate(
        appUri: String,
        hasCount: Boolean,
        count: Long,
        countVisible: Int,
        hasProgress: Boolean,
        progress: Double,
        progressVisible: Int,
        urgent: Int,
        quicklist: String?,
        updating: Int,
    ): Boolean

    @JvmStatic
    external fun nativeRegisterQueryHandler(appUri: String): Boolean

    @JvmStatic
    external fun nativeSetState(
        hasCount: Boolean,
        count: Long,
        countVisible: Int,
        hasProgress: Boolean,
        progress: Double,
        progressVisible: Int,
        urgent: Int,
        quicklist: String?,
        updating: Int,
    )

    @JvmStatic
    external fun nativeUnregister()

    // ---- Dbusmenu native methods -----------------------------------------

    /**
     * Registers a `com.canonical.dbusmenu` object at [objectPath] with the given menu items.
     *
     * Items are passed as flattened parallel arrays. Parent-child relationships are
     * encoded via [parentIds] (0 = child of root).
     */
    @JvmStatic
    external fun nativeSetMenu(
        objectPath: String,
        ids: IntArray,
        parentIds: IntArray,
        labels: Array<String>,
        iconNames: Array<String>,
        types: Array<String>,
        enabled: BooleanArray,
        visible: BooleanArray,
        toggleTypes: Array<String>,
        toggleStates: IntArray,
        dispositions: Array<String>,
    ): Boolean

    /**
     * Unregisters and destroys the dbusmenu server at the given object path.
     */
    @JvmStatic
    external fun nativeDestroyMenu(objectPath: String)

    // ---- Callbacks from native (menu item events) ------------------------

    @JvmStatic
    fun onMenuItemEvent(
        objectPath: String,
        itemId: Int,
    ) {
        LinuxQuicklist.onItemEvent(objectPath, itemId)
    }
}

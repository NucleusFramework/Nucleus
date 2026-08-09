package dev.nucleusframework.launcher.windows

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp
import java.util.logging.Logger

/**
 * Kotlin API for Windows Jump Lists (ICustomDestinationList).
 *
 * Jump lists appear when the user right-clicks the app's taskbar button.
 * They can contain:
 * - **Custom categories**: named groups of items (e.g., "Recent Projects")
 * - **Known categories**: shell-managed Recent or Frequent lists
 * - **User tasks**: pinned actions at the bottom (e.g., "New Window", "Open Settings")
 *
 * When a jump list item is clicked, Windows relaunches the application with the
 * item's [JumpListItem.arguments]. Use `SingleInstanceManager` to forward
 * arguments to the running instance and `DeepLinkHandler` to process them.
 *
 * Works for both APPX/MSIX packaged and unpackaged (NSIS, MSI, distributable) apps.
 * **Note:** Jump list clicks require a real app executable — they do not work
 * in Gradle `run` dev mode (where the process is `java.exe`).
 *
 * Thread-safe singleton.
 */
public object WindowsJumpListManager {
    private val logger = Logger.getLogger(WindowsJumpListManager::class.java.name)

    /** The last error message from a native operation, or null if the last operation succeeded. */
    public var lastError: String? = null
        private set

    /** Whether the native library is loaded and functional on this platform. */
    public val isAvailable: Boolean get() = NativeWindowsJumpListBridge.isLoaded

    /**
     * Set the process AppUserModelID. **Must be called before any window is created**
     * (i.e., at the very beginning of `main()`) for jump lists to work in unpackaged apps.
     *
     * APPX/MSIX apps do not need this — Windows uses the package identity automatically.
     *
     * @param aumid Explicit AUMID, or `null` to use [NucleusApp.appId].
     * @return true if the AUMID was set successfully.
     */
    public fun setProcessAppId(aumid: String? = null): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        if (ExecutableRuntime.isAppX()) return true
        val resolved = aumid ?: NucleusApp.aumid
        val error = NativeWindowsJumpListBridge.nativeSetProcessAppId(resolved)
        lastError = error
        if (error != null) {
            logger.warning("setProcessAppId failed: $error")
        }
        return error == null
    }

    /**
     * Set the entire jump list contents atomically.
     *
     * Replaces any existing jump list. The order of categories in the list
     * determines their display order in the menu.
     *
     * @param categories Custom named categories with items.
     * @param tasks Pinned actions at the bottom of the jump list. Supports [JumpListItem.SEPARATOR].
     * @param knownCategories Shell-managed categories (Recent/Frequent).
     * @return true if the jump list was set successfully.
     */
    public fun setJumpList(
        categories: List<JumpListCategory> = emptyList(),
        tasks: List<JumpListItem> = emptyList(),
        knownCategories: List<KnownCategory> = emptyList(),
    ): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }

        val isAppx = ExecutableRuntime.isAppX()
        val aumid = resolveAumid(isAppx)

        var error = NativeWindowsJumpListBridge.nativeBeginList(aumid, isAppx)
        if (error != null) {
            lastError = error
            logger.warning("BeginList failed: $error")
            return false
        }

        for (category in categories) {
            val items = category.items.filter { !it.isSeparator }
            if (items.isEmpty()) continue
            error =
                NativeWindowsJumpListBridge.nativeAppendCategory(
                    category.name,
                    items.map { it.title }.toTypedArray(),
                    items.map { it.arguments }.toTypedArray(),
                    items.map { it.description }.toTypedArray(),
                    items.map { it.icon.nativeType() }.toIntArray(),
                    items.map { it.icon.nativePath() }.toTypedArray(),
                    items.map { it.icon.nativeIndex() }.toIntArray(),
                )
            if (error != null) {
                lastError = error
                logger.warning("AppendCategory '${category.name}' failed: $error")
                return false
            }
        }

        for (kc in knownCategories) {
            error = NativeWindowsJumpListBridge.nativeAppendKnownCategory(kc.value)
            if (error != null) {
                lastError = error
                logger.warning("AppendKnownCategory ${kc.name} failed: $error")
                return false
            }
        }

        if (tasks.isNotEmpty()) {
            error =
                NativeWindowsJumpListBridge.nativeAddUserTasks(
                    tasks.map { it.title }.toTypedArray(),
                    tasks.map { it.arguments }.toTypedArray(),
                    tasks.map { it.description }.toTypedArray(),
                    tasks.map { it.icon.nativeType() }.toIntArray(),
                    tasks.map { it.icon.nativePath() }.toTypedArray(),
                    tasks.map { it.icon.nativeIndex() }.toIntArray(),
                    tasks.map { it.isSeparator }.toBooleanArray(),
                )
            if (error != null) {
                lastError = error
                logger.warning("AddUserTasks failed: $error")
                return false
            }
        }

        error = NativeWindowsJumpListBridge.nativeCommitList()
        lastError = error
        if (error != null) {
            logger.warning("CommitList failed: $error")
        }
        return error == null
    }

    /**
     * Remove all jump list entries for this application.
     *
     * @return true if the jump list was cleared successfully.
     */
    public fun clearJumpList(): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        val isAppx = ExecutableRuntime.isAppX()
        val aumid = resolveAumid(isAppx)
        val error = NativeWindowsJumpListBridge.nativeDeleteList(aumid, isAppx)
        lastError = error
        if (error != null) {
            logger.warning("DeleteList failed: $error")
        }
        return error == null
    }

    private fun resolveAumid(isAppx: Boolean): String {
        if (isAppx) return ""
        return NucleusApp.aumid
    }

    // -1 = no icon (use app icon), 0 = stock, 1 = file, 2 = resource
    private fun TaskbarIconSource?.nativeType(): Int =
        when (this) {
            is TaskbarIconSource.FromStock -> 0
            is TaskbarIconSource.FromFile -> 1
            is TaskbarIconSource.FromResource -> 2
            null -> -1
        }

    private fun TaskbarIconSource?.nativePath(): String =
        when (this) {
            is TaskbarIconSource.FromStock -> ""
            is TaskbarIconSource.FromFile -> path
            is TaskbarIconSource.FromResource -> dllPath
            null -> ""
        }

    private fun TaskbarIconSource?.nativeIndex(): Int =
        when (this) {
            is TaskbarIconSource.FromStock -> stockIcon.ordinal
            is TaskbarIconSource.FromFile -> 0
            is TaskbarIconSource.FromResource -> index
            null -> 0
        }
}

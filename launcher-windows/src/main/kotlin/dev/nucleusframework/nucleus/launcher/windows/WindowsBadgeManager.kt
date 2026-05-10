package dev.nucleusframework.nucleus.launcher.windows

import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.core.runtime.NucleusApp
import java.util.logging.Logger

/**
 * Kotlin API for Windows Badge Notifications (WinRT).
 *
 * Badges display a numeric count (1-99, 99+ for higher) or a status glyph icon
 * on the app's taskbar button and Start tile.
 *
 * Call [initialize] once before setting any badge. The AUMID is resolved
 * automatically based on the packaging type:
 * - **APPX/MSIX**: empty AUMID - Windows uses the package identity.
 * - **Other (EXE, MSI, dev, etc.)**: uses [NucleusApp.appId] or an explicit
 *   AUMID override.
 *
 * Thread-safe singleton.
 */
object WindowsBadgeManager {
    private val logger = Logger.getLogger(WindowsBadgeManager::class.java.simpleName)
    private var initialized = false

    /** The last error message from a native operation, or null if the last operation succeeded. */
    var lastError: String? = null
        private set

    /** Whether the native library is loaded and functional on this platform. */
    val isAvailable: Boolean get() = NativeWindowsBadgeBridge.isLoaded

    /**
     * Initialize the badge subsystem.
     *
     * @param aumid Explicit AUMID override, or `null` to auto-resolve.
     * @return true if initialization succeeded.
     */
    fun initialize(aumid: String? = null): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            logger.warning(lastError)
            return false
        }
        val isAppx = ExecutableRuntime.isAppX()
        if (!isAppx) {
            val msg =
                "Badge notifications only work in APPX/MSIX packaged apps. " +
                    "Use the packageAppx task to test badges."
            lastError = msg
            logger.warning(msg)
            return false
        }
        val resolvedAumid = resolveAumid(aumid, isAppx)
        logger.fine("Initializing badge with AUMID: '${resolvedAumid.ifEmpty { "<package-identity>" }}' isAppx=$isAppx")
        val error = NativeWindowsBadgeBridge.nativeInitialize(resolvedAumid, isAppx)
        initialized = error == null
        lastError = error
        if (!initialized) {
            logger.warning("Failed to initialize Windows badge subsystem: $error")
        }
        return initialized
    }

    /**
     * Set a numeric badge.
     *
     * Values 1-99 display as the number. Values >= 100 display as "99+".
     * A value of 0 clears the badge.
     *
     * @param count The badge count (0 to clear).
     * @return true if the badge was set successfully.
     */
    fun setCount(count: Int): Boolean {
        if (!ensureReady()) return false
        val error = NativeWindowsBadgeBridge.nativeSetBadgeNumber(count)
        lastError = error
        if (error != null) logger.warning("setCount($count) failed: $error")
        return error == null
    }

    /**
     * Set a glyph badge.
     *
     * Displays a predefined icon on the app's taskbar button and Start tile.
     * Use [BadgeGlyph.NONE] to clear the badge.
     *
     * @param glyph The badge glyph to display.
     * @return true if the badge was set successfully.
     */
    fun setGlyph(glyph: BadgeGlyph): Boolean {
        if (!ensureReady()) return false
        val error = NativeWindowsBadgeBridge.nativeSetBadgeGlyph(glyph.value)
        lastError = error
        if (error != null) logger.warning("setGlyph(${glyph.value}) failed: $error")
        return error == null
    }

    /**
     * Clear the badge from the app's taskbar button and Start tile.
     *
     * @return true if the badge was cleared successfully.
     */
    fun clear(): Boolean {
        if (!ensureReady()) return false
        val error = NativeWindowsBadgeBridge.nativeClearBadge()
        lastError = error
        if (error != null) logger.warning("clear() failed: $error")
        return error == null
    }

    /**
     * Clean up native resources.
     * Call on app shutdown or when badges are no longer needed.
     */
    fun uninitialize() {
        if (!isAvailable || !initialized) return
        NativeWindowsBadgeBridge.nativeUninitialize()
        initialized = false
    }

    private fun resolveAumid(
        explicit: String?,
        isAppx: Boolean,
    ): String {
        if (explicit != null) return explicit
        if (isAppx) return ""
        return NucleusApp.aumid
    }

    private fun ensureReady(): Boolean {
        if (!isAvailable) {
            lastError = "Not available on this platform"
            return false
        }
        if (!initialized) {
            lastError = "Not initialized - call WindowsBadgeManager.initialize() first"
            return false
        }
        return true
    }
}

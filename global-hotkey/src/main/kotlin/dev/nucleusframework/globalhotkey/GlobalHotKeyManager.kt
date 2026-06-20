package dev.nucleusframework.globalhotkey

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.globalhotkey.linux.NativeLinuxHotKeyBridge
import dev.nucleusframework.globalhotkey.macos.NativeMacOsHotKeyBridge
import dev.nucleusframework.globalhotkey.windows.NativeWindowsHotKeyBridge
import java.util.logging.Logger

private const val WIN32_MOD_NOREPEAT = 0x4000

/**
 * Cross-platform manager for system-wide global hotkeys.
 *
 * Global hotkeys are keyboard shortcuts that fire even when the application
 * does not have focus. This is useful for media players, screenshot tools,
 * accessibility shortcuts, etc.
 *
 * Currently implemented:
 * - **Windows**: Win32 `RegisterHotKey` / `UnregisterHotKey` via JNI.
 * - **macOS**: Carbon `RegisterEventHotKey` / `UnregisterEventHotKey` via JNI.
 * - **Linux (X11)**: X11 `XGrabKey` / `XUngrabKey` via JNI.
 * - **Linux (Wayland)**: `org.freedesktop.portal.GlobalShortcuts` D-Bus portal via JNI.
 *   Requires a reverse-DNS `.desktop` file and the app launched from it.
 *
 * Thread-safe singleton.
 *
 * Usage:
 * ```kotlin
 * GlobalHotKeyManager.initialize()
 * val id = GlobalHotKeyManager.register(
 *     keyCode = java.awt.event.KeyEvent.VK_F12,
 *     modifiers = HotKeyModifier.CONTROL + HotKeyModifier.ALT,
 * ) { keyCode, modifiers ->
 *     println("Hotkey pressed!")
 * }
 * // later:
 * GlobalHotKeyManager.unregister(id)
 * GlobalHotKeyManager.shutdown()
 * ```
 */
@Suppress("TooManyFunctions")
object GlobalHotKeyManager {
    private val logger = Logger.getLogger(GlobalHotKeyManager::class.java.simpleName)

    @Volatile
    private var initialized = false

    /** The last error from a native operation, or null if the last operation succeeded. */
    var lastError: String? = null
        private set

    /** Whether the native library is loaded and functional on this platform. */
    val isAvailable: Boolean
        get() =
            when (Platform.Current) {
                Platform.Windows -> NativeWindowsHotKeyBridge.isLoaded
                Platform.MacOS -> NativeMacOsHotKeyBridge.isLoaded
                Platform.Linux -> NativeLinuxHotKeyBridge.isLoaded
                else -> false
            }

    /**
     * Initialize the global hotkey subsystem.
     * Starts the native message loop thread (Windows).
     *
     * @return true if initialization succeeded.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return true
        if (!isAvailable) {
            lastError = "Global hotkeys not available on this platform"
            logger.warning(lastError)
            return false
        }

        val error =
            when (Platform.Current) {
                Platform.Windows -> NativeWindowsHotKeyBridge.nativeInit()
                Platform.MacOS -> NativeMacOsHotKeyBridge.nativeInit()
                Platform.Linux -> NativeLinuxHotKeyBridge.nativeInit()
                else -> "Unsupported platform"
            }

        initialized = error == null
        lastError = error
        if (!initialized) {
            logger.warning("Failed to initialize global hotkey subsystem: $error")
        }
        return initialized
    }

    /**
     * Register a global hotkey.
     *
     * @param keyCode AWT virtual key code (e.g., [java.awt.event.KeyEvent.VK_F12]).
     * @param modifiers bitmask of [HotKeyModifier] values (e.g., `HotKeyModifier.CONTROL + HotKeyModifier.ALT`).
     *                  Use 0 for no modifiers.
     * @param description user-readable description of what the shortcut does (e.g. "Play/Pause").
     *                    Shown in the system shortcut dialog on Linux/Wayland (portal backend); ignored
     *                    on other platforms. When null, the key combination is used as a fallback.
     * @param listener callback invoked when the hotkey is pressed.
     * @return a registration handle for [unregister], or -1 on failure.
     */
    fun register(
        keyCode: Int,
        modifiers: Int = 0,
        description: String? = null,
        listener: HotKeyListener,
    ): Long {
        if (!ensureReady()) return -1

        return when (Platform.Current) {
            Platform.Windows -> registerWindows(keyCode, modifiers, listener)
            Platform.MacOS -> registerMacOs(keyCode, modifiers, listener)
            Platform.Linux -> registerLinux(keyCode, modifiers, description, listener)
            else -> -1
        }
    }

    /**
     * Register a media key as a global hotkey.
     *
     * @param mediaKey the media key to register.
     * @param listener callback invoked when the key is pressed.
     * @return a registration handle for [unregister], or -1 on failure.
     */
    fun register(
        mediaKey: MediaKey,
        listener: HotKeyListener,
    ): Long {
        if (!ensureReady()) return -1

        return when (Platform.Current) {
            Platform.Windows -> registerWindows(mediaKey.nativeCode, 0, listener)
            Platform.MacOS -> {
                lastError = "Media keys are not supported on macOS"
                logger.warning(lastError)
                -1
            }
            Platform.Linux -> registerLinux(mediaKey.nativeCode, 0, mediaKey.name, listener)
            else -> -1
        }
    }

    /**
     * Unregister a previously registered global hotkey.
     *
     * @param handle the registration handle returned by [register].
     * @return true if the hotkey was successfully unregistered.
     */
    fun unregister(handle: Long): Boolean {
        if (!ensureReady()) return false

        return when (Platform.Current) {
            Platform.Windows -> unregisterWindows(handle)
            Platform.MacOS -> unregisterMacOs(handle)
            Platform.Linux -> unregisterLinux(handle)
            else -> false
        }
    }

    /**
     * Shut down the hotkey subsystem.
     * Unregisters all hotkeys and stops the native message loop.
     */
    @Synchronized
    fun shutdown() {
        if (!initialized) return

        when (Platform.Current) {
            Platform.Windows -> {
                NativeWindowsHotKeyBridge.nativeShutdown()
                NativeWindowsHotKeyBridge.clearListeners()
            }
            Platform.MacOS -> {
                NativeMacOsHotKeyBridge.nativeShutdown()
                NativeMacOsHotKeyBridge.clearListeners()
            }
            Platform.Linux -> {
                NativeLinuxHotKeyBridge.nativeShutdown()
                NativeLinuxHotKeyBridge.clearListeners()
            }
            else -> {}
        }

        initialized = false
    }

    private fun registerWindows(
        keyCode: Int,
        modifiers: Int,
        listener: HotKeyListener,
    ): Long {
        val id = NativeWindowsHotKeyBridge.registerListener(listener)

        // Map portable modifier flags to Win32 MOD_* constants
        var winMods = 0
        if (modifiers and HotKeyModifier.ALT.nativeFlag != 0) winMods = winMods or HotKeyModifier.ALT.nativeFlag
        if (modifiers and HotKeyModifier.CONTROL.nativeFlag != 0) winMods = winMods or HotKeyModifier.CONTROL.nativeFlag
        if (modifiers and HotKeyModifier.SHIFT.nativeFlag != 0) winMods = winMods or HotKeyModifier.SHIFT.nativeFlag
        if (modifiers and HotKeyModifier.META.nativeFlag != 0) winMods = winMods or HotKeyModifier.META.nativeFlag
        winMods = winMods or WIN32_MOD_NOREPEAT

        val error = NativeWindowsHotKeyBridge.nativeRegister(id, winMods, keyCode)
        if (error != null) {
            NativeWindowsHotKeyBridge.removeListener(id)
            lastError = error
            logger.warning("register(keyCode=$keyCode, modifiers=$modifiers) failed: $error")
            return -1
        }
        lastError = null
        return id
    }

    private fun unregisterWindows(handle: Long): Boolean {
        val error = NativeWindowsHotKeyBridge.nativeUnregister(handle)
        NativeWindowsHotKeyBridge.removeListener(handle)
        lastError = error
        if (error != null) {
            logger.warning("unregister(handle=$handle) failed: $error")
            return false
        }
        return true
    }

    private fun registerMacOs(
        keyCode: Int,
        modifiers: Int,
        listener: HotKeyListener,
    ): Long {
        val id = NativeMacOsHotKeyBridge.registerListener(listener)

        val error = NativeMacOsHotKeyBridge.nativeRegister(id, modifiers, keyCode)
        if (error != null) {
            NativeMacOsHotKeyBridge.removeListener(id)
            lastError = error
            logger.warning("register(keyCode=$keyCode, modifiers=$modifiers) failed: $error")
            return -1
        }
        lastError = null
        return id
    }

    private fun unregisterMacOs(handle: Long): Boolean {
        val error = NativeMacOsHotKeyBridge.nativeUnregister(handle)
        NativeMacOsHotKeyBridge.removeListener(handle)
        lastError = error
        if (error != null) {
            logger.warning("unregister(handle=$handle) failed: $error")
            return false
        }
        return true
    }

    private fun registerLinux(
        keyCode: Int,
        modifiers: Int,
        description: String?,
        listener: HotKeyListener,
    ): Long {
        val id = NativeLinuxHotKeyBridge.registerListener(listener)
        val error = NativeLinuxHotKeyBridge.nativeRegister(id, modifiers, keyCode, description)
        if (error != null) {
            NativeLinuxHotKeyBridge.removeListener(id)
            lastError = error
            logger.warning("register(keyCode=$keyCode, modifiers=$modifiers) failed: $error")
            return -1
        }
        lastError = null
        return id
    }

    private fun unregisterLinux(handle: Long): Boolean {
        val error = NativeLinuxHotKeyBridge.nativeUnregister(handle)
        NativeLinuxHotKeyBridge.removeListener(handle)
        lastError = error
        if (error != null) {
            logger.warning("unregister(handle=$handle) failed: $error")
            return false
        }
        return true
    }

    private fun ensureReady(): Boolean {
        if (!isAvailable) {
            lastError = "Not available on this platform"
            return false
        }
        if (!initialized) {
            lastError = "Not initialized - call GlobalHotKeyManager.initialize() first"
            return false
        }
        return true
    }
}

package dev.nucleusframework.nucleus.globalhotkey.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.globalhotkey.HotKeyListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_global_hotkey"

internal object NativeWindowsHotKeyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsHotKeyBridge::class.java)
    private val listeners = ConcurrentHashMap<Long, HotKeyListener>()
    private val idGenerator = AtomicLong(0)

    val isLoaded: Boolean get() = loaded

    /**
     * Start the native message loop thread. Must be called once before registering hotkeys.
     * @return error message or null on success.
     */
    @JvmStatic
    external fun nativeInit(): String?

    /**
     * Register a global hotkey.
     * @param id unique registration id.
     * @param modifiers Win32 modifier flags (MOD_ALT, MOD_CONTROL, MOD_SHIFT, MOD_WIN).
     * @param keyCode Win32 virtual key code.
     * @return error message or null on success.
     */
    @JvmStatic
    external fun nativeRegister(
        id: Long,
        modifiers: Int,
        keyCode: Int,
    ): String?

    /**
     * Unregister a previously registered global hotkey.
     * @param id the registration id returned by [nativeRegister].
     * @return error message or null on success.
     */
    @JvmStatic
    external fun nativeUnregister(id: Long): String?

    /**
     * Unregister all hotkeys and stop the message loop.
     */
    @JvmStatic
    external fun nativeShutdown()

    // Called from native code when a hotkey fires
    @JvmStatic
    fun onHotKey(
        id: Long,
        keyCode: Int,
        modifiers: Int,
    ) {
        listeners[id]?.onHotKey(keyCode, modifiers)
    }

    fun registerListener(listener: HotKeyListener): Long {
        val id = idGenerator.incrementAndGet()
        listeners[id] = listener
        return id
    }

    fun removeListener(id: Long) {
        listeners.remove(id)
    }

    fun clearListeners() {
        listeners.clear()
    }
}

package dev.nucleusframework.globalhotkey.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.globalhotkey.HotKeyListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_global_hotkey"

internal object NativeMacOsHotKeyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacOsHotKeyBridge::class.java)
    private val listeners = ConcurrentHashMap<Long, HotKeyListener>()
    private val idGenerator = AtomicLong(0)

    val isLoaded: Boolean get() = loaded

    /**
     * Initialize the Carbon event handler. Must be called once before registering hotkeys.
     * @return error message or null on success.
     */
    @JvmStatic
    external fun nativeInit(): String?

    /**
     * Register a global hotkey.
     * @param id unique registration id.
     * @param modifiers Carbon modifier flags (cmdKey, optionKey, controlKey, shiftKey).
     * @param keyCode macOS virtual key code (Carbon key code).
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
     * @param id the registration id.
     * @return error message or null on success.
     */
    @JvmStatic
    external fun nativeUnregister(id: Long): String?

    /**
     * Unregister all hotkeys and clean up.
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
        // Native fires on its own thread; resolve the listener on the UI thread so a
        // press queued before unregister() is dropped rather than delivered late.
        NucleusUiThread.post { listeners[id]?.onHotKey(keyCode, modifiers) }
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

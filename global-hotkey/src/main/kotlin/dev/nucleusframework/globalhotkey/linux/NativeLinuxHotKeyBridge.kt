package dev.nucleusframework.globalhotkey.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.globalhotkey.HotKeyListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_global_hotkey"

internal object NativeLinuxHotKeyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxHotKeyBridge::class.java)
    private val listeners = ConcurrentHashMap<Long, HotKeyListener>()
    private val idGenerator = AtomicLong(0)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeInit(): String?

    /**
     * Store a hotkey entry. On the portal (Wayland) backend this does **not** call
     * BindShortcuts — use [nativeBindShortcuts] after a batch of registrations so the
     * system dialog appears once. X11 still grabs the key immediately.
     */
    @JvmStatic
    external fun nativeRegister(
        id: Long,
        modifiers: Int,
        keyCode: Int,
        description: String?,
    ): String?

    @JvmStatic
    external fun nativeUnregister(id: Long): String?

    /**
     * Push the full current hotkey set to `org.freedesktop.portal.GlobalShortcuts`
     * via a single BindShortcuts call. No-op on X11.
     */
    @JvmStatic
    external fun nativeBindShortcuts(): String?

    /** Portal shortcut_id for [id], or null if unknown. Stable across launches. */
    @JvmStatic
    external fun nativeShortcutId(id: Long): String?

    @JvmStatic
    external fun nativeShutdown()

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

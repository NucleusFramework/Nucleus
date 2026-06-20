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

    @JvmStatic
    external fun nativeRegister(
        id: Long,
        modifiers: Int,
        keyCode: Int,
        description: String?,
    ): String?

    @JvmStatic
    external fun nativeUnregister(id: Long): String?

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

package dev.nucleusframework.globalhotkey.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.globalhotkey.HotKeyEvent
import dev.nucleusframework.globalhotkey.HotKeyEventListener
import dev.nucleusframework.globalhotkey.HotKeyState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_global_hotkey"

/**
 * A press within this delay of the previous one, with no release in between, is an auto-repeat.
 * Keyboard repeat starts after ~250–660 ms; the bound keeps a portal that never emits
 * `Deactivated` from turning every later press into a repeat.
 */
private const val REPEAT_WINDOW_NANOS = 1_000_000_000L

internal object NativeLinuxHotKeyBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxHotKeyBridge::class.java)
    private val registrations = ConcurrentHashMap<Long, Registration>()
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

    /**
     * Called from native code for every press and release: the portal's `Activated` /
     * `Deactivated` signals (with the `activation_token` of their options) on Wayland, the
     * grabbed key's `KeyPress` / `KeyRelease` on X11.
     */
    @JvmStatic
    fun onHotKeyEvent(
        id: Long,
        keyCode: Int,
        modifiers: Int,
        pressed: Boolean,
        timestamp: Long,
        activationToken: String?,
    ) {
        // Arrival time, not the UI thread's: a busy UI thread would stretch the gaps.
        val receivedNanos = System.nanoTime()
        // Native fires on its own thread; resolve the listener on the UI thread so an
        // event queued before unregister() is dropped rather than delivered late.
        NucleusUiThread.post {
            val registration = registrations[id] ?: return@post
            val event =
                if (pressed) {
                    val last = registration.lastPressNanos
                    val repeat = last != null && receivedNanos - last < REPEAT_WINDOW_NANOS
                    registration.lastPressNanos = receivedNanos
                    HotKeyEvent(keyCode, modifiers, HotKeyState.PRESSED, repeat, timestamp, activationToken)
                } else {
                    registration.lastPressNanos = null
                    HotKeyEvent(keyCode, modifiers, HotKeyState.RELEASED, false, timestamp, activationToken)
                }
            registration.listener.onHotKeyEvent(event)
        }
    }

    fun registerListener(listener: HotKeyEventListener): Long {
        val id = idGenerator.incrementAndGet()
        registrations[id] = Registration(listener)
        return id
    }

    fun removeListener(id: Long) {
        registrations.remove(id)
    }

    fun clearListeners() {
        registrations.clear()
    }

    private class Registration(
        val listener: HotKeyEventListener,
    ) {
        /** When the last press arrived, null once released. UI thread only. */
        var lastPressNanos: Long? = null
    }
}

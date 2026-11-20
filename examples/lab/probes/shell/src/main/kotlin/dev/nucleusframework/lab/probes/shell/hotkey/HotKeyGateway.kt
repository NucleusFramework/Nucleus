package dev.nucleusframework.lab.probes.shell.hotkey

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyListener
import dev.nucleusframework.globalhotkey.MediaKey
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A hotkey press as reported by the listener of registration [handle]. */
data class Press(
    val handle: Long,
    val keyCode: Int,
    val modifiers: Int,
)

/** `handle` on success, `error` otherwise. */
data class Registered(
    val handle: Long?,
    val error: String?,
)

/** Port over `global-hotkey`. */
interface HotKeyGateway {
    fun availability(): Availability

    fun backendName(): String

    fun initialize(): CallOutcome

    fun register(
        keyCode: Int,
        modifiers: Int,
        description: String,
    ): Registered

    fun register(mediaKey: MediaKey): Registered

    fun unregister(handle: Long): CallOutcome

    /** Linux/Wayland portal: flush the debounced BindShortcuts now. */
    fun commit(): CallOutcome

    fun portalShortcutId(handle: Long): String?

    val presses: SharedFlow<Stamped<Press>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusHotKeyGateway : HotKeyGateway {
    private val pressFlow = MutableSharedFlow<Stamped<Press>>(extraBufferCapacity = 64)
    override val presses: SharedFlow<Stamped<Press>> = pressFlow.asSharedFlow()

    override fun availability(): Availability =
        Availability.of(GlobalHotKeyManager.isAvailable) { "nucleus_global_hotkey not loaded for this platform" }

    override fun backendName(): String =
        when {
            Platform.Current == Platform.Windows -> "RegisterHotKey on a message-loop thread"
            Platform.Current == Platform.MacOS -> "Carbon RegisterEventHotKey"
            Platform.isWayland -> "xdg-desktop-portal GlobalShortcuts (asks the user once)"
            Platform.Current == Platform.Linux -> "X11 XGrabKey"
            else -> "none"
        }

    override fun initialize(): CallOutcome =
        CallOutcome.of(GlobalHotKeyManager.initialize()) {
            GlobalHotKeyManager.lastError
        }

    override fun register(
        keyCode: Int,
        modifiers: Int,
        description: String,
    ): Registered {
        // The handle is only known once register returns; the listener resolves it lazily.
        var handle = -1L
        handle = GlobalHotKeyManager.register(keyCode, modifiers, description, listener { handle })
        return result(handle)
    }

    override fun register(mediaKey: MediaKey): Registered {
        var handle = -1L
        handle = GlobalHotKeyManager.register(mediaKey, listener { handle })
        return result(handle)
    }

    override fun unregister(handle: Long): CallOutcome =
        CallOutcome.of(GlobalHotKeyManager.unregister(handle)) { GlobalHotKeyManager.lastError }

    override fun commit(): CallOutcome =
        CallOutcome.of(GlobalHotKeyManager.commitRegistrations()) {
            GlobalHotKeyManager.lastError
        }

    override fun portalShortcutId(handle: Long): String? = GlobalHotKeyManager.portalShortcutId(handle)

    private fun listener(handle: () -> Long) =
        HotKeyListener { keyCode, modifiers -> pressFlow.tryEmit(Press(handle(), keyCode, modifiers).stamped()) }

    private fun result(handle: Long) =
        if (handle >=
            0
        ) {
            Registered(handle, null)
        } else {
            Registered(null, GlobalHotKeyManager.lastError ?: "register returned -1")
        }
}

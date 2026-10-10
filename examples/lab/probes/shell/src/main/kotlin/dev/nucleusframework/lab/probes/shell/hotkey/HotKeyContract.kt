package dev.nucleusframework.lab.probes.shell.hotkey

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.globalhotkey.HotKeyModifier
import dev.nucleusframework.globalhotkey.MediaKey
import dev.nucleusframework.globalhotkey.plus
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.lab.core.mvi.replaceWhere
import java.awt.event.KeyEvent

/** A combination the probe can register; [expectFailure] marks the ones the OS should refuse. */
@Immutable
data class Combo(
    val label: String,
    val keyCode: Int,
    val modifiers: Int,
    val expectFailure: Boolean = false,
    val mediaKey: MediaKey? = null,
)

private val primary = if (Platform.Current == Platform.MacOS) HotKeyModifier.META else HotKeyModifier.CONTROL
private val primaryName = if (Platform.Current == Platform.MacOS) "⌘" else "Ctrl"

// AWT key codes are compile-time constants: reading them never initializes AWT.
val Combos: List<Combo> =
    buildList {
        add(Combo("$primaryName+Alt+L", KeyEvent.VK_L, primary + HotKeyModifier.ALT))
        add(Combo("$primaryName+Shift+F12", KeyEvent.VK_F12, primary + HotKeyModifier.SHIFT))
        add(Combo("F13 (no modifier)", KeyEvent.VK_F13, 0))
        when (Platform.Current) {
            Platform.MacOS ->
                add(
                    Combo(
                        "⌘+Space (Spotlight)",
                        KeyEvent.VK_SPACE,
                        HotKeyModifier.META.nativeFlagOf(),
                        expectFailure = true,
                    ),
                )
            Platform.Windows ->
                add(
                    Combo(
                        "Win+L (lock, reserved)",
                        KeyEvent.VK_L,
                        HotKeyModifier.META.nativeFlagOf(),
                        expectFailure = true,
                    ),
                )
            else ->
                add(
                    Combo(
                        "Alt+F4 (window manager)",
                        KeyEvent.VK_F4,
                        HotKeyModifier.ALT.nativeFlagOf(),
                        expectFailure = true,
                    ),
                )
        }
        if (Platform.Current != Platform.MacOS) {
            add(Combo("Media: Play/Pause", 0, 0, mediaKey = MediaKey.PLAY_PAUSE))
            add(Combo("Media: Next track", 0, 0, mediaKey = MediaKey.NEXT_TRACK))
        }
    }

/** `HotKeyModifier.plus` builds masks from two; a single one needs `0 + it`. */
private fun HotKeyModifier.nativeFlagOf(): Int = 0 + this

@Immutable
data class Registration(
    val handle: Long,
    val combo: Combo,
    val portalId: String?,
    val presses: Int = 0,
)

@Immutable
data class HotKeyState(
    val availability: Availability = Availability.Unknown,
    val backend: String = "",
    val initialized: CallOutcome? = null,
    val registrations: List<Registration> = emptyList(),
    val presses: List<Delivery> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
)

sealed interface HotKeyIntent {
    data class Register(
        val combo: Combo,
    ) : HotKeyIntent

    data class Unregister(
        val handle: Long,
    ) : HotKeyIntent

    data object UnregisterAll : HotKeyIntent

    data object Commit : HotKeyIntent
}

sealed interface HotKeyEvent {
    data class Ready(
        val availability: Availability,
        val backend: String,
        val initialized: CallOutcome?,
    ) : HotKeyEvent

    data class RegisterResult(
        val combo: Combo,
        val handle: Long?,
        val portalId: String?,
        val outcome: CallOutcome,
    ) : HotKeyEvent

    data class Unregistered(
        val handle: Long,
        val outcome: CallOutcome,
    ) : HotKeyEvent

    data class Committed(
        val outcome: CallOutcome,
    ) : HotKeyEvent

    data class Pressed(
        val handle: Long,
        val delivery: Delivery,
    ) : HotKeyEvent
}

object HotKeyReducer : Reducer<HotKeyState, HotKeyEvent> {
    override fun reduce(
        state: HotKeyState,
        event: HotKeyEvent,
    ): HotKeyState =
        when (event) {
            is HotKeyEvent.Ready ->
                state.copy(
                    availability = event.availability,
                    backend = event.backend,
                    initialized = event.initialized,
                )
            is HotKeyEvent.RegisterResult ->
                state.copy(
                    registrations =
                        if (event.handle != null) {
                            state.registrations + Registration(event.handle, event.combo, event.portalId)
                        } else {
                            state.registrations
                        },
                    calls = state.calls.plusCall("register(${event.combo.label})", event.outcome),
                )
            is HotKeyEvent.Unregistered ->
                state.copy(
                    registrations =
                        if (event.outcome.ok) {
                            state.registrations.filterNot {
                                it.handle == event.handle
                            }
                        } else {
                            state.registrations
                        },
                    calls = state.calls.plusCall("unregister(${event.handle})", event.outcome),
                )
            is HotKeyEvent.Committed -> state.copy(calls = state.calls.plusCall("commitRegistrations()", event.outcome))
            is HotKeyEvent.Pressed ->
                state.copy(
                    registrations =
                        state.registrations.replaceWhere({ it.handle }, event.handle) {
                            it.copy(presses = it.presses + 1)
                        },
                    presses = state.presses.plusDelivery(event.delivery),
                )
        }
}

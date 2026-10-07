package dev.nucleusframework.lab.probes.shell.hotkey

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class HotKeyViewModel(
    private val gateway: HotKeyGateway,
    timeline: Timeline,
) : MviViewModel<HotKeyState, HotKeyIntent, HotKeyEvent, Nothing>(
        HotKeyState(),
        HotKeyReducer,
        timeline,
        HotKeyProbe.ID,
    ) {
    init {
        val availability = gateway.availability()
        val initialized = if (availability.isAvailable) gateway.initialize() else null
        dispatch(
            HotKeyEvent.Ready(availability, gateway.backendName(), initialized),
            if (initialized?.ok == false) Severity.Error else Severity.Info,
        )
        launch {
            gateway.presses.collect { stamped ->
                val registration = state.value.registrations.firstOrNull { it.handle == stamped.value.handle }
                val label = registration?.combo?.label ?: "handle ${stamped.value.handle}"
                val delivery = stamped.map { "pressed $label" }.toDelivery()
                dispatch(stamped.map { HotKeyEvent.Pressed(it.handle, delivery) })
            }
        }
    }

    override suspend fun handle(intent: HotKeyIntent) {
        when (intent) {
            is HotKeyIntent.Register -> register(intent.combo)
            is HotKeyIntent.Unregister -> unregister(intent.handle)
            HotKeyIntent.UnregisterAll -> state.value.registrations.forEach { unregister(it.handle) }
            HotKeyIntent.Commit -> {
                val outcome = gateway.commit()
                dispatch(HotKeyEvent.Committed(outcome), severity(outcome))
            }
        }
    }

    private fun register(combo: Combo) {
        val result =
            combo.mediaKey?.let(gateway::register)
                ?: gateway.register(combo.keyCode, combo.modifiers, "Lab: ${combo.label}")
        val outcome = if (result.handle != null) CallOutcome.Ok else CallOutcome(false, result.error)
        val portalId = result.handle?.let(gateway::portalShortcutId)
        // A refusal the probe expected is the OS doing its job, not a failure of the module.
        val severity =
            when {
                outcome.ok && combo.expectFailure -> Severity.Warning
                !outcome.ok && !combo.expectFailure -> Severity.Error
                else -> Severity.Info
            }
        dispatch(HotKeyEvent.RegisterResult(combo, result.handle, portalId, outcome), severity)
    }

    private fun unregister(handle: Long) {
        val outcome = gateway.unregister(handle)
        dispatch(HotKeyEvent.Unregistered(handle, outcome), severity(outcome))
    }

    /** A reset probe must give its combos back to the OS. */
    override fun onCleared() {
        state.value.registrations.forEach { gateway.unregister(it.handle) }
    }

    private fun severity(outcome: CallOutcome) = if (outcome.ok) Severity.Info else Severity.Error
}

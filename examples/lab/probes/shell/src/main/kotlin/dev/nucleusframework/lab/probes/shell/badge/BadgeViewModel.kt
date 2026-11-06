package dev.nucleusframework.lab.probes.shell.badge

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class BadgeViewModel(
    private val gateway: BadgeGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<BadgeState, BadgeIntent, BadgeEvent, Nothing>(BadgeState(), BadgeReducer, timeline, BadgeProbe.ID) {
    init {
        launch {
            val availability = gateway.availability()
            val prepared =
                if (availability.isAvailable) gateway.prepare() else CallOutcome(false, "skipped: unavailable")
            dispatch(
                BadgeEvent.Ready(
                    backend = gateway.backend,
                    availability = availability,
                    target = gateway.target(),
                    prepared = prepared,
                    canReadBack = gateway.canReadBack,
                    supportsGlyphs = gateway.supportsGlyphs,
                ),
                if (prepared.ok) Severity.Info else Severity.Error,
            )
            if (gateway.canReadBack && availability.isAvailable) dispatch(BadgeEvent.ReadBack(gateway.readBack()))
        }
        // nucleus-lab://probe/shell.badge?count=7 | ?clear
        onParams(commands) { params ->
            params.int("count")?.let { onIntent(BadgeIntent.SetCount(it)) }
            if (params.flag("clear")) onIntent(BadgeIntent.Clear)
        }
    }

    override suspend fun handle(intent: BadgeIntent) {
        when (intent) {
            is BadgeIntent.SetCount ->
                call(
                    "setCount(${intent.count})",
                    intent.count.toString(),
                ) { gateway.setCount(intent.count) }
            is BadgeIntent.SetGlyph ->
                call("setGlyph(${intent.glyph.value})", intent.glyph.value) {
                    gateway.setGlyph(intent.glyph)
                }
            BadgeIntent.Clear -> call("clear()", null) { gateway.clear() }
            BadgeIntent.ReadBack -> dispatch(BadgeEvent.ReadBack(gateway.readBack()))
            is BadgeIntent.Ramp ->
                for (n in 1..intent.to) {
                    call("setCount($n)", n.toString()) { gateway.setCount(n) }
                    delay(RAMP_STEP_MS)
                }
        }
    }

    private suspend fun call(
        description: String,
        requested: String?,
        block: suspend () -> CallOutcome,
    ) {
        val outcome = block()
        dispatch(BadgeEvent.Called(description, requested, outcome), if (outcome.ok) Severity.Info else Severity.Error)
        if (gateway.canReadBack && outcome.ok) dispatch(BadgeEvent.ReadBack(gateway.readBack()))
    }

    private companion object {
        const val RAMP_STEP_MS = 300L
    }
}

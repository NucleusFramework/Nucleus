package dev.nucleusframework.lab.probes.lifecycle.identity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@Immutable
data class IdentityState(
    val identity: Identity? = null,
    /** Fields that differ from the previous read: none should, short of an update. */
    val drift: List<String> = emptyList(),
)

sealed interface IdentityIntent {
    data object Refresh : IdentityIntent
}

sealed interface IdentityEvent {
    data class Read(
        val identity: Identity,
    ) : IdentityEvent {
        override fun toString(): String =
            "Read(${identity.appId} ${identity.version} ${identity.executableType} aot=${identity.aotMode})"
    }
}

object IdentityReducer : Reducer<IdentityState, IdentityEvent> {
    override fun reduce(
        state: IdentityState,
        event: IdentityEvent,
    ): IdentityState =
        when (event) {
            is IdentityEvent.Read -> {
                val previous = state.identity
                // Uptime moves on by itself; anything else changing between reads is a bug.
                val drifted = previous != null && event.identity.copy(uptimeMillis = previous.uptimeMillis) != previous
                state.copy(
                    identity = previous ?: event.identity,
                    drift = if (drifted) listOf("changed since the first read: ${event.identity}") else emptyList(),
                )
            }
        }
}

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class IdentityViewModel(
    private val gateway: IdentityGateway,
    timeline: Timeline,
) : MviViewModel<IdentityState, IdentityIntent, IdentityEvent, Nothing>(
        IdentityState(),
        IdentityReducer,
        timeline,
        IdentityProbe.ID,
    ) {
    init {
        dispatch(IdentityEvent.Read(gateway.read()))
    }

    override suspend fun handle(intent: IdentityIntent) {
        when (intent) {
            IdentityIntent.Refresh -> dispatch(IdentityEvent.Read(gateway.read()))
        }
    }
}

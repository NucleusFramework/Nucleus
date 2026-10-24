package dev.nucleusframework.lab.probes.updater.network

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** URLs whose outcome is known in advance, so a wrong answer stands out. */
enum class Target(
    val label: String,
    val url: String,
    val expectation: String,
) {
    Public("Public CA", "https://example.com/", "every client: 200"),
    SelfSigned("Self-signed", "https://self-signed.badssl.com/", "every client: a certificate error"),
    Expired("Expired", "https://expired.badssl.com/", "every client: a certificate error"),
    UntrustedRoot("Untrusted root", "https://untrusted-root.badssl.com/", "every client: a certificate error"),
    WrongHost("Wrong host", "https://wrong.host.badssl.com/", "every client: a hostname error"),
}

@Immutable
data class HttpClientsState(
    val url: String = Target.Public.url,
    val expectation: String? = Target.Public.expectation,
    val running: Boolean = false,
    val outcomes: List<HttpOutcome> = emptyList(),
)

sealed interface HttpClientsIntent {
    data class EditUrl(
        val url: String,
    ) : HttpClientsIntent

    data class Pick(
        val target: Target,
    ) : HttpClientsIntent

    data object Run : HttpClientsIntent
}

sealed interface HttpClientsEvent {
    data class UrlChanged(
        val url: String,
        val expectation: String?,
    ) : HttpClientsEvent

    data object Started : HttpClientsEvent

    data class Answered(
        val outcome: HttpOutcome,
    ) : HttpClientsEvent {
        override fun toString(): String =
            "${outcome.client}: ${outcome.status ?: outcome.error} in ${outcome.millis} ms"
    }

    data object Finished : HttpClientsEvent
}

object HttpClientsReducer : Reducer<HttpClientsState, HttpClientsEvent> {
    override fun reduce(
        state: HttpClientsState,
        event: HttpClientsEvent,
    ): HttpClientsState =
        when (event) {
            is HttpClientsEvent.UrlChanged -> state.copy(url = event.url, expectation = event.expectation)
            HttpClientsEvent.Started -> state.copy(running = true, outcomes = emptyList())
            is HttpClientsEvent.Answered ->
                state.copy(
                    outcomes = (state.outcomes + event.outcome).sortedBy { it.client.ordinal },
                )
            HttpClientsEvent.Finished -> state.copy(running = false)
        }
}

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class HttpClientsViewModel(
    private val gateway: HttpClientsGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<HttpClientsState, HttpClientsIntent, HttpClientsEvent, Nothing>(
        HttpClientsState(),
        HttpClientsReducer,
        timeline,
        HttpClientsProbe.ID,
    ) {
    init {
        // nucleus-lab://probe/network.http-clients?url=https://…&run=true
        onParams(commands) { params ->
            params["url"]?.let { dispatch(HttpClientsEvent.UrlChanged(it, null)) }
            if (params.bool("run") == true) run()
        }
    }

    override suspend fun handle(intent: HttpClientsIntent) {
        when (intent) {
            is HttpClientsIntent.EditUrl -> reduceSilently(HttpClientsEvent.UrlChanged(intent.url, null))
            is HttpClientsIntent.Pick ->
                dispatch(
                    HttpClientsEvent.UrlChanged(intent.target.url, intent.target.expectation),
                )
            HttpClientsIntent.Run -> run()
        }
    }

    private suspend fun run() {
        val url = state.value.url.trim()
        dispatch(HttpClientsEvent.Started)
        // Side by side: every client at once, each answer as it comes.
        coroutineScope {
            LabHttpClient.entries
                .map { client ->
                    async {
                        val outcome = io { gateway.request(client, url) }
                        dispatch(
                            HttpClientsEvent.Answered(outcome),
                            if (outcome.error != null) Severity.Warning else Severity.Info,
                        )
                    }
                }.awaitAll()
        }
        dispatch(HttpClientsEvent.Finished)
    }
}

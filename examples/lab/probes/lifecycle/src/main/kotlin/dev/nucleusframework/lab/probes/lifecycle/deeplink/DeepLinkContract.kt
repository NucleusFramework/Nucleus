package dev.nucleusframework.lab.probes.lifecycle.deeplink

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.commands.ReceivedDeepLink
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.replaceWhere
import dev.nucleusframework.lab.core.process.ProcessUpdate
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchRecord

/** How a link reaches the app: each one exercises a different path into `onDeepLink`. */
enum class LinkRoute(
    val label: String,
) {
    /** Straight into the handler, as the macOS Apple Event sink does. */
    InProcess("In process"),

    /** A second launch with the link as argument: single-instance forwarding, then `onDeepLink`. */
    SecondInstance("Second instance"),

    /** The OS URL scheme registry (`open` / `xdg-open` / `start`): needs an installed app. */
    Os("Through the OS"),
}

@Immutable
data class SuggestedLink(
    val label: String,
    val uri: String,
)

@Immutable
data class DeepLinkState(
    val scheme: String,
    /** `nucleusApplication` only takes the single-instance lock outside dev runs. */
    val singleInstanceActive: Boolean,
    val executableType: String,
    val draft: String,
    val route: LinkRoute = LinkRoute.InProcess,
    val suggestions: List<SuggestedLink> = emptyList(),
    val received: List<ReceivedDeepLink> = emptyList(),
    val paramsReceived: List<Map<String, String>> = emptyList(),
    val launches: List<LaunchRecord> = emptyList(),
    val osResult: String? = null,
    val deliveryError: String? = null,
)

sealed interface DeepLinkIntent {
    data class EditDraft(
        val text: String,
    ) : DeepLinkIntent

    data class SetRoute(
        val route: LinkRoute,
    ) : DeepLinkIntent

    data object Send : DeepLinkIntent

    data object KillLaunched : DeepLinkIntent
}

sealed interface DeepLinkEvent {
    data class DraftChanged(
        val text: String,
    ) : DeepLinkEvent

    data class RouteChanged(
        val route: LinkRoute,
    ) : DeepLinkEvent

    data class Received(
        val links: List<ReceivedDeepLink>,
    ) : DeepLinkEvent {
        override fun toString(): String = "Received(${links.size} links, last=${links.lastOrNull()?.uri})"
    }

    data class ParamsReceived(
        val params: Map<String, String>,
    ) : DeepLinkEvent

    data class LaunchStarted(
        val record: LaunchRecord,
    ) : DeepLinkEvent

    data class LaunchProgress(
        val id: Int,
        val update: ProcessUpdate,
    ) : DeepLinkEvent

    data class OsOpened(
        val command: String,
    ) : DeepLinkEvent

    data class DeliveryFailed(
        val reason: String,
    ) : DeepLinkEvent

    data class Killed(
        val count: Int,
    ) : DeepLinkEvent
}

object DeepLinkReducer : Reducer<DeepLinkState, DeepLinkEvent> {
    override fun reduce(
        state: DeepLinkState,
        event: DeepLinkEvent,
    ): DeepLinkState =
        when (event) {
            is DeepLinkEvent.DraftChanged -> state.copy(draft = event.text)
            is DeepLinkEvent.RouteChanged -> state.copy(route = event.route)
            is DeepLinkEvent.Received -> state.copy(received = event.links)
            is DeepLinkEvent.ParamsReceived -> state.copy(paramsReceived = state.paramsReceived.append(event.params))
            is DeepLinkEvent.LaunchStarted -> state.copy(launches = state.launches.append(event.record))
            is DeepLinkEvent.LaunchProgress ->
                state.copy(
                    launches = state.launches.replaceWhere(LaunchRecord::id, event.id) { it.apply(event.update) },
                )
            is DeepLinkEvent.OsOpened -> state.copy(osResult = event.command, deliveryError = null)
            is DeepLinkEvent.DeliveryFailed -> state.copy(deliveryError = event.reason)
            is DeepLinkEvent.Killed -> state
        }
}

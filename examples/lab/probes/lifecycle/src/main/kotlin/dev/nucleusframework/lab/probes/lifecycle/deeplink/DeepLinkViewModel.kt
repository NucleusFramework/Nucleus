package dev.nucleusframework.lab.probes.lifecycle.deeplink

import androidx.lifecycle.ViewModel
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.process.ProcessUpdate
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.lifecycle.SelfLauncher
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchRecord
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicInteger

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class DeepLinkViewModel(
    private val commands: LabCommands,
    private val launcher: SelfLauncher,
    timeline: Timeline,
) : MviViewModel<DeepLinkState, DeepLinkIntent, DeepLinkEvent, Nothing>(
        initialState(),
        DeepLinkReducer,
        timeline,
        DeepLinkProbe.ID,
    ) {
    private val launchIds = AtomicInteger()

    init {
        launch { commands.deepLinks.collect { reduceSilently(DeepLinkEvent.Received(it)) } }
        onParams(commands) { dispatch(DeepLinkEvent.ParamsReceived(it.values)) }
    }

    override suspend fun handle(intent: DeepLinkIntent) {
        when (intent) {
            is DeepLinkIntent.EditDraft -> reduceSilently(DeepLinkEvent.DraftChanged(intent.text))
            is DeepLinkIntent.SetRoute -> dispatch(DeepLinkEvent.RouteChanged(intent.route))
            DeepLinkIntent.Send -> send(state.value.draft.trim(), state.value.route)
            DeepLinkIntent.KillLaunched -> dispatch(DeepLinkEvent.Killed(launcher.killAll()))
        }
    }

    private suspend fun send(
        link: String,
        route: LinkRoute,
    ) {
        when (route) {
            LinkRoute.InProcess -> {
                val uri =
                    try {
                        URI(link)
                    } catch (e: URISyntaxException) {
                        dispatch(DeepLinkEvent.DeliveryFailed("Not a URI: ${e.reason}"), Severity.Error)
                        return
                    }
                commands.handleDeepLink(uri)
            }
            LinkRoute.SecondInstance -> {
                val id = launchIds.incrementAndGet()
                dispatch(DeepLinkEvent.LaunchStarted(LaunchRecord(id, "deliver $link", System.currentTimeMillis())))
                launcher.launch(listOf(link)).collect { update ->
                    val severity = if (update is ProcessUpdate.Failed) Severity.Error else Severity.Info
                    if (update is ProcessUpdate.Output) {
                        reduceSilently(DeepLinkEvent.LaunchProgress(id, update))
                    } else {
                        dispatch(DeepLinkEvent.LaunchProgress(id, update), severity)
                    }
                }
            }
            LinkRoute.Os ->
                io { launcher.openWithOs(link) }
                    .onSuccess { dispatch(DeepLinkEvent.OsOpened(it)) }
                    .onFailure { dispatch(DeepLinkEvent.DeliveryFailed(it.message ?: it.summary), Severity.Error) }
        }
    }

    private companion object {
        fun initialState(): DeepLinkState {
            val self = DeepLinkProbe.ID
            return DeepLinkState(
                scheme = LabCommands.SCHEME,
                singleInstanceActive = !ExecutableRuntime.isDev(),
                executableType = ExecutableRuntime.type().name,
                draft = LabCommands.deepLink(self, mapOf("echo" to "hello")),
                suggestions =
                    listOf(
                        SuggestedLink(
                            "Echo back here",
                            LabCommands.deepLink(self, mapOf("echo" to "hello", "n" to "1")),
                        ),
                        SuggestedLink("Open Overview", LabCommands.deepLink(ProbeId("lab.overview"))),
                        SuggestedLink("Open Appearance", LabCommands.deepLink(ProbeId("system.appearance"))),
                        SuggestedLink("Unknown probe", LabCommands.deepLink(ProbeId("nowhere.missing"))),
                        SuggestedLink("Not a Lab command", "${LabCommands.SCHEME}://something-else/path"),
                        SuggestedLink(
                            "Encoded params",
                            LabCommands.deepLink(
                                self,
                                mapOf(
                                    "text" to "caf%C3%A9%20%26%20cr%C3%A8me",
                                ),
                            ),
                        ),
                    ),
            )
        }
    }
}

package dev.nucleusframework.lab.probes.updater.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Immutable
data class TrustStoreState(
    val report: TrustStoreReport? = null,
    val error: String? = null,
    val filter: String = "",
)

sealed interface TrustStoreIntent {
    data object Read : TrustStoreIntent

    data class Filter(
        val text: String,
    ) : TrustStoreIntent
}

sealed interface TrustStoreEvent {
    data class Loaded(
        val report: TrustStoreReport,
    ) : TrustStoreEvent {
        override fun toString(): String =
            "Loaded(jdk=${report.jdkCount}, combined=${report.combinedCount}, osOnly=${report.osOnly.size}, " +
                "${report.loadMillis} ms)"
    }

    data class Failed(
        val reason: String,
    ) : TrustStoreEvent

    data class Filtered(
        val text: String,
    ) : TrustStoreEvent
}

object TrustStoreReducer : Reducer<TrustStoreState, TrustStoreEvent> {
    override fun reduce(
        state: TrustStoreState,
        event: TrustStoreEvent,
    ): TrustStoreState =
        when (event) {
            is TrustStoreEvent.Loaded -> state.copy(report = event.report, error = null)
            is TrustStoreEvent.Failed -> state.copy(error = event.reason)
            is TrustStoreEvent.Filtered -> state.copy(filter = event.text)
        }
}

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class TrustStoreViewModel(
    private val gateway: TrustStoreGateway,
    timeline: Timeline,
) : MviViewModel<TrustStoreState, TrustStoreIntent, TrustStoreEvent, Nothing>(
        TrustStoreState(),
        TrustStoreReducer,
        timeline,
        TrustStoreProbe.ID,
    ) {
    init {
        onIntent(TrustStoreIntent.Read)
    }

    override suspend fun handle(intent: TrustStoreIntent) {
        when (intent) {
            TrustStoreIntent.Read ->
                runCatching { io { gateway.read() } }
                    .onSuccess { dispatch(TrustStoreEvent.Loaded(it)) }
                    .onFailure { dispatch(TrustStoreEvent.Failed(it.summary), Severity.Error) }
            is TrustStoreIntent.Filter -> reduceSilently(TrustStoreEvent.Filtered(intent.text))
        }
    }
}

@ContributesIntoSet(AppScope::class)
@Inject
class TrustStoreProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "OS trust store",
            domain = Domain.Network,
            summary = "Which certificate authorities does the OS trust that the bundled JDK does not?",
            modules = listOf("native-ssl"),
            checks =
                listOf(
                    Check(
                        "loaded",
                        "The OS-only list is non-empty on a machine with any enterprise/user CA, empty-but-loaded " +
                            "otherwise",
                    ),
                    Check(
                        "user-ca",
                        "Installing a CA in the OS store (Keychain / certmgr / ca-certificates) makes it appear " +
                            "here after Read again",
                    ),
                    Check("fast", "Loading the OS store takes well under a second"),
                ),
            keywords = listOf("certificates", "TLS", "cacerts", "Keychain", "corporate proxy"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<TrustStoreViewModel>()
        val state by vm.state.collectAsState()
        val report = state.report
        val shown =
            report
                ?.osOnly
                ?.filter {
                    state.filter.isBlank() ||
                        it.subject.contains(state.filter, ignoreCase = true)
                }.orEmpty()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "OS certificates",
                        when {
                            state.error != null -> Availability.Unavailable(state.error!!)
                            report == null -> Availability.Unknown
                            else ->
                                Availability.of(report.combinedCount > report.jdkCount) {
                                    "no anchor beyond the JDK's: native store empty or unreadable"
                                }
                        },
                    ),
                ),
            controls = {
                Actions { SecondaryAction("Read again") { vm.onIntent(TrustStoreIntent.Read) } }
                TextFieldRow("Filter by subject", state.filter) { vm.onIntent(TrustStoreIntent.Filter(it)) }
            },
            observed = {
                report?.let {
                    Readout("JDK cacerts", "${it.jdkCount} anchors")
                    Readout("JDK + OS", "${it.combinedCount} anchors")
                    Readout(
                        "OS only",
                        "${it.osOnly.size} anchors",
                        tone = if (it.osOnly.isEmpty()) Tone.Muted else Tone.Ok,
                    )
                    Readout("OS store load", "${formatDurationMillis(it.loadMillis)} (first call loads it)")
                }
                state.error?.let { Readout("Error", it, tone = Tone.Error) }
                SubHeading("OS-only anchors · ${shown.size}")
                if (report != null && shown.isEmpty()) EmptyState("None.")
                shown.take(MAX_ROWS).forEach { Readout(it.subject, "until ${it.notAfter} · ${it.fingerprint}") }
            },
        )
    }

    companion object {
        val ID = ProbeId("network.trust-store")
        private const val MAX_ROWS = 80
    }
}

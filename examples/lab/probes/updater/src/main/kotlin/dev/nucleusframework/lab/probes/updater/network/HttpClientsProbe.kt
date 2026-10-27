package dev.nucleusframework.lab.probes.updater.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class HttpClientsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "HTTP clients & TLS",
            domain = Domain.Network,
            summary =
                "Do the JDK, OkHttp and Ktor clients trust what the OS trusts once native-ssl is plugged in " +
                    "— and still reject bad certificates?",
            modules = listOf("native-http", "native-http-okhttp", "native-http-ktor", "native-ssl"),
            checks =
                listOf(
                    Check("public", "Public CA: all seven clients answer 200, each with a TLS 1.3/1.2 session"),
                    Check(
                        "bad-certs",
                        "Self-signed, expired, untrusted root, wrong host: all seven fail with a certificate " +
                            "error, none answers 200",
                    ),
                    Check(
                        "os-only",
                        "Behind a TLS-inspecting proxy (or with a site signed by a CA only the OS trusts): the " +
                            "native clients answer 200 and the plain ones fail with PKIX",
                    ),
                    Check("chain", "JDK and OkHttp rows show the peer chain, leaf first"),
                    Check(
                        "offline",
                        "With the network off, every client fails fast with a connect error, not a hang past 10 s",
                    ),
                ),
            keywords = listOf("okhttp", "ktor", "HttpClient", "TLS", "PKIX", "badssl"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<HttpClientsViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                TextFieldRow("URL", state.url) { vm.onIntent(HttpClientsIntent.EditUrl(it)) }
                Actions {
                    Target.entries.forEach { target ->
                        SecondaryAction(target.label) { vm.onIntent(HttpClientsIntent.Pick(target)) }
                    }
                }
                Actions {
                    PrimaryAction(
                        if (state.running) "Requesting…" else "Request with every client",
                        enabled = !state.running,
                    ) {
                        vm.onIntent(HttpClientsIntent.Run)
                    }
                }
                state.expectation?.let { Hint("Expected: $it") }
                Hint(
                    "An OS-only CA is the case the native clients exist for; " +
                        "see the OS trust store probe for what yours adds.",
                )
            },
            observed = {
                if (state.outcomes.isEmpty()) {
                    EmptyState(
                        if (state.running) {
                            "Waiting for " +
                                "answers…"
                        } else {
                            "No request yet."
                        },
                    )
                }
                state.outcomes.forEach { outcome ->
                    Readout(
                        outcome.client.label,
                        outcome.error ?: "HTTP ${outcome.status} in ${outcome.millis} ms",
                        tone = if (outcome.error == null) Tone.Ok else Tone.Error,
                    )
                    outcome.tls?.let { Readout("TLS", it, tone = Tone.Muted, indent = 1) }
                    if (outcome.chain.isNotEmpty()) {
                        Readout("chain", outcome.chain.joinToString(" ← "), tone = Tone.Muted, indent = 1)
                    }
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("network.http-clients")
    }
}

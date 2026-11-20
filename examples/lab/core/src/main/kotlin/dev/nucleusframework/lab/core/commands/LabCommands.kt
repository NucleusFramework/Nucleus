package dev.nucleusframework.lab.core.commands

import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Drives the Lab from outside: `nucleus-lab://probe/<id>?key=value` deep links,
 * `-Dlab.probe=<id>?key=value`, or the command palette. The shell navigates on
 * [navigation]; the probe's ViewModel picks its parameters up with [consumeParams].
 */
@SingleIn(AppScope::class)
@Inject
class LabCommands(
    private val timeline: Timeline,
) {
    private val navigationFlow = MutableSharedFlow<ProbeId>(replay = 1, extraBufferCapacity = 8)
    val navigation: SharedFlow<ProbeId> = navigationFlow.asSharedFlow()

    private val pending = MutableStateFlow<Map<ProbeId, Map<String, String>>>(emptyMap())

    private val receivedLinks = MutableStateFlow<List<ReceivedDeepLink>>(emptyList())

    /** Every deep link delivered to `onDeepLink`, Lab command or not, oldest first. */
    val deepLinks: StateFlow<List<ReceivedDeepLink>> = receivedLinks.asStateFlow()

    fun open(
        probe: ProbeId,
        params: Map<String, String> = emptyMap(),
    ) {
        if (params.isNotEmpty()) pending.update { it + (probe to params) }
        navigationFlow.tryEmit(probe)
    }

    /** Accepts `nucleus-lab://probe/<id>?…`, logs and ignores anything else. */
    fun handleDeepLink(uri: URI) {
        timeline.record(null, EntryKind.Event, "Deep link $uri")
        receivedLinks.update {
            it.append(
                ReceivedDeepLink(System.currentTimeMillis(), uri, Thread.currentThread().name),
            )
        }
        val target = parse(uri)
        if (target == null) {
            timeline.record(null, EntryKind.Log, "Not a Lab command: $uri", Severity.Warning)
            return
        }
        open(target.first, target.second)
    }

    /** Applies `-Dlab.probe=<id>?key=value`, if set. */
    fun handleStartupProperty() {
        val raw = System.getProperty(PROBE_PROPERTY) ?: System.getenv("LAB_PROBE") ?: return
        parse(URI("$SCHEME://probe/$raw"))?.let { open(it.first, it.second) }
    }

    /** Emits the parameters sent to [probe], each set once, then forgets them. */
    fun consumeParams(probe: ProbeId): Flow<Map<String, String>> =
        pending
            .mapNotNull { it[probe] }
            .onEach { pending.update { map -> map - probe } }

    companion object {
        const val SCHEME = "nucleus-lab"
        const val PROBE_PROPERTY = "lab.probe"

        fun deepLink(
            probe: ProbeId,
            params: Map<String, String> = emptyMap(),
        ): String {
            val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            return "$SCHEME://probe/$probe" + if (query.isEmpty()) "" else "?$query"
        }

        fun parse(uri: URI): Pair<ProbeId, Map<String, String>>? {
            if (uri.scheme != SCHEME || uri.host != "probe") return null
            val id = uri.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
            val params =
                uri.rawQuery
                    ?.split('&')
                    ?.filter { it.isNotEmpty() }
                    ?.associate { pair ->
                        val key = pair.substringBefore('=')
                        val value = pair.substringAfter('=', "")
                        decode(key) to decode(value)
                    }.orEmpty()
            return ProbeId(id) to params
        }

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}

data class ReceivedDeepLink(
    val epochMillis: Long,
    val uri: URI,
    val thread: String,
)

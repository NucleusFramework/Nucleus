package dev.nucleusframework.lab.probes.notifications

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.replaceWhere
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry

/** A platform callback about the notification identified by [key] (a probe key, a server id, a toast tag). */
data class NotificationCallback<K>(
    val key: K,
    val what: String,
    /** Closed, dismissed or failed: nothing more is expected for [key]. */
    val terminal: Boolean,
)

/** One notification the probe sent, and everything that happened to it since. */
@Immutable
data class Tracked(
    val key: String,
    val title: String,
    val sentAt: Long,
    /** `null` while sending, then the platform id / handle, or the failure. */
    val platformId: String? = null,
    val error: String? = null,
    val events: List<Delivery> = emptyList(),
    /** Closed, dismissed or removed: nothing more is expected. */
    val finished: Boolean = false,
)

private const val TRACKED = 20

fun List<Tracked>.plusTracked(tracked: Tracked): List<Tracked> =
    filterNot {
        it.key == tracked.key
    }.append(tracked, TRACKED)

fun List<Tracked>.update(
    key: String,
    transform: (Tracked) -> Tracked,
): List<Tracked> = replaceWhere(Tracked::key, key, transform)

fun List<Tracked>.record(
    key: String,
    delivery: Delivery,
    finished: Boolean = false,
): List<Tracked> = update(key) { it.copy(events = it.events + delivery, finished = it.finished || finished) }

/** The notification with its fate, its callbacks as children: feed a list of them to `EventLog`. */
fun Tracked.toLogEntry(): LogEntry =
    LogEntry(
        text =
            "“$title” — " +
                when {
                    error != null -> "failed: $error"
                    platformId == null -> "sending…"
                    finished -> "closed · id $platformId"
                    else -> "shown · id $platformId"
                },
        epochMillis = sentAt,
        tone =
            when {
                error != null -> Tone.Error
                finished -> Tone.Muted
                else -> Tone.Neutral
            },
        children = events.map { it.toLogEntry() },
    )

package dev.nucleusframework.lab.core.mvi

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.summary

/** What an API call returned: most Nucleus APIs answer with a flag, a result or a throwable. */
@Immutable
data class CallOutcome(
    val ok: Boolean,
    val error: String? = null,
) {
    companion object {
        val Ok = CallOutcome(true)

        fun of(
            ok: Boolean,
            error: () -> String?,
        ): CallOutcome = if (ok) Ok else CallOutcome(false, error() ?: "returned false, no error reported")

        fun of(result: Result<*>): CallOutcome =
            result.fold(onSuccess = { Ok }, onFailure = { CallOutcome(false, it.summary) })
    }
}

/** One API call and its outcome, as a probe lists them. */
@Immutable
data class CallRecord(
    val epochMillis: Long,
    val call: String,
    val outcome: CallOutcome,
    /** What the call returned, when that is the point (a state, a count). */
    val returned: String? = null,
)

fun List<CallRecord>.plusCall(
    call: String,
    outcome: CallOutcome,
    returned: String? = null,
    cap: Int = DEFAULT_HISTORY,
): List<CallRecord> = append(CallRecord(System.currentTimeMillis(), call, outcome, returned), cap)

/**
 * Something the OS sent back (a click, a press, a command, a notification action) with the
 * thread it arrived on. `onUiThread == null` means the API promises no thread and none is judged.
 */
@Immutable
data class Delivery(
    val epochMillis: Long,
    val what: String,
    val thread: String,
    val onUiThread: Boolean?,
) {
    /** `(main)` when fine, `⚠ on AppKit-callback` when it should not be there. */
    val threadNote: String
        get() = if (onUiThread == false) "⚠ on $thread" else "($thread)"
}

fun Stamped<String>.toDelivery(): Delivery = Delivery(epochMillis, value, thread, onUiThread)

/** A delivery from an API that promises no thread: shown with its thread, not judged. */
fun unjudgedDelivery(
    what: String,
    thread: String = Thread.currentThread().name,
): Delivery = Delivery(System.currentTimeMillis(), what, thread, null)

fun List<Delivery>.plusDelivery(
    delivery: Delivery,
    cap: Int = DEFAULT_HISTORY,
): List<Delivery> = append(delivery, cap)

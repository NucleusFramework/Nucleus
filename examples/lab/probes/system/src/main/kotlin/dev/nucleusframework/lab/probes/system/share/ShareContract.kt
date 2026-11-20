package dev.nucleusframework.lab.probes.system.share

import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.replaceWhere
import dev.nucleusframework.share.ShareAnchor
import dev.nucleusframework.share.ShareError

/** What a payload should produce: the sheet, a specific error, or some error. */
sealed interface Expectation {
    data object Presented : Expectation

    data class Fails(
        val error: ShareError,
    ) : Expectation

    data object AnyError : Expectation
}

/** Every payload of `share-demo`, plus the malformed ones each platform must reject. */
enum class SharePayload(
    val label: String,
    val expectation: Expectation,
) {
    Text("Text", Expectation.Presented),
    Link("Link", Expectation.Presented),
    File("File", Expectation.Presented),
    UnicodeFile("File, spaces + accents", Expectation.Presented),
    Mixed("Mixed: text, link, 2 files", Expectation.Presented),
    Blank("Blank text", Expectation.Fails(ShareError.InvalidItem)),
    Empty("No item", Expectation.Fails(ShareError.Empty)),
    BadUrl("Unparsable URL", Expectation.Fails(ShareError.InvalidItem)),
    ContentUri("content:// URI", Expectation.Fails(ShareError.UnsupportedItem)),
    MissingFile("Missing file", Expectation.AnyError),
}

/** Lab window = `NucleusWindow.share` (anchored); Auto = `ShareSheet.share`, the app's frontmost window. */
enum class ShareParentChoice { LabWindow, Auto }

sealed interface ShareOutcome {
    data object Presented : ShareOutcome

    data class Failed(
        val error: ShareError?,
        val message: String,
    ) : ShareOutcome
}

@Immutable
data class ShareAttempt(
    val id: Int,
    val epochMillis: Long,
    val payload: SharePayload,
    val parent: ShareParentChoice,
    val outcome: ShareOutcome? = null,
    /** Until `share` returned: on macOS and Windows that is once the sheet is on screen. */
    val millis: Long? = null,
)

@Immutable
data class ShareState(
    val supported: Availability = Availability.Unknown,
    val parent: ShareParentChoice = ShareParentChoice.LabWindow,
    val scratchDir: String = "",
    val attempts: List<ShareAttempt> = emptyList(),
)

sealed interface ShareIntent {
    data class SetParent(
        val parent: ShareParentChoice,
    ) : ShareIntent

    /** [window] and [anchor] come from the composition: they are the desktop parent. */
    data class Share(
        val payload: SharePayload,
        val window: NucleusWindow?,
        val anchor: ShareAnchor?,
    ) : ShareIntent {
        override fun toString(): String = "Share(${payload.name}, anchor=${anchor != null})"
    }
}

sealed interface ShareEvent {
    data class Ready(
        val supported: Availability,
        val scratchDir: String,
    ) : ShareEvent

    data class ParentChanged(
        val parent: ShareParentChoice,
    ) : ShareEvent

    data class Started(
        val attempt: ShareAttempt,
    ) : ShareEvent

    data class Finished(
        val id: Int,
        val outcome: ShareOutcome,
        val millis: Long,
    ) : ShareEvent
}

/** Whether [outcome] is what [expectation] asked for. */
fun Expectation.isMetBy(outcome: ShareOutcome): Boolean =
    when (this) {
        Expectation.Presented -> outcome == ShareOutcome.Presented
        is Expectation.Fails -> outcome is ShareOutcome.Failed && outcome.error == error
        Expectation.AnyError -> outcome is ShareOutcome.Failed
    }

fun Expectation.describe(): String =
    when (this) {
        Expectation.Presented -> "sheet shown"
        is Expectation.Fails -> "ShareException($error)"
        Expectation.AnyError -> "a ShareException"
    }

object ShareReducer : Reducer<ShareState, ShareEvent> {
    override fun reduce(
        state: ShareState,
        event: ShareEvent,
    ): ShareState =
        when (event) {
            is ShareEvent.Ready -> state.copy(supported = event.supported, scratchDir = event.scratchDir)
            is ShareEvent.ParentChanged -> state.copy(parent = event.parent)
            is ShareEvent.Started -> state.copy(attempts = state.attempts.append(event.attempt))
            is ShareEvent.Finished ->
                state.copy(
                    attempts =
                        state.attempts.replaceWhere({ it.id }, event.id) {
                            it.copy(outcome = event.outcome, millis = event.millis)
                        },
                )
        }
}

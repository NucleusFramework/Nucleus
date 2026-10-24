package dev.nucleusframework.lab.probes.system.share

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.share.ShareException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.coroutines.cancellation.CancellationException

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ShareViewModel(
    private val gateway: ShareGateway,
    timeline: Timeline,
) : MviViewModel<ShareState, ShareIntent, ShareEvent, Nothing>(ShareState(), ShareReducer, timeline, ShareProbe.ID) {
    private var nextId = 1

    init {
        dispatch(ShareEvent.Ready(gateway.availability(), gateway.scratchDir.toString()))
    }

    override suspend fun handle(intent: ShareIntent) {
        when (intent) {
            is ShareIntent.SetParent -> dispatch(ShareEvent.ParentChanged(intent.parent))
            is ShareIntent.Share -> share(intent)
        }
    }

    private suspend fun share(intent: ShareIntent.Share) {
        val id = nextId++
        val parent = state.value.parent
        dispatch(ShareEvent.Started(ShareAttempt(id, System.currentTimeMillis(), intent.payload, parent)))
        val (outcome, millis) =
            timedMillis {
                try {
                    // Building writes the sample files: off the UI thread.
                    val request = io { gateway.request(intent.payload) }
                    gateway.share(request, parent, intent.window, intent.anchor)
                    ShareOutcome.Presented
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ShareException) {
                    ShareOutcome.Failed(e.error, e.message ?: e.error.name)
                } catch (e: Exception) {
                    ShareOutcome.Failed(null, e.summary)
                } catch (e: LinkageError) {
                    // A broken native bridge: the attempt fails, the probe stays up.
                    ShareOutcome.Failed(null, e.summary)
                }
            }
        val expected = intent.payload.expectation.isMetBy(outcome)
        // An unexpected result is the finding; an expected rejection is the API working.
        dispatch(ShareEvent.Finished(id, outcome, millis), if (expected) Severity.Info else Severity.Error)
    }
}

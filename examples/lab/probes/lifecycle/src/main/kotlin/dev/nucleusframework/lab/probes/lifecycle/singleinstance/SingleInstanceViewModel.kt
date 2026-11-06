package dev.nucleusframework.lab.probes.lifecycle.singleinstance

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.Delivery
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
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SingleInstanceViewModel(
    private val gateway: SingleInstanceGateway,
    private val launcher: SelfLauncher,
    timeline: Timeline,
) : MviViewModel<SingleInstanceState, SingleInstanceIntent, SingleInstanceEvent, Nothing>(
        SingleInstanceState(
            lockTakenByApp = gateway.lockTakenByApp,
            lockFile = gateway.lockFile.toString(),
            restoreRequestFile = gateway.restoreRequestFile.toString(),
            lockIdentifier = gateway.lockIdentifier,
        ),
        SingleInstanceReducer,
        timeline,
        SingleInstanceProbe.ID,
    ) {
    private val launchIds = AtomicInteger()

    init {
        checkLock()
        launch {
            gateway.restoreFileChanges().collect { stamped ->
                // Our own watch thread, not a Nucleus callback: shown with its thread, never judged.
                val change = stamped.value
                val signal = Delivery(System.currentTimeMillis(), "${change.kind} ${change.file}", stamped.thread, null)
                dispatch(SingleInstanceEvent.RestoreFileChanged(signal))
            }
        }
    }

    override suspend fun handle(intent: SingleInstanceIntent) {
        when (intent) {
            SingleInstanceIntent.CheckLock -> checkLock()
            SingleInstanceIntent.LaunchSecond -> launchSecond()
            SingleInstanceIntent.KillLaunched -> launcher.killAll()
        }
    }

    private fun checkLock() {
        gateway
            .holder()
            .onSuccess { dispatch(SingleInstanceEvent.LockChecked(it, System.currentTimeMillis())) }
            .onFailure {
                dispatch(
                    SingleInstanceEvent.LockCheckFailed(it.summary),
                    Severity.Error,
                )
            }
    }

    private suspend fun launchSecond() {
        val id = launchIds.incrementAndGet()
        dispatch(SingleInstanceEvent.LaunchStarted(LaunchRecord(id, "second instance", System.currentTimeMillis())))
        launcher.launch(emptyList()).collect { update ->
            when (update) {
                is ProcessUpdate.Output -> reduceSilently(SingleInstanceEvent.LaunchProgress(id, update))
                is ProcessUpdate.Failed -> dispatch(SingleInstanceEvent.LaunchProgress(id, update), Severity.Error)
                is ProcessUpdate.Started -> {
                    dispatch(SingleInstanceEvent.LaunchProgress(id, update))
                    // The child takes a moment to reach the lock; look again once it had the chance.
                    launch {
                        delay(LOCK_RECHECK_DELAY_MS)
                        checkLock()
                    }
                }
                is ProcessUpdate.Exited -> {
                    dispatch(SingleInstanceEvent.LaunchProgress(id, update))
                    checkLock()
                }
            }
        }
    }

    private companion object {
        const val LOCK_RECHECK_DELAY_MS = 3_000L
    }
}

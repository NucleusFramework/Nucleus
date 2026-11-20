package dev.nucleusframework.lab.probes.notifications.common

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.util.concurrent.atomic.AtomicInteger

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class CommonViewModel(
    private val gateway: CommonGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<CommonState, CommonIntent, CommonEvent, Nothing>(
        CommonState(),
        CommonReducer,
        timeline,
        CommonProbe.ID,
    ) {
    private val counter = AtomicInteger()

    init {
        dispatch(CommonEvent.Ready(gateway.availability()))
        launch {
            gateway.callbacks.collect { stamped ->
                val callback = stamped.value
                val delivery = stamped.map { it.what }.toDelivery()
                dispatch(stamped.map { CommonEvent.Callback(callback.key, delivery, callback.terminal) })
            }
        }
        // nucleus-lab://probe/notifications.common?title=…&message=…  sends at once.
        onParams(commands) { params ->
            val draft = CommonDraft()
            onIntent(
                CommonIntent.Send(
                    draft.copy(title = params["title"] ?: draft.title, message = params["message"] ?: draft.message),
                ),
            )
        }
    }

    override suspend fun handle(intent: CommonIntent) {
        when (intent) {
            is CommonIntent.Send -> send(intent.draft)
            is CommonIntent.Burst ->
                repeat(intent.count) { i -> send(intent.draft.copy(title = "${intent.draft.title} #${i + 1}")) }
            is CommonIntent.Dismiss -> dispatch(CommonEvent.Dismissed(intent.key, gateway.dismiss(intent.key)))
        }
    }

    private fun send(draft: CommonDraft) {
        val key = "n${counter.incrementAndGet()}"
        dispatch(CommonEvent.Sending(key, draft.title, System.currentTimeMillis()))
        val result = gateway.send(key, draft)
        dispatch(CommonEvent.Sent(key, result), if (result.isSuccess) Severity.Info else Severity.Error)
    }
}

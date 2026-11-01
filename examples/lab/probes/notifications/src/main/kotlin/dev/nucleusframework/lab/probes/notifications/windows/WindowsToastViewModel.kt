package dev.nucleusframework.lab.probes.notifications.windows

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.notification.windows.ActivationType
import dev.nucleusframework.notification.windows.AdaptiveProgressBar
import dev.nucleusframework.notification.windows.AdaptiveText
import dev.nucleusframework.notification.windows.ToastActions
import dev.nucleusframework.notification.windows.ToastAudio
import dev.nucleusframework.notification.windows.ToastBindingGeneric
import dev.nucleusframework.notification.windows.ToastButton
import dev.nucleusframework.notification.windows.ToastContent
import dev.nucleusframework.notification.windows.ToastGenericAttributionText
import dev.nucleusframework.notification.windows.ToastHeader
import dev.nucleusframework.notification.windows.ToastInput
import dev.nucleusframework.notification.windows.ToastNotificationData
import dev.nucleusframework.notification.windows.ToastSelectionBox
import dev.nucleusframework.notification.windows.ToastSelectionBoxItem
import dev.nucleusframework.notification.windows.ToastTextBox
import dev.nucleusframework.notification.windows.ToastVisual
import dev.nucleusframework.notification.windows.ToastVisualChild
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.util.concurrent.atomic.AtomicInteger

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class WindowsToastViewModel(
    private val gateway: WindowsToastGateway,
    timeline: Timeline,
) : MviViewModel<WindowsToastState, WindowsToastIntent, WindowsToastEvent, Nothing>(
        WindowsToastState(),
        WindowsToastReducer,
        timeline,
        WindowsToastProbe.ID,
    ) {
    private val counter = AtomicInteger()
    private val sequence = AtomicInteger()

    init {
        val availability = gateway.availability()
        val initialized = if (availability.isAvailable) gateway.initialize() else null
        dispatch(
            WindowsToastEvent.Ready(availability, gateway.identity(), initialized),
            if (initialized == false) Severity.Error else Severity.Info,
        )
        launch {
            gateway.callbacks.collect { stamped ->
                val callback = stamped.value
                val delivery = stamped.map { it.what }.toDelivery()
                dispatch(stamped.map { WindowsToastEvent.Reported(callback.key, delivery, callback.terminal) })
            }
        }
    }

    override suspend fun handle(intent: WindowsToastIntent) {
        when (intent) {
            is WindowsToastIntent.Send -> {
                val tag = "lab-${counter.incrementAndGet()}"
                dispatch(
                    WindowsToastEvent.Sending(
                        tag,
                        intent.draft.title,
                        System.currentTimeMillis(),
                        intent.draft.progress,
                    ),
                )
                val initial = if (intent.draft.progress) progressData(0.0) else null
                val error = gateway.show(content(intent.draft), tag, TOAST_GROUP, initial)
                dispatch(WindowsToastEvent.Shown(tag, error), if (error != null) Severity.Error else Severity.Info)
            }
            is WindowsToastIntent.AdvanceProgress -> {
                val next = ((state.value.progress[intent.tag] ?: 0.0) + PROGRESS_STEP).coerceAtMost(1.0)
                val error = gateway.update(intent.tag, TOAST_GROUP, progressData(next))
                dispatch(
                    WindowsToastEvent.ProgressUpdated(intent.tag, next, error),
                    if (error != null) Severity.Error else Severity.Info,
                )
            }
            is WindowsToastIntent.Remove -> {
                gateway.remove(intent.tag, TOAST_GROUP)
                dispatch(WindowsToastEvent.Removed(intent.tag))
            }
            WindowsToastIntent.ClearAll -> {
                gateway.clearAll()
                dispatch(WindowsToastEvent.Removed(null))
            }
            WindowsToastIntent.ReadHistory -> {
                val (entries, error) = gateway.history()
                dispatch(
                    WindowsToastEvent.HistoryRead(entries.map { "${it.tag} · group ${it.group}" }, error),
                    if (error != null) Severity.Error else Severity.Info,
                )
            }
        }
    }

    /** The bindings `{progress…}` in the toast XML, at [value]. */
    private fun progressData(value: Double) =
        ToastNotificationData(
            sequenceNumber = sequence.incrementAndGet(),
            values =
                mapOf(
                    "progressTitle" to "Downloading lab-data.zip",
                    "progressValue" to value.toString(),
                    "progressValueString" to "${(value * 100).toInt()} %",
                    "progressStatus" to if (value >= 1.0) "Complete" else "In progress…",
                ),
        )

    private fun content(draft: ToastDraft): ToastContent {
        val children = mutableListOf<ToastVisualChild>(AdaptiveText(draft.title), AdaptiveText(draft.body))
        if (draft.progress) {
            children +=
                AdaptiveProgressBar(
                    title = "{progressTitle}",
                    valueBind = "progressValue",
                    valueStringOverride = "{progressValueString}",
                    status = "{progressStatus}",
                )
        }
        val inputs = mutableListOf<ToastInput>()
        if (draft.textBox) inputs += ToastTextBox(id = "reply", title = "Reply", placeholderContent = "Type here…")
        if (draft.selection) {
            inputs +=
                ToastSelectionBox(
                    id = "snooze",
                    title = "Snooze for",
                    defaultSelectionBoxItemId = "15",
                    items =
                        listOf(
                            ToastSelectionBoxItem("5", "5 minutes"),
                            ToastSelectionBoxItem("15", "15 minutes"),
                            ToastSelectionBoxItem("60", "1 hour"),
                        ),
                )
        }
        val buttons = mutableListOf<ToastButton>()
        if (draft.buttons) {
            buttons += ToastButton(content = "Open", arguments = "action=open")
            buttons +=
                ToastButton(content = "Later", arguments = "action=later", activationType = ActivationType.BACKGROUND)
        }
        if (draft.textBox) buttons += ToastButton(content = "Send", arguments = "action=reply", inputId = "reply")
        return ToastContent(
            visual =
                ToastVisual(
                    ToastBindingGeneric(
                        children = children,
                        attribution =
                            draft.attribution.takeIf { it.isNotBlank() }?.let {
                                ToastGenericAttributionText(
                                    it,
                                )
                            },
                    ),
                ),
            actions =
                if (inputs.isEmpty() &&
                    buttons.isEmpty()
                ) {
                    null
                } else {
                    ToastActions(inputs = inputs, buttons = buttons)
                },
            audio = if (draft.silent) ToastAudio(silent = true) else null,
            header = if (draft.header) ToastHeader("lab-header", "Nucleus Lab", "action=header") else null,
            launch = "action=body",
            scenario = draft.scenario,
            duration = draft.duration,
        )
    }

    private companion object {
        const val PROGRESS_STEP = 0.25
    }
}

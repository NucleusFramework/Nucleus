package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.getWidgetState
import dev.nucleusframework.widget.updateWidgetState
import dev.nucleusframework.widget.windows.WidgetManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/**
 * Port over the widget runtime (`widget-runtime` + `widget-windows`): the Lab counter registered by
 * `widgets { }` in the Lab's `main`, and the 1:1 [WidgetManager] for what the high-level API does
 * not cover (unpinning).
 */
interface WindowsWidgetsGateway {
    fun managerAvailability(): Availability

    fun hostStatus(): HostStatus

    /** [NucleusWidgets.events], described. The runtime promises no thread: they are not judged. */
    val events: Flow<Stamped<String>>

    suspend fun list(): Result<List<PinnedLabWidget>>

    /**
     * Changes a widget from the app side, as a background update would: bumps a counter, adds a
     * task to a task list.
     */
    suspend fun push(
        widgetId: String,
        definitionId: String,
    ): CallOutcome

    fun delete(widgetId: String): CallOutcome
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusWindowsWidgetsGateway : WindowsWidgetsGateway {
    // Stamped with the event's own time: the runtime replays its latest events to new collectors.
    override val events: Flow<Stamped<String>> =
        NucleusWidgets.events.map { event ->
            Stamped(event.describe(), Thread.currentThread().name, onUiThread = null, epochMillis = event.epochMillis)
        }

    override fun managerAvailability(): Availability =
        if (Platform.Current != Platform.Windows) {
            Availability.Unavailable("Windows 11 only")
        } else {
            Availability.catching({ "WidgetManager not activatable: run the packaged Lab (runAppX)" }) {
                WidgetManager.isAvailable
            }
        }

    override fun hostStatus(): HostStatus =
        HostStatus(
            host =
                Availability.of(NucleusWidgets.isAvailable) {
                    "no widget host: needs the packaged Lab on Windows 11"
                },
            launchedByHost = NucleusWidgets.isLaunchedByWidgetHost,
            clsid = NucleusApp.windowsWidgetsClsid,
        )

    override suspend fun list(): Result<List<PinnedLabWidget>> =
        runCatching {
            NucleusWidgets
                .pinnedWidgets()
                .filter { it.definitionId == LAB_COUNTER_WIDGET || it.definitionId == LAB_TASKS_WIDGET }
                .map { pinned ->
                    val state = getWidgetState(pinned.id)
                    PinnedLabWidget(
                        id = pinned.id.value,
                        definitionId = pinned.definitionId,
                        size = pinned.size.name,
                        isActive = pinned.isActive,
                        summary =
                            if (pinned.definitionId == LAB_TASKS_WIDGET) {
                                val tasks = LabTasks.read(state)
                                "${tasks.count { !it.isDone }} of ${tasks.size} tasks left"
                            } else {
                                "count ${state[CountKey] ?: 0}"
                            },
                    )
                }
        }

    override suspend fun push(
        widgetId: String,
        definitionId: String,
    ): CallOutcome =
        CallOutcome.of(
            runCatching {
                val id = WidgetId(widgetId)
                if (definitionId == LAB_TASKS_WIDGET) {
                    updateWidgetState(id) { LabTasks.add(it, "From the app at ${LocalTime.now().withNano(0)}") }
                    LabTasksWidget().update(id)
                } else {
                    updateWidgetState(id) {
                        it[CountKey] = (it[CountKey] ?: 0) + 1
                        it[NoteKey] = "pushed by the app"
                    }
                    LabCounterWidget().update(id)
                }
            },
        )

    override fun delete(widgetId: String): CallOutcome =
        CallOutcome.of(runCatching { WidgetManager.getDefault().deleteWidget(widgetId) })

    private fun WidgetEvent.describe(): String =
        when (this) {
            is WidgetEvent.Pinned -> "pinned $id ($definitionId, $size)"
            is WidgetEvent.Unpinned -> "unpinned $id"
            is WidgetEvent.Activated -> "activated $id"
            is WidgetEvent.Deactivated -> "deactivated $id"
            is WidgetEvent.Resized -> "resized $id to $size"
            is WidgetEvent.ActionInvoked -> "action '$key' on $id"
            is WidgetEvent.ActionDropped -> "action '$key' on $id dropped: the card was outdated"
            is WidgetEvent.CustomizationRequested -> "customization requested on $id"
            is WidgetEvent.Rendered -> "rendered $id in $millis ms"
            is WidgetEvent.HostError -> "host error on $id: $details"
            is WidgetEvent.Failed -> "FAILED${id?.let { " on $it" }.orEmpty()}: $message"
        }
}

@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** Declares the app's widgets, inside `widgets { }`. */
public class WidgetsBuilder internal constructor() {
    internal val factories = linkedMapOf<String, () -> NucleusWidget>()

    /**
     * Declares the widget [definitionId], rendered by the [NucleusWidget] [factory] returns. The id
     * must match a `widget("…")` of the Gradle plugin's `windows { widgets { } }` and / or
     * `macOS { widgets { } }` (the WidgetKit kind).
     */
    public fun widget(
        definitionId: String,
        factory: () -> NucleusWidget,
    ) {
        require(definitionId !in factories) { "Widget '$definitionId' is declared twice" }
        factories[definitionId] = factory
    }
}

/** A widget instance pinned in the widget host. */
public data class PinnedWidget(
    val id: WidgetId,
    val definitionId: String,
    val size: WidgetSize,
    val isActive: Boolean,
)

/** Something the widget host or the runtime did, for logs and diagnostics. */
public sealed interface WidgetEvent {
    /** The widget concerned, when there is one. */
    public val id: WidgetId?

    /** When it happened (Unix epoch, ms): replayed events keep their original time. */
    public val epochMillis: Long

    /** The user pinned a widget. */
    public data class Pinned(
        override val id: WidgetId,
        val definitionId: String,
        val size: WidgetSize,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The user removed a pinned widget. */
    public data class Unpinned(
        override val id: WidgetId,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The widget became visible in the host. */
    public data class Activated(
        override val id: WidgetId,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The widget is no longer visible in the host. */
    public data class Deactivated(
        override val id: WidgetId,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The user resized the widget. */
    public data class Resized(
        override val id: WidgetId,
        val size: WidgetSize,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The user asked for the widget's customization UI (Windows: *Customize widget*). */
    public data class CustomizationRequested(
        override val id: WidgetId,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The user clicked an element that runs app code. */
    public data class ActionInvoked(
        override val id: WidgetId,
        val key: String,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /**
     * A click was not run because the card it came from is older than the widget state (the app
     * changed it meanwhile): its input values were kept and the widget rendered again.
     */
    public data class ActionDropped(
        override val id: WidgetId,
        val key: String,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The widget was composed and handed to the host. */
    public data class Rendered(
        override val id: WidgetId,
        val millis: Long,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** The host reported an error about the widget (e.g. content it cannot display). */
    public data class HostError(
        override val id: WidgetId?,
        val details: String,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent

    /** Rendering, an action or a host call failed. */
    public data class Failed(
        override val id: WidgetId?,
        val message: String,
        val cause: Throwable?,
        override val epochMillis: Long = currentEpochMillis(),
    ) : WidgetEvent
}

/** The app's widgets: declaration, host state and events. */
public object NucleusWidgets {
    @Volatile
    internal var host: WidgetHost? = null
        private set

    @Volatile
    internal var factories: Map<String, () -> NucleusWidget> = emptyMap()
        private set
    private var classesById: Map<String, kotlin.reflect.KClass<out NucleusWidget>> = emptyMap()

    private val eventFlow = MutableSharedFlow<WidgetEvent>(replay = 64, extraBufferCapacity = 64)

    /** Host callbacks, renders and failures, the latest 64 replayed to new collectors. */
    public val events: SharedFlow<WidgetEvent> = eventFlow.asSharedFlow()

    /**
     * Whether the widget host started this process (COM on Windows, the widget extension on macOS)
     * rather than the user: such a launch may not want to open the main window.
     */
    public val isLaunchedByWidgetHost: Boolean get() = host?.isLaunchedByHost ?: false

    /** Whether a widget host is running for this app (the packaged app on Windows 11 or macOS 14+). */
    public val isAvailable: Boolean get() = host != null

    /**
     * Declares the app's widgets and connects to the platform's widget host. Only the first call
     * counts, so it is safe from a composable body; prefer `widgets { }` in `nucleusApplication`.
     */
    public fun start(declarations: WidgetsBuilder.() -> Unit) {
        if (factories.isNotEmpty() || host != null) return
        val declared = WidgetsBuilder().apply(declarations).factories
        if (declared.isEmpty()) return
        classesById = declared.mapValues { (_, factory) -> factory()::class }
        factories = declared
        val loaded = loadWidgetHost() ?: return
        try {
            loaded.start()
            host = loaded
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(WidgetEvent.Failed(null, "The widget host could not start: ${e.message}", e))
        }
    }

    /** The app's widgets pinned in the host, or an empty list when no host runs. */
    public suspend fun pinnedWidgets(): List<PinnedWidget> = host?.pinnedWidgets().orEmpty()

    internal fun definitionIdsOf(widget: NucleusWidget): Set<String> =
        classesById.filterValues { it == widget::class }.keys

    internal fun emit(event: WidgetEvent) {
        eventFlow.tryEmit(event)
    }
}

internal expect fun loadWidgetHost(): WidgetHost?

internal expect fun currentEpochMillis(): Long

/**
 * A platform's widget host (Windows widgets board, WidgetKit), loaded through `ServiceLoader` by
 * [NucleusWidgets.start]. Implementations drive [WidgetEngine].
 */
@InternalNucleusWidgetApi
public interface WidgetHost {
    /**
     * Whether this host can run here (right OS, packaged with an identity). Unsupported hosts are
     * skipped silently: a Windows host on a macOS build is not an error.
     */
    public val isSupported: Boolean

    /** Whether the host launched this process. */
    public val isLaunchedByHost: Boolean

    /** Connects to the OS widget host; throws when it cannot. */
    public fun start()

    /** The pinned instances of the app's widgets. */
    public suspend fun pinnedWidgets(): List<PinnedWidget>

    /** Renders [id] again and hands it to the host. */
    public suspend fun refresh(id: WidgetId)

    /** The persisted state of [id]. */
    public suspend fun loadState(id: WidgetId): MutableWidgetState

    /** Persists [state] for [id]. */
    public suspend fun saveState(
        id: WidgetId,
        state: WidgetState,
    )
}

/** What hosts call: rendering, actions and events, all serialized on one coroutine. */
@InternalNucleusWidgetApi
public object WidgetEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Single-threaded scope every host callback should run in, so renders never interleave. */
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Runs [block] on the engine's thread, like the host callbacks (no hop when already there). */
    public suspend fun <T> serialized(block: suspend () -> T): T = withContext(dispatcher) { block() }

    /** Whether [definitionId] was declared with `widgets { widget(definitionId) { … } }`. */
    public fun isDeclared(definitionId: String): Boolean = definitionId in NucleusWidgets.factories

    /** Composes [instance] from its state. */
    public suspend fun render(instance: WidgetInstance): WidgetRender {
        val factory =
            NucleusWidgets.factories[instance.definitionId]
                ?: error("Widget '${instance.definitionId}' is not declared in widgets { }")
        val start = TimeSource.Monotonic.markNow()
        val render = renderWidget(factory(), instance)
        emit(WidgetEvent.Rendered(instance.id, start.elapsedNow().inWholeMilliseconds))
        return render
    }

    /**
     * Runs the action [key] of [instance]. The widget is composed first from the state the host
     * holds, so the key resolves even if the process restarted since the card was drawn, and the
     * handler changes [WidgetInstance.state], which the host renders and persists next.
     * Returns `false` when the key matches no action (a stale card).
     */
    public suspend fun invokeAction(
        instance: WidgetInstance,
        key: String,
        inputs: Map<String, String> = emptyMap(),
    ): Boolean {
        emit(WidgetEvent.ActionInvoked(instance.id, key))
        applyInputs(instance, inputs)
        val action = render(instance).actions[key] ?: return false
        when (action) {
            is LambdaAction -> action.block()
            is RunCallbackAction -> {
                // updateWidgetState on this widget changes the action's own state, whatever the host.
                withContext(ActingInstance(instance)) { action.callback.onAction(instance.id, action.parameters) }
            }
            is OpenUrlAction -> Unit // opened by the host itself
        }
        return true
    }

    /**
     * Writes the input values a click submitted into [instance]'s state. Input ids are state
     * storage names (`s:city`), not positions, so they hold even when the action itself is dropped.
     */
    public fun applyInputs(
        instance: WidgetInstance,
        inputs: Map<String, String>,
    ) {
        for ((id, value) in inputs) {
            if (id.length > 2 && id[1] == ':') instance.state.values[id] = value
        }
    }

    /** The input values of an action's data (a flat JSON object): input id to value. */
    public fun parseInputs(data: String): Map<String, String> =
        if (data.isBlank()) emptyMap() else FlatJson.decode(data).orEmpty()

    /**
     * A new instance of the widget declared as [definitionId], or `null`: lets a host reach what a
     * widget implements for that platform alone (e.g. `WindowsWebWidget`).
     */
    public fun newWidget(definitionId: String): NucleusWidget? = NucleusWidgets.factories[definitionId]?.invoke()

    /** Reports [event] on [NucleusWidgets.events]. */
    public fun emit(event: WidgetEvent): Unit = NucleusWidgets.emit(event)
}

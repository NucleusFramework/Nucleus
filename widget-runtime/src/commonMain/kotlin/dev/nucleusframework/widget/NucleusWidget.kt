@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A desktop widget, declared like a Glance `GlanceAppWidget`:
 *
 * ```
 * private val Count = intStateKey("count")
 *
 * class CounterWidget : NucleusWidget() {
 *     override suspend fun provideWidget(id: WidgetId) {
 *         provideContent {
 *             val state = currentWidgetState()
 *             val count = state[Count] ?: 0
 *             Column {
 *                 Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge))
 *                 Button("+1", onClick = { state.update { it[Count] = count + 1 } })
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Register it with `widgets { widget("Counter") { CounterWidget() } }` inside `nucleusApplication`,
 * and declare the same id in the Gradle plugin's `windows { widgets { } }` so the package manifest
 * lists it.
 *
 * Each render composes [provideWidget]'s content once, from the widget's [WidgetState]: the widget
 * is a snapshot, not a live UI, so Compose `remember` / `mutableStateOf` do not survive between
 * renders. Keep everything that must last in the widget state.
 */
public abstract class NucleusWidget {
    /**
     * Produces the widget [id]'s content by calling [provideContent]. Suspend work (loading data)
     * goes before that call.
     */
    public abstract suspend fun provideWidget(id: WidgetId)

    /** Sets the content of the render in progress. Must be called once by [provideWidget]. */
    protected suspend fun provideContent(content: @Composable () -> Unit) {
        val session =
            coroutineContext[RenderSession]
                ?: error("provideContent must be called from provideWidget, during a widget render")
        session.content = content
    }

    /** Renders the pinned widget [id] again, e.g. after its data changed. No-op if it is not pinned. */
    public suspend fun update(id: WidgetId) {
        NucleusWidgets.host?.refresh(id)
    }

    /** Renders again every pinned instance of this widget. */
    public suspend fun updateAll() {
        val host = NucleusWidgets.host ?: return
        val definitions = NucleusWidgets.definitionIdsOf(this)
        for (pinned in host.pinnedWidgets()) {
            if (pinned.definitionId in definitions) host.refresh(pinned.id)
        }
    }
}

/**
 * Changes the state of the pinned widget [id] from anywhere in the app; call
 * [NucleusWidget.update] afterwards to show the change. From an [ActionCallback] of that same
 * widget, it changes the state the action runs on, and the widget is rendered with it right after.
 */
public suspend fun updateWidgetState(
    id: WidgetId,
    block: (MutableWidgetState) -> Unit,
) {
    WidgetEngine.acting?.takeIf { it.id == id }?.let {
        block(it.state)
        return
    }
    val host = NucleusWidgets.host ?: return
    val state = host.loadState(id)
    block(state)
    host.saveState(id, state)
}

/** The state of the pinned widget [id], like Glance's `getAppWidgetState`; empty when no host runs. */
public suspend fun getWidgetState(id: WidgetId): WidgetState = NucleusWidgets.host?.loadState(id) ?: WidgetState.Empty

/** The id of the widget being rendered. */
public val LocalWidgetId: androidx.compose.runtime.ProvidableCompositionLocal<WidgetId> =
    staticCompositionLocalOf { error("No widget is being rendered") }

/** The size the widget being rendered is pinned at. */
public val LocalSize: androidx.compose.runtime.ProvidableCompositionLocal<WidgetSize> =
    staticCompositionLocalOf { WidgetSize.Medium }

/**
 * The state of the widget being rendered, readable during composition and changeable from
 * click handlers. The widget is rendered again after every click, so changes show at once.
 */
public class WidgetStateHandle internal constructor(
    private val state: MutableWidgetState,
) {
    /** The value of [key], or `null`. */
    public operator fun <T : Any> get(key: WidgetStateKey<T>): T? = state[key]

    /** Changes the state. Call it from a click handler; it is persisted with the widget. */
    public fun update(block: (MutableWidgetState) -> Unit) {
        block(state)
    }

    /** Leaves the customization UI the host asked for (see [isCustomizing]). */
    public fun finishCustomization() {
        state.remove(CustomizingKey)
    }
}

/**
 * Whether the user asked for the widget's customization UI (on Windows, *Customize widget* in its
 * menu, offered when the plugin's `customizable = true`). Show the settings while it is `true`, and
 * leave with [actionFinishCustomization] or [WidgetStateHandle.finishCustomization]:
 *
 * ```
 * if (isCustomizing()) {
 *     TextField(CityKey, label = "City")
 *     Button("Done", onClick = actionFinishCustomization())
 * } else { … }
 * ```
 */
@Composable
public fun isCustomizing(): Boolean = LocalWidgetStateHandle.current[CustomizingKey] == true

internal val LocalWidgetStateHandle =
    staticCompositionLocalOf<WidgetStateHandle> { error("No widget is being rendered") }

/** The state of the widget being rendered. */
@Composable
public fun currentWidgetState(): WidgetStateHandle = LocalWidgetStateHandle.current

/** The value of [key] in the state of the widget being rendered, like Glance's `currentState(key)`. */
@Composable
public fun <T : Any> currentState(key: WidgetStateKey<T>): T? = LocalWidgetStateHandle.current[key]

internal class RenderSession : AbstractCoroutineContextElement(RenderSession) {
    var content: (@Composable () -> Unit)? = null

    companion object Key : CoroutineContext.Key<RenderSession>
}

/** One pinned instance, as the host knows it. */
@InternalNucleusWidgetApi
public class WidgetInstance(
    public val id: WidgetId,
    public val definitionId: String,
    public val size: WidgetSize,
    /** The live state: renders read it, click handlers change it, the host persists it. */
    public val state: MutableWidgetState,
)

/** A composed widget: the element tree for the host, and the actions its keys stand for. */
@InternalNucleusWidgetApi
public class WidgetRender internal constructor(
    public val instance: WidgetInstance,
    public val root: WidgetElement,
    internal val actions: Map<String, Action>,
)

internal suspend fun renderWidget(
    widget: NucleusWidget,
    instance: WidgetInstance,
): WidgetRender {
    val session = RenderSession()
    kotlinx.coroutines.withContext(session) { widget.provideWidget(instance.id) }
    val content = session.content ?: error("${widget::class.simpleName}.provideWidget did not call provideContent")
    val handle = WidgetStateHandle(instance.state)
    val (root, actions) =
        composeOnce(
            locals = { inner ->
                CompositionLocalProvider(
                    LocalWidgetId provides instance.id,
                    LocalSize provides instance.size,
                    LocalWidgetStateHandle provides handle,
                ) { inner() }
            },
            content = content,
        )
    return WidgetRender(instance, root, actions)
}

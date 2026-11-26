@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.android

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.widget.RemoteViews
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.MutableWidgetState
import dev.nucleusframework.widget.PinnedWidget
import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.WidgetHost
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android app widgets as a [WidgetHost]: each widget kind is a [NucleusWidgetReceiver] the app
 * declares in its manifest, and every render is turned into [RemoteViews] by [RemoteViewsRenderer].
 * A [WidgetId] is the `appWidgetId` Android gives each placed instance.
 *
 * Everything runs on [WidgetEngine.scope]: the receivers dispatch their broadcasts there, and
 * [refresh] / [loadState] are reached through the engine as well.
 */
internal class AndroidWidgetHost(
    context: Context,
) : WidgetHost {
    private val context = context.applicationContext
    private val manager: AppWidgetManager by lazy { AppWidgetManager.getInstance(this.context) }
    private val prefs: SharedPreferences by lazy { this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val states = ConcurrentHashMap<String, MutableWidgetState>()
    private val images by lazy { WidgetImages(this.context) }

    override val isSupported: Boolean get() = true

    // Android starts the app's process for a broadcast without any window: nothing to skip.
    override val isLaunchedByHost: Boolean get() = false

    override fun start() = Unit

    override suspend fun pinnedWidgets(): List<PinnedWidget> =
        receivers().flatMap { (component, definitionId) ->
            manager.getAppWidgetIds(component).map { appWidgetId ->
                PinnedWidget(WidgetId(appWidgetId.toString()), definitionId, sizeOf(appWidgetId), isActive = true)
            }
        }

    override suspend fun refresh(id: WidgetId) {
        val appWidgetId = id.value.toIntOrNull() ?: return
        val definitionId = definitionOf(appWidgetId) ?: return
        if (WidgetEngine.isDeclared(definitionId)) push(appWidgetId, definitionId)
    }

    override suspend fun loadState(id: WidgetId): MutableWidgetState =
        states.computeIfAbsent(id.value) { key ->
            val stored = prefs.getString(STATE + key, null)
            stored?.let { saved[key] = it }
            WidgetState.decode(stored)
        }

    /** What is on disk for each widget, so a render that changed nothing writes nothing. */
    private val saved = ConcurrentHashMap<String, String>()

    /**
     * Persists [state] before returning: `apply()` would leave the write pending, and a process
     * killed meanwhile (low memory, a crash) would come back with an older state than the one
     * the launcher shows. Runs on the engine's thread, never the main one.
     */
    override suspend fun saveState(
        id: WidgetId,
        state: WidgetState,
    ) {
        val encoded = state.encode()
        if (saved[id.value] == encoded) return
        if (prefs.edit().putString(STATE + id.value, encoded).commit()) {
            saved[id.value] = encoded
        } else {
            Log.w(TAG, "The state of widget $id could not be written")
        }
    }

    /**
     * Records which widget [component] draws: the app can then list and update its widgets from a
     * process no widget broadcast reached yet (a placed widget's receiver always ran once).
     */
    fun remember(
        component: ComponentName,
        definitionId: String,
    ) {
        val key = RECEIVER + component.className
        if (prefs.getString(key, null) != definitionId) prefs.edit().putString(key, definitionId).apply()
    }

    /** `APPWIDGET_UPDATE`: placed, the app updated, the device restarted, or the update period ran. */
    suspend fun update(
        definitionId: String,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val id = WidgetId(appWidgetId.toString())
            if (!prefs.contains(KNOWN + id.value)) {
                prefs.edit().putBoolean(KNOWN + id.value, true).apply()
                WidgetEngine.emit(WidgetEvent.Pinned(id, definitionId, sizeOf(appWidgetId)))
            }
            push(appWidgetId, definitionId)
        }
    }

    suspend fun resized(
        definitionId: String,
        appWidgetId: Int,
    ) {
        WidgetEngine.emit(WidgetEvent.Resized(WidgetId(appWidgetId.toString()), sizeOf(appWidgetId)))
        push(appWidgetId, definitionId)
    }

    // Like Windows and macOS, a removed widget takes its state with it.
    fun deleted(appWidgetIds: IntArray) {
        val edit = prefs.edit()
        for (appWidgetId in appWidgetIds) {
            val id = appWidgetId.toString()
            states.remove(id)
            saved.remove(id)
            edit.remove(STATE + id).remove(KNOWN + id)
            WidgetEngine.emit(WidgetEvent.Unpinned(WidgetId(id)))
        }
        edit.commit()
    }

    /**
     * A click from a widget: the input value it carries (a switch, a choice) is written, then its
     * action runs unless the render it came from is outdated and its key now names another element.
     * The widget is drawn again either way.
     */
    suspend fun click(intent: Intent) {
        val click = WidgetClick.parse(intent) ?: return
        val definitionId = definitionOf(click.appWidgetId) ?: return
        if (!WidgetEngine.isDeclared(definitionId)) return
        val id = WidgetId(click.appWidgetId.toString())
        val instance = WidgetInstance(id, definitionId, sizeOf(click.appWidgetId), loadState(id))
        click.input?.let { (input, value) -> WidgetEngine.applyInputs(instance, mapOf(input to value)) }
        if (click.key != null) {
            // A widget whose code throws still gets drawn again below: the failure is reported, and
            // the launcher never keeps a render whose clicks no longer match the state.
            guarded(id, "Action ${click.key}") {
                val current =
                    WidgetActionSignatures.of(WidgetEngine.render(instance).root)[click.key]?.let(WidgetClick::digest)
                if (click.signature != null && current != click.signature) {
                    WidgetEngine.emit(WidgetEvent.ActionDropped(id, click.key))
                } else if (!WidgetEngine.invokeAction(instance, click.key)) {
                    Log.d(TAG, "Stale action '${click.key}' on $id")
                }
            }
        }
        push(click.appWidgetId, definitionId)
    }

    /**
     * Composes [appWidgetId] from its state and hands the [RemoteViews] to the launcher. A widget
     * that fails to compose is replaced by a notice (like Glance's error layout) and reported, rather
     * than thrown at the code that asked for the update.
     */
    private suspend fun push(
        appWidgetId: Int,
        definitionId: String,
    ) {
        val id = WidgetId(appWidgetId.toString())
        val provider = manager.getAppWidgetInfo(appWidgetId)?.provider ?: return
        val state = loadState(id)
        val views =
            guarded(id, "Render") {
                val render = WidgetEngine.render(WidgetInstance(id, definitionId, sizeOf(appWidgetId), state))
                val renderer = RemoteViewsRenderer(context, appWidgetId, provider, images.load(render.root))
                renderer.render(render.root).also {
                    if (renderer.nesting > MAX_NESTING) {
                        val details = "The widget nests ${renderer.nesting} views deep, Android allows $MAX_NESTING"
                        WidgetEngine.emit(WidgetEvent.HostError(id, details))
                    }
                }
            } ?: noticeViews(context, "This widget could not be drawn.")
        // Persisted before it is shown: a process killed in between never leaves the launcher
        // showing a state the next click would not find.
        saveState(id, state)
        try {
            manager.updateAppWidget(appWidgetId, views)
        } catch (
            // Thrown back by the system when it refuses the views (e.g. a transaction too large).
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            Log.w(TAG, "The launcher refused widget $id", e)
            WidgetEngine.emit(WidgetEvent.HostError(id, "The widget could not be drawn: ${e.message}"))
        }
    }

    /** Runs [block], reporting an exception it throws as [WidgetEvent.Failed]; `null` then. */
    private suspend fun <T> guarded(
        id: WidgetId,
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "$what failed on widget $id", e)
            WidgetEngine.emit(WidgetEvent.Failed(id, "$what failed: ${e.message}", e))
            null
        }

    private fun receivers(): List<Pair<ComponentName, String>> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(RECEIVER) || value !is String) return@mapNotNull null
            ComponentName(context.packageName, key.removePrefix(RECEIVER)) to value
        }

    private fun definitionOf(appWidgetId: Int): String? =
        manager
            .getAppWidgetInfo(appWidgetId)
            ?.provider
            ?.takeIf { it.packageName == context.packageName }
            ?.let { prefs.getString(RECEIVER + it.className, null) }

    /**
     * The size class of [appWidgetId], from the size the launcher gives it in portrait: Small for a
     * narrow widget (a 2×2 cell square), Medium for a wide and short one, Large otherwise.
     */
    private fun sizeOf(appWidgetId: Int): WidgetSize {
        val options = manager.getAppWidgetOptions(appWidgetId)
        val info = manager.getAppWidgetInfo(appWidgetId)
        val density = context.resources.displayMetrics.density
        val width =
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }
                ?: info?.minWidth?.let { (it / density).toInt() } ?: 0
        val height =
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
                ?: info?.minHeight?.let { (it / density).toInt() } ?: 0
        return when {
            width < SMALL_MAX_WIDTH_DP -> WidgetSize.Small
            height < MEDIUM_MAX_HEIGHT_DP -> WidgetSize.Medium
            else -> WidgetSize.Large
        }
    }

    internal companion object {
        const val ACTION_CLICK = "dev.nucleusframework.widget.CLICK"
        private const val TAG = "NucleusWidgets"
        private const val PREFS = "dev.nucleusframework.widget"
        private const val STATE = "state:"
        private const val KNOWN = "known:"
        private const val RECEIVER = "receiver:"
        private const val SMALL_MAX_WIDTH_DP = 220
        private const val MEDIUM_MAX_HEIGHT_DP = 250
    }
}

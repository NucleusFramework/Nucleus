package dev.nucleusframework.widgetandroiddemo

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.WidgetEvent
import dev.nucleusframework.widget.getWidgetState
import dev.nucleusframework.widget.updateWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Buttons for what the app does to its widgets, scriptable for the E2E:
 * `adb shell am start -n dev.nucleusframework.widgetandroiddemo/.MainActivity --es action <pin-counter|pin-tasks|push|list>`,
 * results on logcat tag `WidgetDemo`.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                for (action in ACTIONS) {
                    addView(
                        Button(context).apply {
                            text = action
                            setOnClickListener { run(action) }
                        },
                    )
                }
            },
        )
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.data?.let { Log.i(TAG, "opened from a widget: $it") }
        intent.getStringExtra("action")?.let(::run)
        // Scripted runs leave the launcher on top, so its view tree is what dumpsys shows.
        if (intent.getBooleanExtra("finish", false)) finish()
    }

    private fun run(action: String) {
        scope.launch {
            when (action) {
                "pin-counter" -> pin(CounterWidgetReceiver::class.java)
                "pin-tasks" -> pin(TasksWidgetReceiver::class.java)
                "pin-fuzz" -> pin(FuzzWidgetReceiver::class.java)
                "fuzz" -> fuzz(intent.getLongExtra("seed", 0L), intent.getIntExtra("count", 1))
                // The app changes a widget's state and redraws it, without any widget broadcast.
                "push" ->
                    for (widget in NucleusWidgets.pinnedWidgets().filter { it.definitionId == COUNTER_WIDGET }) {
                        updateWidgetState(widget.id) {
                            it[CountKey] = (it[CountKey] ?: 0) + 100
                            it[NoteKey] = "+100 from the app"
                        }
                        CounterWidget().update(widget.id)
                        Log.i(TAG, "pushed ${widget.id}")
                    }
                "list" -> Log.i(TAG, "pinned ${NucleusWidgets.pinnedWidgets()}")
                "storm" -> storm(intent.getIntExtra("count", 200), intent.getIntExtra("workers", 8))
                "state" ->
                    for (widget in NucleusWidgets.pinnedWidgets().filter { it.definitionId == FUZZ_WIDGET }) {
                        val state = getWidgetState(widget.id)
                        Log.i(TAG, "STATE ${widget.id} clicks=${state[FuzzClicksKey]} storm=${state[FuzzStormKey]}")
                    }
            }
        }
    }

    /** Renders [count] seeds from [first] on every Fuzz widget, logging each spec's oracle. */
    private suspend fun fuzz(
        first: Long,
        count: Int,
    ) {
        val widgets = NucleusWidgets.pinnedWidgets().filter { it.definitionId == FUZZ_WIDGET }
        for (seed in first until first + count) {
            val spec = FuzzSpec.generate(seed)
            val start = System.currentTimeMillis()
            for (widget in widgets) {
                updateWidgetState(widget.id) { it[FuzzSeedKey] = seed }
                FuzzWidget().update(widget.id)
            }
            // The events this render raised, from the replay: logged with the oracle, not later.
            val errors =
                NucleusWidgets.events.replayCache
                    .filter { it.epochMillis >= start && (it is WidgetEvent.HostError || it is WidgetEvent.Failed) }
            Log.i(
                TAG,
                "FUZZ seed=$seed depth=${spec.depth()} invalid=${spec.invalid()} expect=${spec.expected()} errors=$errors",
            )
        }
    }

    /**
     * [count] increments of the storm counter from [workers] concurrent coroutines, with redraws
     * of every Fuzz widget in between: no increment may be lost, whatever the clicks meanwhile.
     */
    private suspend fun storm(
        count: Int,
        workers: Int,
    ) {
        val widgets = NucleusWidgets.pinnedWidgets().filter { it.definitionId == FUZZ_WIDGET }
        val start = System.currentTimeMillis()
        // A reader copying the state while the writers run: it must never see a torn map.
        var readerFailures = 0
        var reads = 0
        withContext(Dispatchers.Default) {
            val reader =
                launch {
                    while (true) {
                        for (widget in widgets) {
                            try {
                                getWidgetState(widget.id).toMutable()
                                reads++
                            } catch (e: ConcurrentModificationException) {
                                readerFailures++
                            }
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            (0 until workers)
                .map { worker ->
                    launch {
                        for (i in worker until count step workers) {
                            for (widget in widgets) {
                                updateWidgetState(widget.id) { it[FuzzStormKey] = (it[FuzzStormKey] ?: 0) + 1 }
                                if (i % 3 == 0) FuzzWidget().update(widget.id)
                            }
                            if (i % 10 == 0) FuzzWidget().updateAll()
                        }
                    }
                }.joinAll()
            reader.cancel()
        }
        Log.i(TAG, "STORM reader reads=$reads concurrentModifications=$readerFailures")
        for (widget in widgets) {
            val state = getWidgetState(widget.id)
            Log.i(
                TAG,
                "STORM done ${widget.id} storm=${state[FuzzStormKey]} clicks=${state[FuzzClicksKey]} in ${System.currentTimeMillis() - start} ms",
            )
        }
    }

    private fun pin(receiver: Class<*>) {
        val manager = AppWidgetManager.getInstance(this)
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported
        Log.i(TAG, "pin ${receiver.simpleName} supported=$supported")
        if (supported &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            manager.requestPinAppWidget(ComponentName(this, receiver), null, null)
        }
    }

    private companion object {
        val ACTIONS = listOf("pin-counter", "pin-tasks", "push", "list")
    }
}

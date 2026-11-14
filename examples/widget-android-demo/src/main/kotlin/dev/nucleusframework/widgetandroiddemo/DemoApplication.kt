package dev.nucleusframework.widgetandroiddemo

import android.app.Application
import android.util.Log
import dev.nucleusframework.widget.NucleusWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val TAG = "WidgetDemo"

/** Declares the widgets before any receiver runs, and logs every widget event for the E2E. */
class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NucleusWidgets.start {
            widget(COUNTER_WIDGET) { CounterWidget() }
            widget(TASKS_WIDGET) { TasksWidget() }
            widget(FUZZ_WIDGET) { FuzzWidget() }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            NucleusWidgets.events.collect { event -> Log.i(TAG, "event $event") }
        }
        Log.i(TAG, "widgets declared, host available=${NucleusWidgets.isAvailable}")
    }
}

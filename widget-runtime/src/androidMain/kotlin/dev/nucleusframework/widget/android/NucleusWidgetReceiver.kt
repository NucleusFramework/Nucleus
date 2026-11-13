@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget.android

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.nucleusframework.widget.ExperimentalNucleusWidgetApi
import dev.nucleusframework.widget.InternalNucleusWidgetApi
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetEvent
import kotlinx.coroutines.launch

/**
 * The Android side of a widget declared with `NucleusWidgets.start { widget(definitionId) { … } }`,
 * like Glance's `GlanceAppWidgetReceiver`. Android tells widget kinds apart by their receiver class,
 * so each widget needs one, empty:
 *
 * ```
 * class CounterWidgetReceiver : NucleusWidgetReceiver("Counter")
 * ```
 *
 * declared in the app's manifest with its `appwidget-provider` XML (give that XML
 * `android:initialLayout="@layout/nucleus_widget_root"`, the empty widget frame shown while the
 * app starts):
 *
 * ```xml
 * <receiver android:name=".CounterWidgetReceiver" android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
 *     </intent-filter>
 *     <meta-data android:name="android.appwidget.provider" android:resource="@xml/counter_widget" />
 * </receiver>
 * ```
 *
 * The widgets themselves are declared in `Application.onCreate`, which runs before any receiver,
 * also when Android starts the app for a widget alone:
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         NucleusWidgets.start { widget("Counter") { CounterWidget() } }
 *     }
 * }
 * ```
 */
@ExperimentalNucleusWidgetApi
public abstract class NucleusWidgetReceiver(
    private val definitionId: String,
) : AppWidgetProvider() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val host = host() ?: return
        host.remember(ComponentName(context, javaClass), definitionId)
        if (intent.action == AndroidWidgetHost.ACTION_CLICK) {
            async("click") { host.click(intent) }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val host = host() ?: return
        async("update") { host.update(definitionId, appWidgetIds) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val host = host() ?: return
        async("resize") { host.resized(definitionId, appWidgetId) }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        val host = host() ?: return
        async("removal") { host.deleted(appWidgetIds) }
    }

    private fun host(): AndroidWidgetHost? {
        // Declared in Application.onCreate, which runs before any receiver: a missing declaration
        // would stay missing, so say what is wrong instead of drawing nothing in silence.
        if (!WidgetEngine.isDeclared(definitionId)) {
            Log.e(
                TAG,
                "Widget '$definitionId' (${javaClass.name}) is not declared: call " +
                    "NucleusWidgets.start { widget(\"$definitionId\") { … } } in Application.onCreate",
            )
            return null
        }
        return NucleusWidgets.host as? AndroidWidgetHost
    }

    /** Runs [block] on the widget engine's thread, keeping the broadcast alive until it is done. */
    private fun async(
        what: String,
        block: suspend () -> Unit,
    ) {
        val pending = goAsync()
        WidgetEngine.scope.launch {
            try {
                block()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Widget $what failed", e)
                WidgetEngine.emit(WidgetEvent.Failed(null, "$what failed: ${e.message}", e))
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NucleusWidgets"
    }
}

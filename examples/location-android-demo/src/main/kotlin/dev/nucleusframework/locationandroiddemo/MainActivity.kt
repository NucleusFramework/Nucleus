package dev.nucleusframework.locationandroiddemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.minutes

/**
 * Walks the `location` API on a device: each button is one call, its result is appended to the
 * log on screen and to logcat (tag `LocationDemo`).
 *
 * Scriptable over adb, one action per launch: `adb shell am start -n
 * dev.nucleusframework.locationandroiddemo/.MainActivity --es action <action>`, where `<action>`
 * is one of `request-approximate`, `request-precise`, `request-background`, `fix`, `cached`,
 * `track`, `settings`.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this)
        val buttons =
            listOf(
                "Request approximate" to "request-approximate",
                "Request precise" to "request-precise",
                "Request background" to "request-background",
                "Current location" to "fix",
                "Cached location" to "cached",
                "Start / stop tracking" to "track",
                "Open settings" to "settings",
            )
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                buttons.forEach { (label, action) ->
                    addView(
                        Button(context).apply {
                            text = label
                            setOnClickListener { run(action) }
                        },
                    )
                }
                addView(output)
            }
        setContentView(ScrollView(this).apply { addView(column) })
        output.text = history

        current = WeakReference(this)
        if (savedInstanceState == null && authorizationWatch == null) {
            log("available: ${Geolocation.isAvailable}")
            authorizationWatch =
                scope.launch {
                    Geolocation.authorizationUpdates().collect { log("authorization: $it") }
                }
        }
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_ACTION)?.let(::run)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ACTION)?.let(::run)
    }

    override fun onDestroy() {
        if (current.get() === this) current = WeakReference(null)
        super.onDestroy()
    }

    private fun run(action: String) {
        scope.launch {
            when (action) {
                "request-approximate" -> request(LocationAccess.Foreground, LocationAccuracy.Approximate)
                "request-precise" -> request(LocationAccess.Foreground, LocationAccuracy.Precise)
                "request-background" -> request(LocationAccess.Background, LocationAccuracy.Precise)
                "fix" -> fix(cached = false)
                "cached" -> fix(cached = true)
                "track" -> toggleTracking()
                "settings" -> log("openSettings() → ${Geolocation.openSettings()}")
                else -> log("unknown action: $action")
            }
        }
    }

    private suspend fun request(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ) {
        log("requestAuthorization($access, $accuracy) …")
        log("requestAuthorization($access, $accuracy) → ${Geolocation.requestAuthorization(access, accuracy)}")
    }

    private suspend fun fix(cached: Boolean) {
        try {
            val location = Geolocation.currentLocation(maxAge = if (cached) 10.minutes else 0.minutes)
            log("currentLocation(cached=$cached) → $location")
        } catch (e: LocationException) {
            log("currentLocation(cached=$cached) failed: ${e.error} — ${e.message}")
        }
    }

    private fun toggleTracking() {
        tracking?.let {
            it.cancel()
            tracking = null
            log("tracking stopped")
            return
        }
        log("tracking started")
        tracking =
            scope.launch {
                Geolocation
                    .locationUpdates()
                    .catch { e -> log("locationUpdates failed: ${(e as? LocationException)?.error ?: e}") }
                    .collect { log("update: $it") }
            }
    }

    private companion object {
        const val TAG = "LocationDemo"
        const val EXTRA_ACTION = "action"

        // Process-wide, so a request survives the activity being recreated (the permission
        // dialog itself can cause a configuration change), as it would in a ViewModel.
        val scope = MainScope()
        var tracking: Job? = null
        var authorizationWatch: Job? = null
        val history = StringBuilder()
        var current = WeakReference<MainActivity>(null)

        fun log(line: String) {
            Log.i(TAG, line)
            history.append(line).append('\n')
            current.get()?.output?.text = history
        }
    }
}

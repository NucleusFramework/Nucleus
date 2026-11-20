package dev.nucleusframework.locationwebdemo

import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccuracy
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The same walk as `location-demo`, printed into the page. */
fun main() {
    MainScope().launch {
        log("available:     ${Geolocation.isAvailable}")
        log("authorization: ${Geolocation.authorization()}")
        log("requested:     ${Geolocation.requestAuthorization()}")
        runCatching { Geolocation.currentLocation(LocationAccuracy.Precise, maxAge = 10.minutes, timeout = 30.seconds) }
            .onSuccess { log("current:       $it") }
            .onFailure { log("current:       failed — $it") }
        runCatching { Geolocation.locationUpdates().take(3).collect { log("update:        $it") } }
            .onFailure { log("updates:       failed — $it") }
        log("done")
    }
}

private fun log(line: String) {
    println(line)
    appendLine(line)
}

private fun appendLine(line: String): Unit =
    js("{ var p = document.createElement('pre'); p.textContent = line; document.body.appendChild(p); }")

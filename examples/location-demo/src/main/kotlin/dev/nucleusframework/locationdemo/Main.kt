package dev.nucleusframework.locationdemo

import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccuracy
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Walks the whole `location` surface once against the real platform: availability,
 * authorization, a one-shot fix (cached accepted), then a few continuous updates.
 *
 * `-Daccuracy=approximate` asks for a city-level fix instead of a precise one.
 */
fun main() =
    runBlocking {
        val accuracy =
            if (System.getProperty("accuracy") ==
                "approximate"
            ) {
                LocationAccuracy.Approximate
            } else {
                LocationAccuracy.Precise
            }
        println("available:     ${Geolocation.isAvailable}")
        println("authorization: ${Geolocation.authorization()}")
        println("requested:     ${Geolocation.requestAuthorization(accuracy = accuracy)}")

        runCatching { Geolocation.currentLocation(accuracy, maxAge = 10.minutes, timeout = 30.seconds) }
            .onSuccess { println("current:       $it") }
            .onFailure { println("current:       failed — $it") }

        val updates =
            withTimeoutOrNull(30.seconds) {
                runCatching {
                    Geolocation.locationUpdates(accuracy, minInterval = 1.seconds).take(3).collect {
                        println("update:        $it")
                    }
                }.onFailure { println("updates:       failed — $it") }
            }
        if (updates == null) println("updates:       none within 30 s")
    }

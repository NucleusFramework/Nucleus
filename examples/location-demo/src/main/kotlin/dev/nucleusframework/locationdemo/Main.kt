package dev.nucleusframework.locationdemo

import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationException
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

        try {
            val here = Geolocation.currentLocation(accuracy, maxAge = 10.minutes, timeout = 30.seconds)
            println("current:       $here")
        } catch (e: LocationException) {
            println("current:       failed — $e")
        }

        // A device that does not move may report a single fix: Core Location only reports changes.
        var received = 0
        try {
            withTimeoutOrNull(30.seconds) {
                Geolocation.locationUpdates(accuracy, minInterval = 1.seconds).take(3).collect {
                    received++
                    println("update:        $it")
                }
            }
            println("updates:       $received within 30 s")
        } catch (e: LocationException) {
            println("updates:       failed — $e")
        }
    }

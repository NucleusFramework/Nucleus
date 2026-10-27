package dev.nucleusframework.lab.probes.system.location

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.location.Location
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class LocationViewModel(
    private val gateway: LocationGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<LocationState, LocationIntent, LocationEvent, Nothing>(
        LocationState(),
        LocationReducer,
        timeline,
        LocationProbe.ID,
    ) {
    private val provider get() = gateway.provider
    private var tracking: Job? = null

    init {
        // nucleus-lab://probe/system.location?accuracy=approximate&request=foreground&fix&cached&track
        onParams(commands) { params ->
            params.enum<LocationAccuracy>("accuracy")?.let { onIntent(LocationIntent.SetAccuracy(it)) }
            params.enum<LocationAccess>("request")?.let { onIntent(LocationIntent.RequestAuthorization(it)) }
            if (params.flag("fix")) onIntent(LocationIntent.CurrentLocation(cached = false))
            if (params.flag("cached")) onIntent(LocationIntent.CurrentLocation(cached = true))
            if (params.flag("track")) onIntent(LocationIntent.SetTracking(true))
            if (params.flag("settings")) onIntent(LocationIntent.OpenSettings)
        }
        launch {
            val available = io { gateway.availability() }
            dispatch(LocationEvent.Ready(available))
            if (!available.isAvailable) return@launch
            // Observed for the probe's whole life: a change made in the system settings must show
            // up here without any request.
            provider.authorizationUpdates().collect { authorization ->
                dispatch(LocationEvent.AuthorizationChanged(authorization))
                log("authorization → $authorization", LocationTone.Info)
            }
        }
    }

    override suspend fun handle(intent: LocationIntent) {
        when (intent) {
            is LocationIntent.SetAccuracy -> dispatch(LocationEvent.AccuracyChanged(intent.accuracy))
            is LocationIntent.RequestAuthorization -> {
                val (answer, millis) =
                    timedMillis {
                        provider.requestAuthorization(
                            intent.access,
                            state.value.accuracy,
                        )
                    }
                log("requestAuthorization(${intent.access}) → $answer in $millis ms", LocationTone.Ok)
            }
            is LocationIntent.CurrentLocation -> currentLocation(if (intent.cached) 10.minutes else Duration.ZERO)
            is LocationIntent.SetTracking -> if (intent.tracking) startTracking() else stopTracking()
            LocationIntent.OpenSettings -> {
                val opened = provider.openSettings()
                log("openSettings() → $opened", if (opened) LocationTone.Ok else LocationTone.Failed)
            }
            LocationIntent.Clear -> dispatch(LocationEvent.Cleared)
        }
    }

    private suspend fun currentLocation(maxAge: Duration) {
        val label = if (maxAge == Duration.ZERO) "fresh" else "cached ok"
        try {
            val (location, millis) = timedMillis { provider.currentLocation(state.value.accuracy, maxAge = maxAge) }
            dispatch(LocationEvent.Fixed(location, live = false))
            log("currentLocation($label) in $millis ms: ${location.describe()}", LocationTone.Ok)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocationException) {
            log("currentLocation($label) failed: ${e.error} — ${e.message}", LocationTone.Failed)
        }
    }

    private fun startTracking() {
        if (tracking != null) return
        dispatch(LocationEvent.TrackingChanged(true))
        tracking =
            launch {
                provider
                    .locationUpdates(state.value.accuracy)
                    .catch { e ->
                        log(
                            "locationUpdates failed: ${(e as? LocationException)?.error ?: e.summary}",
                            LocationTone.Failed,
                        )
                    }.collect { location ->
                        dispatch(LocationEvent.Fixed(location, live = true))
                        log("update: ${location.describe()}", LocationTone.Info)
                    }
                tracking = null
                dispatch(LocationEvent.TrackingChanged(false))
            }
    }

    private fun stopTracking() {
        tracking?.cancel()
        tracking = null
        dispatch(LocationEvent.TrackingChanged(false))
    }

    private fun log(
        text: String,
        tone: LocationTone,
    ) = dispatch(
        LocationEvent.Logged(LocationLine(System.currentTimeMillis(), text, tone)),
        if (tone == LocationTone.Failed) Severity.Error else Severity.Info,
    )

    override fun onCleared() {
        tracking?.cancel()
        super.onCleared()
    }
}

internal fun Location.describe(): String =
    buildString {
        append("%.5f, %.5f".format(Locale.ROOT, latitude, longitude))
        horizontalAccuracy?.let { append(" ±%.0f m".format(Locale.ROOT, it)) }
        if (isCached) append(" (cached)")
    }

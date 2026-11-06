package dev.nucleusframework.lab.probes.rendering.swiftui

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.lang.foreign.MemorySegment

/**
 * Owns the Swift handle while a NativeView embeds it. Every call reaches AppKit, so all of
 * them run on the UI thread (the Tao loop, which is the AppKit main thread on macOS).
 */
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SwiftUiViewModel(
    private val gateway: SwiftUiGateway,
    timeline: Timeline,
) : MviViewModel<SwiftUiState, SwiftUiIntent, SwiftUiEvent, Nothing>(
        SwiftUiState(),
        SwiftUiReducer,
        timeline,
        SwiftUiProbe.ID,
    ) {
    private var handle: MemorySegment? = null

    init {
        dispatch(SwiftUiEvent.AvailabilityRead(gateway.availability))
    }

    /** NativeView factory: allocates the Swift side and returns its `NSView*`. */
    fun attach(): Long {
        val created = gateway.create()
        handle = created
        val address = gateway.viewAddress(created)
        dispatch(SwiftUiEvent.ViewCreated(address))
        // A fresh view starts from the model's defaults: replay the current state into it.
        gateway.setCounter(created, state.value.counter)
        gateway.setHue(created, state.value.hue)
        return address
    }

    /** NativeView dispose: releases what [attach] allocated. */
    fun detach() {
        handle?.let(gateway::release)
        handle = null
        dispatch(SwiftUiEvent.ViewReleased)
    }

    override suspend fun handle(intent: SwiftUiIntent) {
        when (intent) {
            SwiftUiIntent.Increment -> setCounter(state.value.counter + 1)
            SwiftUiIntent.Decrement -> setCounter(state.value.counter - 1)
            SwiftUiIntent.Reset -> {
                setCounter(0)
                setHue(SwiftUiState.DEFAULT_HUE)
            }
            is SwiftUiIntent.SetHue -> setHue(intent.hue)
            SwiftUiIntent.ToggleEmbedded -> dispatch(SwiftUiEvent.EmbeddedToggled)
        }
    }

    private fun setCounter(value: Int) {
        dispatch(SwiftUiEvent.CounterChanged(value))
        handle?.let { gateway.setCounter(it, value) }
    }

    private fun setHue(hue: Float) {
        reduceSilently(SwiftUiEvent.HueChanged(hue))
        handle?.let { gateway.setHue(it, state.value.hue) }
    }

    override fun onCleared() {
        // The NativeView disposes first in practice; this covers a store cleared under it.
        handle?.let(gateway::release)
        handle = null
    }
}

package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge

/**
 * macOS headful helper (#660): delivers a synthetic magnify / rotate /
 * smart-magnify NSEvent through [NativeMetalBridge.nativeDiagInjectTrackpadGesture].
 * The event is queued with `NSApp.postEvent` (delivered once the current
 * loop callback returns, in posting order), so the local monitor in
 * `touchpad_gestures.m`, the Rust loop, `TaoWindow` and the scene host all
 * run exactly as for a real trackpad pinch.
 *
 * [Phase] values are the IOHID encodings `+[NSEvent eventWithCGEvent:]` maps
 * onto `NSEventPhase` — NOT the `NSEventPhase` bits themselves.
 */
internal object MacTrackpadGestureProbe {
    /** The `touchpad_gestures.m` wire. */
    object Kind {
        const val MAGNIFY: Int = 0
        const val ROTATE: Int = 1
        const val SMART_MAGNIFY: Int = 2
    }

    /** Gesture phase field encodings → `NSEvent.phase`. */
    object Phase {
        const val NONE: Int = 0
        const val BEGAN: Int = 1
        const val CHANGED: Int = 2
        const val ENDED: Int = 4
        const val CANCELLED: Int = 8
    }

    val available: Boolean get() = NativeMetalBridge.isLoaded

    /**
     * [x] / [y] are content-local points, top-left origin. [value] is the
     * magnification delta (`NSEvent.magnification`) or the rotation in degrees
     * (`NSEvent.rotation`, positive = counter-clockwise). Returns `false` when
     * injection is disabled or the window is gone.
     */
    @Suppress("LongParameterList")
    fun inject(
        window: TaoWindow,
        kind: Int,
        phase: Int,
        x: Float,
        y: Float,
        value: Double = 0.0,
    ): Boolean {
        val nsView = window.nativeHandle
        if (nsView == 0L) return false
        return NativeMetalBridge.nativeDiagInjectTrackpadGesture(nsView, kind, phase, x, y, value)
    }
}

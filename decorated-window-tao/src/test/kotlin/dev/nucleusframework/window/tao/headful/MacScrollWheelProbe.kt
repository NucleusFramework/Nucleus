package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge

/**
 * macOS headful helper: delivers a synthetic `scrollWheel:` NSEvent to the
 * tao content view of [TaoWindow] through
 * [NativeMetalBridge.nativeDiagInjectScrollWheel] — the same entry a real
 * trackpad or mouse wheel takes after the WindowServer, so tao's
 * `scroll_wheel`, the JNI loop and the Compose host all run for real.
 *
 * Deltas are raw AppKit `scrollingDelta*` values: points for a [precise]
 * (trackpad) event, lines for a wheel notch. AppKit's sign convention is
 * "positive = content moves down / right", i.e. a two-finger swipe *up* or
 * *left* (natural scrolling) is a negative delta. Compose / AWT use the
 * opposite sign; see `MacOsWheelDelta.kt`.
 *
 * [Phase] and [Momentum] are the IOHID field encodings that
 * `+[NSEvent eventWithCGEvent:]` maps onto `NSEventPhase` — NOT the
 * `NSEventPhase` bit values themselves.
 */
internal object MacScrollWheelProbe {
    /** `kCGScrollWheelEventScrollPhase` encodings → `NSEvent.phase`. */
    object Phase {
        const val NONE: Int = 0
        const val BEGAN: Int = 1
        const val CHANGED: Int = 2
        const val ENDED: Int = 4
        const val CANCELLED: Int = 8
        const val MAY_BEGIN: Int = 128
    }

    /** `kCGScrollWheelEventMomentumPhase` encodings → `NSEvent.momentumPhase`. */
    object Momentum {
        const val NONE: Int = 0
        const val BEGAN: Int = 1
        const val CHANGED: Int = 2
        const val ENDED: Int = 3
    }

    val available: Boolean get() = NativeMetalBridge.isLoaded

    /**
     * [x] / [y] are content-local points, top-left origin. Returns `false`
     * when the window's NSView or NSWindow is gone.
     */
    @Suppress("LongParameterList")
    fun inject(
        window: TaoWindow,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        precise: Boolean,
        phase: Int = Phase.NONE,
        momentum: Int = Momentum.NONE,
    ): Boolean {
        val nsView = window.nativeHandle
        if (nsView == 0L) return false
        return NativeMetalBridge.nativeDiagInjectScrollWheel(
            nsView,
            x,
            y,
            dx,
            dy,
            precise,
            phase,
            momentum,
        )
    }
}

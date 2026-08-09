package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge

/**
 * Headful e2e cases for the macOS window-level native state reported against
 * the #494 follow-up patch: real Tao window, real AppKit objects, live
 * assertions through the [NativeMetalBridge] diagnostics.
 */
internal object MacWindowChromeStateHeadfulCases {
    private val isMac: Boolean =
        System.getProperty("os.name", "").lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    fun all(): List<TaoWindowTestCase> =
        listOf(
            overlayDetachKeepsWindowChromeState(),
            setFocusableDoesNotLeakWindowRetains(),
        )

    /** Bit 0 = primary attachment associated object, bit 1 = FS observer. */
    private const val WINDOW_STATE_INTACT = 3

    /**
     * Detaching an overlay Metal attachment must not tear down the host
     * window's own native state. The driver replays the exact JNI sequence
     * `NativeViewOverlayController` runs when a `NativeView` is composed and
     * disposed (`nativeCreateOverlay` → `nativeAttachOverlay`, then
     * `nativeDetach` → `nativeReleaseOverlay`, all on the main thread) — an
     * unguarded detach wipes the window's primary-attachment associated
     * object and the fullscreen observer (the #327 machinery), killing
     * fullscreen transitions and `attachmentForWindow`-based features for
     * the rest of the window's life.
     *
     * Driven at the bridge level rather than through the `NativeView`
     * composable: the composable path can deadlock a mid-flight
     * `nativePresentWithInterop` (render thread `dispatch_sync` to main vs.
     * main blocked in `runOnRenderThread(...).get()` creating the overlay's
     * DirectContext) — a separate pre-existing race, not what this case
     * guards.
     */
    private fun overlayDetachKeepsWindowChromeState(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "overlay detach keeps main-window chrome state (#494 patch)",
            skip = { if (!isMac) "macOS only" else null },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val contentNsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            check(contentNsView != 0L) { "content NSView must be non-zero on a mapped window" }

            val before = NativeMetalBridge.nativeDiagWindowState(contentNsView)
            check(before == WINDOW_STATE_INTACT) {
                "window state must be intact before the overlay round-trip (diag=$before)"
            }

            // NativeViewOverlayController.attach()'s native half.
            val overlayNsView = NativeTaoMacOsNativeViewBridge.nativeCreateOverlay(contentNsView)
            check(overlayNsView != 0L) { "nativeCreateOverlay failed" }
            val attachment = NativeMetalBridge.nativeAttachOverlay(overlayNsView)
            check(attachment != 0L) { "nativeAttachOverlay failed" }
            settle()

            // NativeViewOverlayController.dispose()'s native half, same order.
            NativeMetalBridge.nativeDetach(attachment)
            NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(overlayNsView)
            settle()

            val after = NativeMetalBridge.nativeDiagWindowState(contentNsView)
            check(after == WINDOW_STATE_INTACT) {
                "overlay nativeDetach wiped main-window native state: diag=$after " +
                    "(bit0=primary attachment, bit1=fullscreen observer)"
            }
        }

    /**
     * `TaoWindow.setFocusable` funnels into the vendored tao's
     * `set_focusable`, which used to retain the NSWindow (+1 per call via
     * `clone()` + `Retained::into_raw`) and never release it — the window
     * could never deallocate after close. Asserted via the retain-count
     * delta across a burst of calls.
     */
    private fun setFocusableDoesNotLeakWindowRetains(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "setFocusable does not leak NSWindow retains (#494 patch)",
            skip = { if (!isMac) "macOS only" else null },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val nsView = window.nativeHandle
            check(nsView != 0L) { "nativeHandle must be non-zero on a mapped window" }

            val before = NativeMetalBridge.nativeDiagWindowRetainCount(nsView)
            check(before > 0) { "retain-count diagnostic unavailable (got $before)" }

            // `true` keeps behavior unchanged (the window is already
            // focusable); the leak was in the pointer plumbing, not the value.
            repeat(FOCUSABLE_CALLS) { window.setFocusable(true) }
            // setFocusable is queued through the tao user-event loop — let it
            // drain fully before measuring.
            settle()

            val after = NativeMetalBridge.nativeDiagWindowRetainCount(nsView)
            check(after > 0) { "retain-count diagnostic unavailable after calls (got $after)" }
            val delta = after - before
            check(delta < FOCUSABLE_CALLS / 2) {
                "set_focusable leaked ~$delta NSWindow retains over $FOCUSABLE_CALLS calls " +
                    "(before=$before after=$after) — Retained::into_raw without a matching release"
            }
        }

    private const val FOCUSABLE_CALLS = 40
}

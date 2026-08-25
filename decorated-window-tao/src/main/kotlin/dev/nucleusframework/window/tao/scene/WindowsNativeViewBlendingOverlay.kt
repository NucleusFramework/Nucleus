@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsOverlayBridge

/**
 * Full-client DirectComposition overlay that composites the host Compose
 * scene over embedded child HWNDs (Win32 children always paint above
 * their parent). [SetWindowRgn][flushRegions] is the union of live
 * NativeView rects: outside it the overlay has no presence, so title bar
 * / regular Compose keep hitting the owner HWND. Inside it the overlay
 * is HTCLIENT and Compose sees the event first.
 *
 * Threading: the Tao Windows event-loop thread.
 */
internal class WindowsNativeViewBlendingOverlay(
    private val host: Host,
) {
    internal interface Host {
        val hwnd: Long
        val widthPx: Int
        val heightPx: Int
        val popupRenderers: MutableMap<Any, () -> Unit>
        var hostContextDirtied: Boolean

        fun requestRedraw()

        fun renderBlendingFrame(overlayHandle: Long)

        fun onBlendingPointer(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        )

        fun onBlendingScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        )
    }

    private var overlay: Long = 0
    private val rendererToken: Any = Any()
    private var attachCount: Int = 0
    private val rects: MutableMap<Any, IntArray> = LinkedHashMap()
    private val callback = Callback()

    fun retain() {
        if (attachCount == 0) ensureOverlay()
        attachCount++
    }

    fun release() {
        attachCount--
        if (attachCount > 0) return
        attachCount = 0
        destroyOverlay()
    }

    fun setRect(
        token: Any,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        rects[token] = intArrayOf(xPx, yPx, widthPx, heightPx)
        flushRegions()
    }

    fun removeRect(token: Any) {
        rects.remove(token)
        flushRegions()
    }

    fun syncFrame() {
        val handle = overlay
        if (handle == 0L) return
        NativeTaoWindowsOverlayBridge.nativeSetOverlayFrame(
            handle,
            0,
            0,
            host.widthPx.coerceAtLeast(1),
            host.heightPx.coerceAtLeast(1),
        )
    }

    fun destroyOverlay() {
        val handle = overlay
        if (handle == 0L) return
        host.popupRenderers.remove(rendererToken)
        NativeTaoWindowsOverlayBridge.nativeSetOverlayCallback(handle, null)
        NativeTaoWindowsOverlayBridge.nativeReleaseOverlay(handle)
        overlay = 0
        rects.clear()
        host.hostContextDirtied = true
    }

    private fun ensureOverlay() {
        if (overlay != 0L) return
        if (host.hwnd == 0L || !NativeTaoWindowsOverlayBridge.isLoaded) return
        val created = NativeTaoWindowsOverlayBridge.nativeCreateOverlay(host.hwnd)
        if (created == 0L) return
        overlay = created
        NativeTaoWindowsOverlayBridge.nativeSetOverlayCallback(created, callback)
        NativeTaoWindowsOverlayBridge.nativeSetOverlayFrame(
            created,
            0,
            0,
            host.widthPx.coerceAtLeast(1),
            host.heightPx.coerceAtLeast(1),
        )
        host.popupRenderers[rendererToken] = {
            val handle = overlay
            if (handle != 0L) host.renderBlendingFrame(handle)
        }
        host.hostContextDirtied = true
        flushRegions()
        host.requestRedraw()
    }

    private fun flushRegions() {
        val handle = overlay
        if (handle == 0L) return
        val count = rects.size
        if (count == 0) {
            NativeTaoWindowsOverlayBridge.nativeSetOverlayRegions(handle, FloatArray(0), 0)
            return
        }
        val flat = FloatArray(count * 4)
        var i = 0
        for (r in rects.values) {
            flat[i] = r[0].toFloat()
            flat[i + 1] = r[1].toFloat()
            flat[i + 2] = r[2].toFloat()
            flat[i + 3] = r[3].toFloat()
            i += 4
        }
        NativeTaoWindowsOverlayBridge.nativeSetOverlayRegions(handle, flat, count)
    }

    /**
     * Named so GraalVM JNI reachability metadata can register the
     * implementor.
     */
    private inner class Callback : NativeTaoWindowsOverlayBridge.OverlayEventCallback {
        override fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) {
            host.onBlendingPointer(type, x, y, button, modifiers)
        }

        override fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        ) {
            host.onBlendingScroll(x, y, dx, dy)
        }
    }
}

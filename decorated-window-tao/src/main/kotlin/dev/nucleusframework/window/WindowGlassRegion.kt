package dev.nucleusframework.window

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalRequestedGlassBackground
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Kind of system pane rendered by [windowGlassRegion] — mapping directly to
 * the public `NSSplitViewItem` factories, whose panes AppKit backs with the
 * desktop-tinted system material (the wallpaper shows through; windows
 * behind never do — exactly like System Settings).
 *
 * Wire values match `TAO_GLASS_REGION_KIND_*` in NucleusTaoMetal.m — explicit
 * so reordering the enum entries cannot silently swap materials.
 */
public enum class WindowGlassRegionKind(
    internal val nativeValue: Int,
) {
    /** Leading sidebar pane — what System Settings' menu column uses. */
    Sidebar(0),

    /** Content-list pane (the middle column of a three-column layout). */
    ContentList(1),

    /**
     * Trailing inspector pane (macOS 14+; falls back to the content-list
     * material on older systems).
     */
    Inspector(2),
}

/**
 * Renders Apple's wallpaper-tinted system material behind this component
 * only — the rest of the window keeps its opaque background, exactly like
 * System Settings where only the sidebar column shows the desktop through.
 *
 * Implementation is the genuine Apple pattern: a real `NSSplitViewController`
 * with a [kind] pane (`NSSplitViewItem.sidebarWithViewController` & co.) is
 * hosted below the Compose surface, so AppKit applies the same
 * desktop-tinted backdrop as its own apps — the desktop wallpaper shows
 * through, intervening windows never do, and light/dark, inactive-window
 * desaturation, Space-specific wallpapers and the "Reduce transparency"
 * accessibility setting all behave natively. Public API only.
 *
 * The component itself must not paint an opaque background: whatever it
 * leaves unpainted shows the material. Siblings outside the region are
 * unaffected as long as they paint their own backgrounds (the Compose
 * surface is cleared to transparent while at least one region is active,
 * but the window itself stays opaque).
 *
 * The native pane follows this component's window-relative bounds across
 * layout changes and resizes. macOS only (Tao backend); a no-op elsewhere.
 */
public fun Modifier.windowGlassRegion(
    kind: WindowGlassRegionKind = WindowGlassRegionKind.Sidebar,
    cornerRadius: Dp = 0.dp,
): Modifier =
    composed {
        val window = LocalTaoWindow.current
        val glassState = LocalRequestedGlassBackground.current
        if (window == null ||
            Platform.Current != Platform.MacOS ||
            !NativeMetalBridge.isLoaded
        ) {
            return@composed Modifier
        }

        val density = LocalDensity.current
        var regionPtr by remember { mutableStateOf(0L) }
        // Last bounds seen by layout, and the last ones actually pushed. The
        // guard keeps the JNI traffic down during a resize; the effect below
        // covers the cases layout alone cannot signal.
        var bounds by remember { mutableStateOf<Rect?>(null) }
        var pushedBounds by remember { mutableStateOf<Rect?>(null) }

        fun push(rect: Rect) {
            val ptr = regionPtr
            if (ptr == 0L) return
            val scale = density.density
            NativeMetalBridge.nativeSetGlassRegionFrame(
                ptr,
                rect.left / scale,
                rect.top / scale,
                rect.width / scale,
                rect.height / scale,
                cornerRadius.value,
            )
            pushedBounds = rect
        }

        DisposableEffect(window, kind) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            if (nsView != 0L) {
                regionPtr = NativeMetalBridge.nativeAddGlassRegion(nsView, kind.nativeValue)
                if (regionPtr != 0L) WindowTransparencyMode.acquire(window, glassState)
            }
            onDispose {
                if (regionPtr != 0L) {
                    NativeMetalBridge.nativeRemoveGlassRegion(regionPtr)
                    regionPtr = 0L
                    WindowTransparencyMode.release(window, glassState)
                }
            }
        }

        // A re-created pane (a new [kind]) or a new [cornerRadius] must reach
        // native even when nothing moved: layout would not run again, so the
        // fresh region would keep NSZeroRect and stay invisible.
        LaunchedEffect(regionPtr, cornerRadius) {
            bounds?.let { push(it) }
        }

        // Pushed straight from layout rather than from an effect: the material
        // has to land in the same frame as the Compose bounds, or it visibly
        // trails the panel during a live resize.
        Modifier.onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            bounds = rect
            if (rect != pushedBounds) push(rect)
        }
    }

/**
 * Ref-counts the active glass regions of each window: the first one flips the
 * window into transparent mode (and the render loop into alpha-0 clears), the
 * last one to go restores the opaque themed background.
 *
 * This is safe to toggle repeatedly because the native side only ever makes
 * the `CAMetalLayer` non-opaque, never opaque again — flipping a live layer
 * back left stale opaque drawables and rendered the first frames black, which
 * is what an earlier latch-forever version worked around at the cost of
 * leaving windows stuck transparent.
 *
 * Runs on the Tao main thread only, so no synchronization is needed.
 */
internal object WindowTransparencyMode {
    private val counts = HashMap<Long, Int>()

    fun acquire(
        window: TaoWindow,
        glassState: MutableState<Boolean>?,
    ) {
        val handle = window.handle
        val next = (counts[handle] ?: 0) + 1
        counts[handle] = next
        if (next == 1) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(handle)
            if (nsView != 0L) NativeMetalBridge.nativeSetWindowTransparencyMode(nsView, true)
            glassState?.value = true
        }
    }

    fun release(
        window: TaoWindow,
        glassState: MutableState<Boolean>?,
    ) {
        val handle = window.handle
        val next = (counts[handle] ?: 1) - 1
        if (next > 0) {
            counts[handle] = next
            return
        }
        // Drop the entry rather than keeping a zero: handles are native
        // pointers and can be recycled by a later window.
        counts.remove(handle)
        val nsView = NativeTaoBridge.nativeNsViewHandle(handle)
        if (nsView != 0L) NativeMetalBridge.nativeSetWindowTransparencyMode(nsView, false)
        glassState?.value = false
    }
}

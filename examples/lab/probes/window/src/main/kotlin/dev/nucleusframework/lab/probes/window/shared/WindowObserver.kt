package dev.nucleusframework.lab.probes.window.shared

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedCallbackFlow
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

/** What the OS says about a window right now, read from [TaoWindow], never from the request. */
@Immutable
data class WindowSnapshot(
    /** Outer frame in physical screen pixels; `null` where the platform cannot tell. */
    val outerPx: IntRect?,
    val scale: Float,
    val maximized: Boolean,
    val minimized: Boolean,
    val fullscreen: Boolean,
    val tiled: Boolean,
    val focused: Boolean,
    val resizable: Boolean,
    /** `Wayland` / `X11` on Linux, `AppKit` on macOS, `Win32` on Windows. */
    val surface: String,
    val canPlaceOnScreen: Boolean,
    val monitor: String?,
    val workAreaPx: IntRect?,
) {
    /** One line for the timeline. */
    fun describe(): String =
        buildString {
            append(outerPx?.describe() ?: "bounds unknown")
            append(" @${scale}x")
            if (maximized) append(" maximized")
            if (minimized) append(" minimized")
            if (fullscreen) append(" fullscreen")
            if (tiled) append(" tiled")
            append(if (focused) " focused" else " unfocused")
            monitor?.let { append(" on $it") }
        }
}

fun IntRect.describe(): String = "$left,$top $width×$height"

/** Native window notifications a probe follows. */
sealed interface WindowSignal {
    val isGeometry: Boolean get() = false

    data class Moved(
        val x: Int,
        val y: Int,
    ) : WindowSignal {
        override val isGeometry: Boolean get() = true
    }

    data class Resized(
        val width: Int,
        val height: Int,
    ) : WindowSignal {
        override val isGeometry: Boolean get() = true
    }

    data class Focus(
        val focused: Boolean,
    ) : WindowSignal

    data class Minimized(
        val minimized: Boolean,
    ) : WindowSignal

    data object Destroyed : WindowSignal
}

/** A monitor as `TaoMonitors` reports it. */
@Immutable
data class MonitorRow(
    val id: String,
    val name: String,
    val boundsPx: IntRect,
    val workAreaPx: IntRect,
    val scale: Float,
    val primary: Boolean,
)

/** Port over the read side of [TaoWindow]: snapshots and its multi-cast listeners. */
interface WindowObserver {
    fun snapshot(window: TaoWindow): WindowSnapshot

    fun monitors(window: TaoWindow?): List<MonitorRow>

    /**
     * Native move / resize / focus / minimize / destroy notifications, stamped on the
     * thread they are delivered on. [TaoWindow] keeps its listeners for the window's
     * lifetime, so collect this once per window.
     */
    fun signals(window: TaoWindow): Flow<Stamped<WindowSignal>>
}

@ContributesBinding(AppScope::class)
@Inject
class TaoWindowObserver : WindowObserver {
    override fun snapshot(window: TaoWindow): WindowSnapshot {
        val outer =
            window.outerBoundsPx()?.takeIf { it.size >= 4 }?.let {
                val left = it[0].toInt()
                val top = it[1].toInt()
                IntRect(left, top, left + it[2].toInt(), top + it[3].toInt())
            }
        val monitor = runCatching { TaoMonitors.forWindow(window) }.getOrNull()
        return WindowSnapshot(
            outerPx = outer,
            scale = window.scaleFactor,
            maximized = window.isMaximized,
            minimized = window.isMinimized,
            fullscreen = window.isFullscreen,
            tiled = window.isTiled,
            focused = window.isFocused,
            resizable = window.isResizable,
            surface = surfaceOf(window),
            canPlaceOnScreen = window.canPlaceOnScreen,
            monitor = monitor?.name,
            workAreaPx = monitor?.workAreaPx,
        )
    }

    override fun monitors(window: TaoWindow?): List<MonitorRow> =
        runCatching { TaoMonitors.all(window) }.getOrDefault(emptyList()).map {
            MonitorRow(it.id, it.name, it.boundsPx, it.workAreaPx, it.scaleFactor, it.isPrimary)
        }

    override fun signals(window: TaoWindow): Flow<Stamped<WindowSignal>> =
        stampedCallbackFlow {
            // The listener lists have no public removal: gate them instead, they die with the window.
            val active = AtomicBoolean(true)

            fun send(signal: WindowSignal) {
                if (active.get()) emit(signal)
            }
            window.onMoved { x, y -> send(WindowSignal.Moved(x, y)) }
            window.onResized { w, h -> send(WindowSignal.Resized(w, h)) }
            window.onFocusChanged { send(WindowSignal.Focus(it)) }
            window.onMinimizedChanged { send(WindowSignal.Minimized(it)) }
            window.onDestroyed { send(WindowSignal.Destroyed) }
            onClose { active.set(false) }
        }
}

fun surfaceOf(window: TaoWindow): String =
    when (Platform.Current) {
        Platform.Linux -> if (window.isNativeWaylandSurface) "Wayland" else "X11"
        Platform.MacOS -> "AppKit"
        Platform.Windows -> "Win32"
        Platform.Unknown -> "unknown"
    }

/** Why programmatic placement does nothing here, if it does nothing. */
fun placementCaveat(snapshot: WindowSnapshot?): String? =
    when {
        snapshot == null -> null
        !snapshot.canPlaceOnScreen ->
            "native Wayland surface: the compositor places windows, position requests and screen coordinates are ignored"
        else -> null
    }

/** Where [alignment] puts a window of [outer] size inside [work], in physical px. */
fun expectedTopLeft(
    alignment: Alignment,
    outer: IntSize,
    work: IntRect,
): Pair<Int, Int> {
    val offset = alignment.align(outer, IntSize(work.width, work.height), LayoutDirection.Ltr)
    return (work.left + offset.x) to (work.top + offset.y)
}

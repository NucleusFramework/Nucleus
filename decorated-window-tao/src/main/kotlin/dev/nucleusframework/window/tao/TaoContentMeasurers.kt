package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-window hook into the live scene's `ComposeScene.measureContent`, the
 * real re-measure behind
 * [dev.nucleusframework.window.tao.v2.WindowGeometryProviderScope.measureWindowContent].
 *
 * Registered by the scene host for the window's lifetime and looked up by
 * handle — the same shape as [WindowSizePolicy], and for the same reason: the
 * window API must not grow a parameter for something only the host can do.
 * Calls run on the Tao main thread (the Compose dispatcher), where the scene
 * may be measured. Returns `null` while the window has no scene yet.
 */
internal typealias ContentMeasurer = (Constraints) -> IntSize?

private val contentMeasurers = ConcurrentHashMap<Long, ContentMeasurer>()

internal fun TaoWindow.installContentMeasurer(measurer: ContentMeasurer) {
    contentMeasurers[handle] = measurer
}

internal fun TaoWindow.clearContentMeasurer() {
    contentMeasurers.remove(handle)
}

internal fun TaoWindow.contentMeasurerOrNull(): ContentMeasurer? = contentMeasurers[handle]

package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.DirectContext

/*
 * Shared policy for the Skia GPU resource cache of the scene hosts'
 * `DirectContext`s.
 *
 * Skia evicts only when a new allocation would push the cache past its budget,
 * so a scene that stops drawing keeps its high-water mark for the rest of the
 * process' life. `DirectContext` offers no purge of its own — skiko exposes
 * `resourceCacheLimit` and nothing else: no `freeGpuResources`, no
 * `purgeUnlockedResources`, not even a usage read-back — so the only primitive
 * available to us is *toggling the limit*. Writing 0 runs Skia's
 * `purgeAsNeeded` inline, releasing every unlocked resource; writing the budget
 * back lets the next frame re-mint only what it actually needs.
 *
 * Two properties of that primitive shape every caller:
 *
 *  - It frees **unlocked** resources only. Compose layers and pictures still
 *    referenced by live Java objects keep their Skia natives locked, and those
 *    are released by the skiko `Cleaner` only after a GC — which is why the
 *    settle paths pair the purge with a `System.gc()` nudge, and why a purge
 *    alone never returns a drag's or an animation's full peak.
 *  - It issues backend deletes, so it must run where the context is usable:
 *    with *that* host's GL context current on the ANGLE/EGL hosts (purging
 *    against a sibling's binding deletes ids in the sibling's namespace — see
 *    the KDoc on `TaoComposeSceneHostWindows.purgeGpuResourceCache`), and on
 *    the owning render thread on Metal, where the context is thread-affine.
 */

/**
 * Budget written onto a host `DirectContext` at attach.
 *
 * Measured, not assumed: Ganesh already hands out exactly 268435456 bytes by
 * default, so at the current value this write is a deliberate no-op. It is the
 * explicit anchor the limit-toggle purge restores, and the single place to
 * change should we ever decide to run the hosts *below* Skia's own default
 * (which is the interesting question once several surfaces each own a context).
 * Do not read it as "the cache would be unbounded without this line".
 */
internal const val GPU_RESOURCE_CACHE_LIMIT_BYTES: Long = 256L * 1024 * 1024

/**
 * Gap between in-drag purges while resize events are streaming. Every frame of
 * a drag mints render-target scratch (stencil/attachments) at a size no later
 * frame reuses; purging periodically releases that accumulation mid-drag so the
 * peak stays bounded even on long drags, without skipping a resize frame (a
 * skipped frame is composited as a geometry/content mismatch — trembling).
 */
internal const val GPU_RESIZE_PURGE_INTERVAL_NS: Long = 250_000_000L

/**
 * The limit-toggle for a context that will not draw again soon: the window was
 * minimized (#638). Toggling queues the backend deletes, and inside a frame the
 * frame's own flush submits them; at rest no frame follows, so the deletes
 * would sit in the command stream and the driver would keep every byte — the
 * toggle alone was measured to return nothing on NVIDIA/GL. Flushing and
 * submitting synchronously is what actually hands the memory back.
 *
 * Same calling contract as every toggle: on the owning render thread on Metal,
 * with this context current on the ANGLE/EGL hosts.
 */
internal fun DirectContext.purgeAtRest() {
    resourceCacheLimit = 0
    resourceCacheLimit = GPU_RESOURCE_CACHE_LIMIT_BYTES
    flush()
    submit(true)
}

/*
 * There is deliberately no "settle" constant here. A drag-end purge needs a
 * drag-end signal, and only Windows has one (`WM_EXITSIZEMOVE`); standing a
 * timer in for it on the other hosts was measured to be a bad trade — see
 * `TaoComposeSceneHost.purgeResizeScratchIfDue`. The rest purge below is a
 * different trigger: it waits for frames to stop, not for sizes to.
 */

/**
 * How long a host draws nothing before [GpuCacheRestReclaimer] purges its cache.
 * Long enough that a pause between two interactions keeps the cache warm, short
 * enough that the high-water mark of an animation that has stopped does not stay
 * resident for the rest of the session (#638). The cost of being wrong is one
 * frame re-minting its offscreen scratch, not a re-raster of the scene.
 */
internal const val GPU_REST_PURGE_DELAY_MS: Long = 3_000L

/**
 * Reclaims a scene host's GPU resource cache once its window is at rest but
 * still on screen (#638): [GPU_REST_PURGE_DELAY_MS] after the last frame. Skia
 * evicts only to fit a new allocation, so without this the cache an animation
 * filled stays resident for as long as the window is still. Minimize, the
 * other at-rest moment, purges at once through the hosts' `onMinimizedChanged`;
 * both go through [purgeAtRest].
 *
 * The [purge] runs on the Tao main thread, where every host renders or records
 * its frames; it returns `false` when the context cannot be purged right now
 * (Linux: a swap still holds it), and the reclaimer tries again one delay later.
 * A context with a live [dev.nucleusframework.window.tao.TaoGpuRenderContextConsumers]
 * consumer is left alone by every host's [purge] — an external renderer may be
 * holding resources across frames of its own.
 *
 * One timer per rest period, not one per frame: [onFrame] only stamps the time
 * while a timer is pending, and the timer re-arms itself until the stamp is old
 * enough.
 */
internal class GpuCacheRestReclaimer(
    private val purge: () -> Boolean,
) {
    private val scope = CoroutineScope(TaoMainDispatcher + SupervisorJob())

    @Volatile
    private var lastFrameNs: Long = 0L

    /** Main thread only. */
    private var pending: Job? = null

    /** A frame was drawn: restart the rest period. Main thread. */
    fun onFrame() {
        lastFrameNs = System.nanoTime()
        if (pending?.isActive == true) return
        pending =
            scope.launch {
                while (true) {
                    val leftNs = lastFrameNs + GPU_REST_PURGE_DELAY_MS * NANOS_PER_MILLI - System.nanoTime()
                    if (leftNs > 0L) {
                        delay(leftNs / NANOS_PER_MILLI + 1)
                    } else if (purge()) {
                        break
                    } else {
                        lastFrameNs = System.nanoTime()
                    }
                }
            }
    }

    /** The host is going away. */
    fun close() {
        scope.cancel()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

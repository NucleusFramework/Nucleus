package dev.nucleusframework.window.tao.scene

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
 * Quiet period after the last resize event, standing in for a drag-end signal
 * on backends that have none. Windows is told exactly when the drag ends
 * (`WM_EXITSIZEMOVE`); AppKit's `viewDidEndLiveResize` is not bridged through
 * the Metal helper, so the macOS host settles on a timer instead. Long enough
 * that a human pausing mid-drag rarely pays the re-raster of a full purge,
 * short enough that the drag's dead scratch does not stay resident.
 */
internal const val GPU_RESIZE_SETTLE_MS: Long = 500L

/**
 * Resize events a burst must have carried before its settle is allowed to nudge
 * a `System.gc()`. A border drag streams dozens; a zoom, a snap, a display hop
 * or a programmatic resize streams one or two, and those must not each buy a
 * stop-the-world collection. Only the hosts without a real drag-end signal need
 * this — Windows is told when the drag ends and can nudge unconditionally.
 */
internal const val GPU_RESIZE_GC_MIN_EVENTS: Int = 8

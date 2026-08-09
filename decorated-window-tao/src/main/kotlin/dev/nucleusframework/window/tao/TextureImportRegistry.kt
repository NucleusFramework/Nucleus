package dev.nucleusframework.window.tao

import androidx.compose.runtime.RememberObserver
import org.jetbrains.skia.DirectContext

/**
 * Shares GPU imports between [TextureView]s: N composables showing the same
 * source on the same surface use one native import, one Skia image and — where
 * the backend needs one — one per-frame copy. The moral equivalent of Flutter's
 * texture registry, and the one copy of it for all three backends; only
 * [importTexture] and [closeImport] differ per platform.
 *
 * Keyed by the **Skia context**, not the surface: every surface owns its own
 * `DirectContext` (window scene, popup layer, tray panel, `NativeView` overlay)
 * and a GPU image belongs to exactly one of them. On Windows that is also the
 * only usable key — a tray panel has no HWND of its own, so an hwnd-keyed
 * registry handed it a window's image (hwnd 0 for both) and Skia silently
 * dropped the draw. The source completes the key by value: every
 * [TextureViewSource] is a data class, so two views built from the same
 * producer, size and layout share one entry.
 *
 * [I] is compared by identity (the imported types define no `equals`), which is
 * what makes the reverse map a plain refcount ledger rather than a value lookup.
 *
 * Event-loop / main thread only.
 */
internal class TextureImportRegistry<H : Any, I : Any>(
    private val contextOf: (H) -> DirectContext,
    private val importTexture: (H, TextureViewSource) -> I?,
    private val closeImport: (I) -> Unit,
) {
    private data class Key(
        val context: DirectContext,
        val source: TextureViewSource,
    )

    private class Entry<I : Any>(
        val imported: I,
    ) {
        var refCount: Int = 1
    }

    private val entries = HashMap<Key, Entry<I>>()
    private val keys = HashMap<I, Key>()

    /** Existing import for this (context, source) pair, or a fresh one; null when the import fails. */
    fun acquire(
        host: H,
        source: TextureViewSource,
    ): I? {
        val key = Key(contextOf(host), source)
        entries[key]?.let { entry ->
            entry.refCount++
            return entry.imported
        }
        val imported = importTexture(host, source) ?: return null
        entries[key] = Entry(imported)
        keys[imported] = key
        return imported
    }

    /** Drops one reference, closing the import when the last one goes. */
    fun release(imported: I) {
        val key = keys[imported] ?: return
        val entry = entries[key] ?: return
        entry.refCount--
        if (entry.refCount <= 0) {
            entries.remove(key)
            keys.remove(imported)
            closeImport(imported)
        }
    }

    /**
     * Drops every import made on [context] — called by a surface right before it
     * closes its `DirectContext`. Without this the imports would outlive their
     * context and their Skia images would be freed against a dead one. Leases
     * releasing later find the import already gone from the ledger.
     */
    fun closeAllFor(context: DirectContext) {
        val stale = entries.keys.filter { it.context == context }
        for (key in stale) {
            val entry = entries.remove(key) ?: continue
            keys.remove(entry.imported)
            closeImport(entry.imported)
        }
    }

    /**
     * Whether any live import targets [context] — i.e. the scene composites
     * at least one `TextureView` right now.
     */
    fun hasImportsFor(context: DirectContext): Boolean = entries.keys.any { it.context == context }
}

/**
 * Composition-lifetime holder of one [TextureImportRegistry] reference.
 *
 * A [RememberObserver] rather than a `DisposableEffect`: the reference is
 * released on `onForgotten` **and** `onAbandoned`, so a composition that
 * computes the `remember` block but is never applied cannot leak the native
 * import — a `DisposableEffect` would never run in that case.
 */
internal class TextureImportLease<H : Any, I : Any>(
    private val registry: TextureImportRegistry<H, I>,
    host: H,
    source: TextureViewSource,
) : RememberObserver {
    val imported: I? = registry.acquire(host, source)

    override fun onRemembered() {
        // The reference was already taken in the constructor.
    }

    override fun onForgotten() {
        imported?.let(registry::release)
    }

    override fun onAbandoned() {
        imported?.let(registry::release)
    }
}

/**
 * Newest frame stamp already consumed, per controller feeding one import — the
 * gate that keeps the backends with per-frame work (the Windows keyed-mutex
 * staging copy, the macOS snapshot) to exactly one pull per producer frame.
 *
 * Per controller, not one slot: an import is shared by every view on the same
 * source, but each view reads *its own* controller's stamp, so a single slot
 * would either re-pull once per view or let views with different controllers
 * (or none) invalidate each other on every draw pass.
 *
 * Weak keys: the import outlives any single view (it is refcounted across all of
 * them), so a strongly-keyed map would pin the controller of every view that ever
 * drew through it — a screen creating one controller per item over a shared
 * source would grow without bound. Losing an entry early only costs one
 * redundant pull. `null` keys are the views without a controller, which need a
 * single pull. Main thread only.
 */
internal class FrameStampGate {
    private val consumed = java.util.WeakHashMap<TextureViewController?, Long>()

    fun isPending(
        controller: TextureViewController?,
        stamp: Long,
    ): Boolean = consumed[controller] != stamp

    fun markConsumed(
        controller: TextureViewController?,
        stamp: Long,
    ) {
        consumed[controller] = stamp
    }

    fun clear() {
        consumed.clear()
    }
}

package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry

/**
 * `rememberSaveable` values saved by one host, with the composite key hash of
 * the [RelocatedContentHost] they were composed under ([anchor]).
 */
internal class RelocatedSavedState(
    val anchor: Long,
    val values: Map<String, List<Any?>>,
)

/**
 * The saveable state of one piece of content that moves between hosts — a
 * panel between its floating window and a dock, a tab between windows: what
 * the last host saved, and the registry of the host composing it right now.
 *
 * Owned by whatever identifies the content across the move (a workspace
 * entry), never by a host.
 */
internal class RelocatableSlot {
    /** What the previous host saved on its way out. */
    var savedState: RelocatedSavedState? = null

    /** The registry of the host currently composing the content, if any. */
    var activeRegistry: RelocatingSaveableStateRegistry? = null

    /** Everything known right now: the live registry's values, else what the last host saved. */
    fun snapshot(): RelocatedSavedState? = activeRegistry?.snapshot() ?: savedState
}

/**
 * Composes [content] under a saveable-state registry owned by [slot], so
 * `rememberSaveable` values follow the content from one host to the next.
 *
 * Two things make this more than a shared `SaveableStateHolder`:
 *
 *  - The hosts live in different compositions (two windows' scenes) whose
 *    dispose / compose order in the switching frame is not defined. The new
 *    host therefore pulls the live values straight out of the registry that
 *    is still mounted, falling back to the values the previous host saved on
 *    dispose — correct in both orders.
 *  - `rememberSaveable` keys are the composite key hash of the call site,
 *    which encodes the whole path from the root of the composition — and the
 *    path differs between hosts. [RelocatingSaveableStateRegistry] maps the
 *    keys across using the hash recorded here, see there.
 *
 * The relocation only holds if every group between this composable and the
 * content's own `rememberSaveable` call sites is identical in both hosts,
 * which is why [content] must be invoked from here and only from here —
 * never through a per-host wrapper lambda, whose group key would differ.
 *
 * @param scope the receiver [content] is composed with; the same instance in
 *   every host.
 * @param content the relocatable content, or `null` while it is not declared.
 */
@Composable
internal fun <S> RelocatedContentHost(
    slot: RelocatableSlot,
    scope: S,
    content: (@Composable S.() -> Unit)?,
) {
    val anchor: Long = currentCompositeKeyHashCode
    val registry =
        remember(slot) {
            RelocatingSaveableStateRegistry(slot.snapshot(), anchor).also { slot.activeRegistry = it }
        }
    DisposableEffect(registry) {
        onDispose {
            slot.savedState = registry.snapshot()
            if (slot.activeRegistry === registry) slot.activeRegistry = null
        }
    }
    if (content == null) return
    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        content(scope)
    }
}

/**
 * A [SaveableStateRegistry] that restores values saved under a *different*
 * composition path.
 *
 * Compose derives a `rememberSaveable` key from the composite key hash, built
 * top-down as `hash = (hash rol shift) xor segment` for every group entered,
 * and rendered in radix 36. For the same content composed below two anchors
 * `A` and `B`, a call site at the same relative position therefore hashes to
 * `kA` and `kB` with `kA xor kB == (A xor B) rol n` for some `n` (the shifts
 * accumulated on the way down). The hash is 64-bit on the JVM, so there are
 * at most 64 candidates for that rotation — [consumeRestored] matches a
 * requested key against the saved ones by testing exactly that, after trying
 * an exact match (same host, or explicit string keys) first.
 *
 * Only the linearity of the hash is relied on, not the shift constants or the
 * group structure, so the mapping is exact as long as the content composes the
 * same `rememberSaveable` call sites in both hosts, which
 * [RelocatedContentHost] guarantees by construction.
 */
internal class RelocatingSaveableStateRegistry(
    saved: RelocatedSavedState?,
    private val anchor: Long,
) : SaveableStateRegistry {
    /**
     * One registered provider. Several call sites can share a key — Compose
     * then stores a *list* per key and hands the values back in composition
     * order — so a slot keeps its position in that list for the lifetime of
     * the host, whether its provider is still registered or not.
     */
    private class Slot(
        var provider: (() -> Any?)?,
    ) {
        /** Value read out of [provider] when it unregistered. */
        var captured: Any? = null
    }

    private val slots = LinkedHashMap<String, MutableList<Slot>>()
    private val pending: MutableMap<String, MutableList<Any?>> =
        saved?.values.orEmpty().mapValuesTo(LinkedHashMap()) { (_, values) -> values.toMutableList() }
    private val rotations: Set<Long> =
        saved?.let { previous ->
            val delta = previous.anchor xor anchor
            (0 until Long.SIZE_BITS).mapTo(HashSet()) { delta.rotateLeft(it) }
        } ?: emptySet()

    override fun consumeRestored(key: String): Any? {
        val match = if (key in pending) key else relocatedKey(key) ?: return null
        val values = pending.getValue(match)
        val value = values.removeAt(0)
        if (values.isEmpty()) pending.remove(match)
        return value
    }

    private fun relocatedKey(key: String): String? {
        if (rotations.isEmpty()) return null
        val requested = key.toLongOrNull(KEY_RADIX) ?: return null
        return pending.keys.firstOrNull { candidate ->
            val saved = candidate.toLongOrNull(KEY_RADIX) ?: return@firstOrNull false
            (saved xor requested) in rotations
        }
    }

    override fun registerProvider(
        key: String,
        valueProvider: () -> Any?,
    ): SaveableStateRegistry.Entry {
        val keySlots = slots.getOrPut(key) { mutableListOf() }
        // Reuse a vacated slot before growing the list: a recomposing
        // `rememberSaveable` unregisters and registers again under the same
        // key, and must not shift the values of its neighbours.
        val slot =
            keySlots.firstOrNull { it.provider == null }?.apply { provider = valueProvider }
                ?: Slot(valueProvider).also { keySlots += it }
        return object : SaveableStateRegistry.Entry {
            override fun unregister() {
                slot.captured = slot.provider?.invoke()
                slot.provider = null
            }
        }
    }

    override fun canBeSaved(value: Any): Boolean = true

    /**
     * Every value this host knows, per key, in registration order.
     *
     * Order is the whole contract when several call sites share a key, and it
     * cannot be read off the providers still registered: when a host is
     * disposed Compose unregisters them in reverse composition order, and it
     * does so *before* the host's own disposable effect runs. Hence the slots,
     * which hold their position and keep the value their provider had on the
     * way out.
     *
     * Keys restored but never consumed are carried over, so content that
     * moves hosts twice before it composes keeps its state.
     */
    override fun performSave(): Map<String, List<Any?>> {
        val map = LinkedHashMap<String, List<Any?>>()
        for ((key, values) in pending) map[key] = values.toList()
        for ((key, keySlots) in slots) {
            map[key] = keySlots.map { slot -> slot.provider?.invoke() ?: slot.captured }
        }
        return map
    }

    /** Everything this host knows, tagged with its anchor. */
    fun snapshot(): RelocatedSavedState = RelocatedSavedState(anchor, performSave())

    private companion object {
        /** `rememberSaveable` renders the composite key hash in this radix. */
        const val KEY_RADIX = 36
    }
}

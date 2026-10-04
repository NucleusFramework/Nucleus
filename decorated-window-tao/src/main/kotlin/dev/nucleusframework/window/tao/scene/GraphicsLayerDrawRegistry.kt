package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.graphics.layer.NucleusGraphicsLayerHooks
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Who draws which graphics layer, for the partial redraw (#755).
 *
 * An explicit layer — `rememberGraphicsLayer()`, `drawWithCache { obtainGraphicsLayer() }`
 * — is drawn with `drawLayer` into whichever layer is recording at the time,
 * and its content is whatever it last recorded. The damage tracker can vouch
 * for it in the common shape: recorded and drawn by the same layer (a node
 * that records its own content and draws it, the screenshot pattern), whose
 * re-recording the tracker already sees. Recorded somewhere else — a
 * backdrop blur sampling a layer another node records — its change shows on
 * screen where no invalidated layer is, and the frame must repaint in full.
 *
 * Fed by the hooks the Nucleus plugin patches into `GraphicsLayer.record` /
 * `GraphicsLayer.draw` (`LayerDamageTransform`). Without them [available] is
 * false and the tracker falls back to counting live layers.
 *
 * Process-wide (the hooks are static) and called from whichever thread records,
 * hence the lock; in practice the render thread.
 */
internal object GraphicsLayerDrawRegistry {
    /** Whether the plugin's hooks are there and installed. */
    val available: Boolean = install()

    private val lock = Any()

    /** The layers each layer drew in its latest recording. */
    private val children = WeakHashMap<Any, MutableSet<Any>>()

    /** Per layer, how many times it recorded outside any other layer's recording. */
    private val outsideRecords = WeakHashMap<Any, IntArray>()

    /** Layers recorded inside another one's recording since the last [drainForeignRecords]: (layer, recording). */
    private val nestedRecords = ArrayList<Any>()
    private var nestedOverflow = false

    private val recording = ThreadLocal.withInitial { ArrayList<Any>() }

    private fun install(): Boolean =
        try {
            NucleusGraphicsLayerHooks.recordStart = Consumer { onRecordStart(it) }
            NucleusGraphicsLayerHooks.recordEnd = Consumer { onRecordEnd(it) }
            NucleusGraphicsLayerHooks.draw = BiConsumer { layer, parent -> onDraw(layer, parent) }
            true
        } catch (_: LinkageError) {
            false
        }

    private fun onRecordStart(layer: Any) {
        val stack = recording.get()
        val parent = stack.lastOrNull()
        stack += layer
        synchronized(lock) {
            children[layer]?.clear()
            if (parent == null) {
                outsideRecords.getOrPut(layer) { IntArray(1) }[0]++
            } else if (nestedRecords.size < MAX_NESTED_RECORDS) {
                nestedRecords += layer
                nestedRecords += parent
            } else {
                nestedOverflow = true
            }
        }
    }

    private fun onRecordEnd(layer: Any) {
        val stack = recording.get()
        if (stack.lastOrNull() === layer) stack.removeAt(stack.size - 1) else stack.remove(layer)
    }

    private fun onDraw(
        layer: Any,
        parent: Any?,
    ) {
        if (parent == null) return
        synchronized(lock) { children.getOrPut(parent) { newIdentitySet() }.add(layer) }
    }

    /**
     * Forgets this thread's recording stack. Between two frames nothing
     * records, so whatever is left came from a recording that threw.
     */
    fun resetThread() {
        recording.get().clear()
    }

    /** The layers [parent] drew in its latest recording, into [into]. */
    fun childrenOf(
        parent: Any,
        into: MutableCollection<Any>,
    ) {
        synchronized(lock) { children[parent]?.let { into.addAll(it) } }
    }

    /** How many times [layer] recorded outside any other recording. */
    fun outsideRecordCount(layer: Any): Int = synchronized(lock) { outsideRecords[layer]?.get(0) ?: 0 }

    /**
     * Whether a layer recorded inside another one's recording since the last
     * call without being drawn by that layer — a change the tracker could not
     * see. Called right after a scene draws, so the records are that draw's.
     */
    fun drainForeignRecords(): Boolean =
        synchronized(lock) {
            var foreign = nestedOverflow
            var i = 0
            while (!foreign && i < nestedRecords.size) {
                val drawnByRecorder = children[nestedRecords[i + 1]]?.contains(nestedRecords[i]) == true
                if (!drawnByRecorder) foreign = true
                i += 2
            }
            nestedRecords.clear()
            nestedOverflow = false
            foreign
        }

    private fun newIdentitySet(): MutableSet<Any> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** Past this many nested records between two drains, the draw is reported foreign. */
    private const val MAX_NESTED_RECORDS = 1 shl 16
}

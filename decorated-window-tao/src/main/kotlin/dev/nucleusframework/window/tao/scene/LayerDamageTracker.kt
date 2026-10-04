package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.SkiaGraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.TaoLayerTreeAccess
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.IntRect
import java.util.IdentityHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Finds the part of a scene that changed since the previous frame (#755) —
 * Android HWUI's `DamageAccumulator`, at the granularity Compose caches at:
 * the render layer.
 *
 * Every frame, between layout and draw, the tracker walks the scene's layer
 * tree and compares each layer with the previous frame: its content version
 * (bumped by Compose whenever the layer is invalidated or repainted — a
 * counter the Nucleus plugin patches in, see `LayerDamageTransform`), a
 * fingerprint of its graphics-layer properties, and its footprint in window
 * pixels. A layer that changed, appeared or went away damages its footprint
 * in both frames.
 *
 * The footprint is where the layer *can* paint, which is narrower than the
 * window only when the layer is self-contained: it clips to its bounds and
 * casts no shadow, applies no render effect and has no outline poking out.
 * Any other layer may draw anywhere its nearest clipping ancestor lets it, so
 * its footprint is that ancestor's clip (the window, at the top). Two cases
 * hand a whole subtree the footprint of its root:
 *  - a render effect (a blur) spreads every change inside it, and
 *  - a layer whose transform the layout coordinates do not reflect — an
 *    explicit layer placed with `placeWithLayer(layer)` and moved through its
 *    own properties — leaves every coordinate below it unreliable.
 *
 * Explicit layers — drawn with `drawLayer` rather than placed — are followed
 * through [GraphicsLayerDrawRegistry]: one recorded by the layer that draws it
 * changes with that layer; one recorded outside any draw, or given new
 * properties, damages where its drawers paint; one recorded by another layer
 * (a backdrop blur sampling content recorded elsewhere) makes the next frame
 * full — see [afterDraw].
 *
 * Anything the walk cannot vouch for answers `null`, "repaint everything":
 * Compose without the plugin patch, a layer kind other than
 * `GraphicsLayerOwnerLayer`, more than one owner (a canvas popup layer is
 * open), or — without the `GraphicsLayer` hooks — any graphics layer alive in
 * the owner's context that no coordinator places.
 *
 * Confined to the thread that renders the scene, which is also the one the
 * owner listener is called on.
 */
@OptIn(InternalComposeUiApi::class)
internal class LayerDamageTracker {
    private val owners = LinkedHashSet<SemanticsOwner>()

    /** The previous frame, by owned layer. Swapped with [current] after each walk. */
    private var previous = IdentityHashMap<Any, LayerState>()
    private var current = IdentityHashMap<Any, LayerState>()

    /** Whether [previous] describes the last frame the scene drew. */
    private var previousValid = false

    // Per-walk accumulators — fields rather than a context object, so a walk
    // allocates nothing but the layer states it keeps.
    private val damage = MutableIntRect()
    private var unknown = false
    private var unknownReason = ""
    private var allLayers = 0
    private var rootCoordinates: LayoutCoordinates? = null

    /** Explicit layers on screen in the previous frame, and the frame being walked. */
    private var explicitPrevious = IdentityHashMap<Any, ExplicitState>()
    private var explicitCurrent = IdentityHashMap<Any, ExplicitState>()

    /** Set after a draw that recorded a layer where the walk cannot see it change. */
    private var foreignRecord = false

    init {
        // Installs the GraphicsLayer hooks before the scene records anything.
        explicitTracking
    }

    /**
     * Why the last [frameDamage] answered `null`, for the
     * `nucleus.tao.partialRedraw.debug` log; `null` after a known damage.
     */
    var fullFrameReason: String? = null
        private set

    /**
     * Wraps [delegate] so the tracker learns which owners the scene draws.
     * Installed in the scene's [PlatformContext] by the bundle factories.
     */
    fun wrapListener(delegate: PlatformContext.SemanticsOwnerListener?): PlatformContext.SemanticsOwnerListener =
        object : PlatformContext.SemanticsOwnerListener {
            override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                owners += semanticsOwner
                previousValid = false
                delegate?.onSemanticsOwnerAppended(semanticsOwner)
            }

            override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                owners -= semanticsOwner
                previousValid = false
                delegate?.onSemanticsOwnerRemoved(semanticsOwner)
            }

            override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
                delegate?.onSemanticsChange(semanticsOwner)
            }

            override fun onLayoutChange(
                semanticsOwner: SemanticsOwner,
                semanticsNodeId: Int,
            ) {
                delegate?.onLayoutChange(semanticsOwner, semanticsNodeId)
            }
        }

    /**
     * Call right after the scene drew. Returns true when the draw recorded an
     * explicit layer somewhere else than in the layer that draws it: what is
     * on screen may be stale, so the caller schedules another frame — which
     * [frameDamage] then makes a full one.
     */
    fun afterDraw(): Boolean {
        if (!explicitTracking || !GraphicsLayerDrawRegistry.drainForeignRecords()) return false
        foreignRecord = true
        return true
    }

    /**
     * With `nucleus.tao.partialRedraw.debug`, what damaged the last frame
     * walked: one line per layer, with the area it damaged.
     */
    val damageSources: MutableList<Pair<IntRect, String>> = ArrayList()

    private fun note(
        area: IntRect,
        source: String,
    ) {
        damageSources += area to source
    }

    /** Forgets the previous frame, so the next [frameDamage] is a full one. */
    fun invalidate() {
        previousValid = false
    }

    /**
     * The window-pixel rectangle that changed since the previous call, clipped
     * to [sceneWidth] × [sceneHeight]; empty when nothing did, `null` when the
     * damage cannot be determined. Call between layout and draw, every frame
     * the scene draws — a frame drawn without it makes the next one full.
     */
    fun frameDamage(
        sceneWidth: Int,
        sceneHeight: Int,
    ): IntRect? {
        if (!supported) return full("Compose is not patched for partial redraw (run through the Nucleus plugin)")
        val owner = owners.singleOrNull()
        if (owner == null) {
            previousValid = false
            return full("${owners.size} owners in the scene (a canvas popup layer is open)")
        }
        GraphicsLayerDrawRegistry.resetThread()
        damageSources.clear()
        damage.setEmpty()
        unknown = false
        unknownReason = ""
        allLayers = 0
        current.clear()
        explicitCurrent.clear()
        if (foreignRecord) {
            foreignRecord = false
            markUnknown("an explicit layer was recorded by another layer than the one drawing it")
        }
        try {
            walkOwner(owner, IntRect(0, 0, sceneWidth, sceneHeight))
        } catch (e: LinkageError) {
            // A Compose release that moved a member the accessor reads, or a
            // classpath without the plugin patch. Logged once, then off.
            disable(e)
            return full("Compose is not patched for partial redraw (run through the Nucleus plugin)")
        }
        val wasValid = previousValid
        // Layers gone since the previous frame damage where they were.
        if (wasValid) {
            for ((layer, state) in previous) {
                if (!current.containsKey(layer)) damage.union(state.footprint)
            }
        }
        if (wasValid) {
            for ((layer, state) in explicitPrevious) {
                if (!explicitCurrent.containsKey(layer)) damage.union(state.area)
            }
        }
        val swap = previous
        previous = current
        current = swap
        val swapExplicit = explicitPrevious
        explicitPrevious = explicitCurrent
        explicitCurrent = swapExplicit
        previousValid = !unknown
        if (unknown) return full(unknownReason)
        if (!wasValid) return full("no previous frame to compare with")
        fullFrameReason = null
        return damage.toIntRect()
    }

    private fun walkOwner(
        owner: SemanticsOwner,
        sceneRect: IntRect,
    ) {
        val root = owner.rootSemanticsNode.layoutInfo
        rootCoordinates = root.coordinates
        walk(root, sceneRect, forced = null, drawn = root.isPlaced)
        rootCoordinates = null
        if (explicitTracking) {
            visitExplicitLayers()
            return
        }
        // Without the GraphicsLayer hooks, every layer the owner's context
        // created must be one the walk saw: anything else is drawn through
        // `drawLayer` from a place the walk cannot attribute.
        val context = TaoLayerTreeAccess.graphicsContext(root) as? SkiaGraphicsContext
        if (context == null || context.activeGraphicsLayersCount != allLayers) {
            markUnknown(
                "${context?.activeGraphicsLayersCount} graphics layers alive, $allLayers placed: " +
                    "an explicit layer is drawn with drawLayer",
            )
        }
    }

    /**
     * Visits [node]'s coordinators outer to inner, then its children.
     * [clip] is the device rectangle the node's ancestors clip it to; [forced]
     * is the footprint an ancestor imposes on the whole subtree (render effect
     * or untrusted transform). [drawn] is false below a node that is not placed:
     * its layers are counted, never damaged.
     */
    private fun walk(
        node: LayoutInfo,
        clip: IntRect,
        forced: IntRect?,
        drawn: Boolean,
    ) {
        val isDrawn = drawn && node.isPlaced
        var subtreeClip = clip
        var subtreeForced = forced
        val inner = TaoLayerTreeAccess.innerCoordinator(node)
        var coordinator: LayoutCoordinates? = TaoLayerTreeAccess.outerCoordinator(node)
        while (coordinator != null) {
            val owned = TaoLayerTreeAccess.ownedLayer(coordinator)
            if (owned != null) {
                allLayers++
                if (isDrawn) {
                    val below = visitLayer(coordinator, owned, subtreeClip, subtreeForced)
                    subtreeClip = below.clip
                    subtreeForced = below.forced
                }
            }
            if (coordinator === inner) break
            coordinator = TaoLayerTreeAccess.wrapped(coordinator)
        }
        val children = TaoLayerTreeAccess.children(node)
        for (i in children.indices) walk(children[i], subtreeClip, subtreeForced, isDrawn)
    }

    /**
     * Records the layer [owned] of [coordinator] drawn under [clip] /
     * [forced], and returns what the coordinators and nodes below it get.
     */
    private fun visitLayer(
        coordinator: LayoutCoordinates,
        owned: Any,
        clip: IntRect,
        forced: IntRect?,
    ): Subtree {
        val graphicsLayer = TaoLayerTreeAccess.graphicsLayer(owned)
        if (graphicsLayer == null || graphicsLayer.isReleased) {
            markUnknown("a layer of another kind (${owned.javaClass.simpleName})")
            return Subtree(clip, forced)
        }
        val rect = deviceBounds(coordinator)
        val trusted = isTransformTrusted(coordinator, graphicsLayer)
        val clipsContent = graphicsLayer.clip && outlineWithinBounds(graphicsLayer, coordinator)
        val selfContained = clipsContent && graphicsLayer.shadowElevation <= 0f && graphicsLayer.renderEffect == null
        val footprint =
            when {
                forced != null -> forced
                trusted && selfContained -> rect.intersectOrEmpty(clip)
                else -> clip
            }
        val why = if (PartialRedraw.debug) describe(rect, forced, trusted, selfContained, graphicsLayer) else null
        record(owned, graphicsLayer, footprint, why)
        return when {
            forced != null -> Subtree(clip, forced)
            !trusted || graphicsLayer.renderEffect != null -> Subtree(clip, clip)
            clipsContent -> Subtree(rect.intersectOrEmpty(clip), null)
            else -> Subtree(clip, null)
        }
    }

    /**
     * The explicit layers the scene's layers draw (see [GraphicsLayerDrawRegistry]):
     * one that changed without its drawer re-recording — recorded outside any
     * draw, or given new properties — damages everywhere its drawers can paint.
     */
    private fun visitExplicitLayers() {
        val owned = IdentityHashMap<Any, IntRect>(current.size)
        for (state in current.values) owned[state.graphicsLayer] = state.footprint
        val areas = IdentityHashMap<Any, IntRect>()
        var frontier: Map<Any, IntRect> = owned
        val children = ArrayList<Any>()
        var depth = 0
        while (frontier.isNotEmpty()) {
            if (depth++ == MAX_EXPLICIT_NESTING) {
                markUnknown("explicit layers nested deeper than $MAX_EXPLICIT_NESTING")
                return
            }
            val next = IdentityHashMap<Any, IntRect>()
            for ((parent, area) in frontier) {
                children.clear()
                GraphicsLayerDrawRegistry.childrenOf(parent, children)
                for (child in children) {
                    if (owned.containsKey(child)) continue
                    val known = areas[child]
                    val widened = known?.union(area) ?: area
                    if (widened != known) {
                        areas[child] = widened
                        next[child] = widened
                    }
                }
            }
            frontier = next
        }
        for ((layer, area) in areas) {
            val graphicsLayer = layer as? GraphicsLayer
            if (graphicsLayer != null && !graphicsLayer.isReleased) recordExplicit(graphicsLayer, area)
        }
    }

    private fun recordExplicit(
        graphicsLayer: GraphicsLayer,
        area: IntRect,
    ) {
        val outside = GraphicsLayerDrawRegistry.outsideRecordCount(graphicsLayer)
        val fingerprint = graphicsLayer.fingerprint()
        val state = explicitPrevious[graphicsLayer]
        val changed =
            state == null || state.outsideRecords != outside || state.fingerprint != fingerprint || state.area != area
        if (changed) {
            state?.let { damage.union(it.area) }
            damage.union(area)
            if (PartialRedraw.debug) {
                val size = graphicsLayer.size
                note(area, "explicit layer ${size.width}x${size.height} (drawn in its parents' area)")
            }
        }
        explicitCurrent[graphicsLayer] = ExplicitState(outside, fingerprint, area)
    }

    private class ExplicitState(
        val outsideRecords: Int,
        val fingerprint: Long,
        val area: IntRect,
    )

    /** `nucleus.tao.partialRedraw.debug`: what a layer's change damages, and why. */
    private fun describe(
        rect: IntRect,
        forced: IntRect?,
        trusted: Boolean,
        selfContained: Boolean,
        graphicsLayer: GraphicsLayer,
    ): String {
        val kind =
            when {
                forced != null -> "subtree of a blurred/untrusted layer"
                trusted && selfContained -> "own bounds"
                !trusted -> "untrusted transform"
                !graphicsLayer.clip -> "unclipped layer"
                graphicsLayer.shadowElevation > 0f -> "shadow"
                graphicsLayer.renderEffect != null -> "render effect"
                else -> "outline outside bounds"
            }
        return "layer ${rect.width}x${rect.height}@${rect.left},${rect.top} ($kind)"
    }

    /** What a layer hands the coordinators and nodes below it — see [walk]. */
    private class Subtree(
        val clip: IntRect,
        val forced: IntRect?,
    )

    private fun record(
        owned: Any,
        graphicsLayer: GraphicsLayer,
        footprint: IntRect,
        why: String?,
    ) {
        val version = TaoLayerTreeAccess.contentVersion(owned)
        if (version < 0) {
            markUnknown("a layer without a content version")
            return
        }
        val fingerprint = graphicsLayer.fingerprint()
        val state = previous[owned]
        current[owned] =
            if (state != null && state.graphicsLayer === graphicsLayer) {
                if (state.version != version || state.fingerprint != fingerprint || state.footprint != footprint) {
                    damage.union(state.footprint)
                    damage.union(footprint)
                    why?.let { note(footprint.union(state.footprint), it) }
                }
                state.version = version
                state.fingerprint = fingerprint
                state.footprint = footprint
                state
            } else {
                if (state != null) damage.union(state.footprint)
                damage.union(footprint)
                why?.let { note(footprint, "new $it") }
                LayerState(graphicsLayer, version, fingerprint, footprint)
            }
    }

    /** [coordinator]'s bounds in window pixels, rounded out with an anti-aliasing margin. */
    private fun deviceBounds(coordinator: LayoutCoordinates): IntRect {
        val root = rootCoordinates ?: return IntRect.Zero
        val bounds = root.localBoundingBoxOf(coordinator, clipBounds = false)
        return IntRect(
            floor(bounds.left).toInt() - AA_MARGIN_PX,
            floor(bounds.top).toInt() - AA_MARGIN_PX,
            ceil(bounds.right).toInt() + AA_MARGIN_PX,
            ceil(bounds.bottom).toInt() + AA_MARGIN_PX,
        )
    }

    private fun markUnknown(reason: String) {
        if (!unknown) unknownReason = reason
        unknown = true
    }

    private fun full(reason: String): IntRect? {
        fullFrameReason = reason
        return null
    }

    private fun disable(cause: Throwable) {
        if (supported) {
            supported = false
            logger.log(Level.FINE, "Partial redraw unavailable (Compose not patched or changed)", cause)
        }
        previousValid = false
    }

    private class LayerState(
        val graphicsLayer: GraphicsLayer,
        var version: Int,
        var fingerprint: Long,
        var footprint: IntRect,
    )

    private companion object {
        /** Explicit layers drawn into explicit layers, deeper than this, repaint in full. */
        const val MAX_EXPLICIT_NESTING = 8

        /** Pixels added around a layer's bounds for anti-aliased edges. */
        const val AA_MARGIN_PX = 2

        val logger: Logger = Logger.getLogger(LayerDamageTracker::class.java.name)

        /**
         * Whether explicit layers are followed through the GraphicsLayer hooks:
         * off with partial redraw, so `-Dnucleus.tao.partialRedraw=false`
         * leaves no hook installed.
         */
        val explicitTracking: Boolean by lazy { PartialRedraw.enabled && GraphicsLayerDrawRegistry.available }

        /** Cleared for the process once the patched classes turn out to be missing. */
        @Volatile
        var supported = true
    }
}

/** Tolerance, in pixels, for an outline to count as inside its layer's bounds. */
private const val OUTLINE_TOLERANCE_PX = 0.5f

/** Tolerance, in pixels, when checking coordinates against a layer's own transform. */
private const val TRANSFORM_TOLERANCE_PX = 0.5f

/**
 * Whether [coordinator]'s position relative to its parent is the one
 * [graphicsLayer]'s transform produces. Compose maps coordinates through the
 * owned layer's cached matrix, which only `graphicsLayer {}` updates: an
 * explicit layer moved through its own properties is drawn transformed while
 * every coordinate below it still reads the untransformed position.
 */
private fun isTransformTrusted(
    coordinator: LayoutCoordinates,
    graphicsLayer: GraphicsLayer,
): Boolean {
    val identity =
        graphicsLayer.translationX == 0f &&
            graphicsLayer.translationY == 0f &&
            graphicsLayer.scaleX == 1f &&
            graphicsLayer.scaleY == 1f &&
            graphicsLayer.rotationX == 0f &&
            graphicsLayer.rotationY == 0f &&
            graphicsLayer.rotationZ == 0f
    if (identity) return true
    // A 3D rotation needs the camera projection; not worth replicating.
    if (graphicsLayer.rotationX != 0f || graphicsLayer.rotationY != 0f) return false
    val parent = coordinator.parentCoordinates ?: return false
    val width = coordinator.size.width.toFloat()
    val height = coordinator.size.height.toFloat()
    val pivot =
        if (graphicsLayer.pivotOffset.isUnspecified) Offset(width / 2f, height / 2f) else graphicsLayer.pivotOffset
    val radians = Math.toRadians(graphicsLayer.rotationZ.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val topLeft = graphicsLayer.topLeft
    for (corner in arrayOf(Offset.Zero, Offset(width, height))) {
        val dx = (corner.x - pivot.x) * graphicsLayer.scaleX
        val dy = (corner.y - pivot.y) * graphicsLayer.scaleY
        val expectedX = dx * cos - dy * sin + pivot.x + graphicsLayer.translationX + topLeft.x
        val expectedY = dx * sin + dy * cos + pivot.y + graphicsLayer.translationY + topLeft.y
        val actual = parent.localPositionOf(coordinator, corner)
        if (abs(actual.x - expectedX) > TRANSFORM_TOLERANCE_PX || abs(actual.y - expectedY) > TRANSFORM_TOLERANCE_PX) {
            return false
        }
    }
    return true
}

/** Whether [graphicsLayer]'s outline — which is what its clip follows — stays inside the layer's bounds. */
private fun outlineWithinBounds(
    graphicsLayer: GraphicsLayer,
    coordinator: LayoutCoordinates,
): Boolean {
    val bounds: Rect =
        when (val outline = graphicsLayer.outline) {
            is Outline.Rectangle -> outline.rect
            is Outline.Rounded -> outline.roundRect.let { Rect(it.left, it.top, it.right, it.bottom) }
            is Outline.Generic -> outline.path.getBounds()
        }
    val size = coordinator.size
    return bounds.left >= -OUTLINE_TOLERANCE_PX &&
        bounds.top >= -OUTLINE_TOLERANCE_PX &&
        bounds.right <= size.width + OUTLINE_TOLERANCE_PX &&
        bounds.bottom <= size.height + OUTLINE_TOLERANCE_PX
}

/**
 * Hash of every [GraphicsLayer] property that changes how the layer is
 * composited. Compose-managed layers already bump their content version when
 * one changes; the hash is for explicit layers, whose properties an app sets
 * directly.
 */
@Suppress("MagicNumber") // hash seeds and bit packing
private fun GraphicsLayer.fingerprint(): Long {
    var h = 1125899906842597L

    fun mix(value: Long) {
        h = 31 * h + value
    }

    fun mix(value: Float) = mix(value.toRawBits().toLong())
    mix(alpha)
    mix(translationX)
    mix(translationY)
    mix(scaleX)
    mix(scaleY)
    mix(rotationX)
    mix(rotationY)
    mix(rotationZ)
    mix(cameraDistance)
    mix(shadowElevation)
    mix(pivotOffset.packedValue)
    mix(topLeft.x.toLong() shl 32 or (topLeft.y.toLong() and 0xFFFFFFFFL))
    mix(size.width.toLong() shl 32 or (size.height.toLong() and 0xFFFFFFFFL))
    mix(if (clip) 1L else 0L)
    mix(ambientShadowColor.value.toLong())
    mix(spotShadowColor.value.toLong())
    mix(blendMode.hashCode().toLong())
    mix(compositingStrategy.hashCode().toLong())
    mix(System.identityHashCode(renderEffect).toLong())
    mix(System.identityHashCode(colorFilter).toLong())
    return h
}

private fun IntRect.intersectOrEmpty(other: IntRect): IntRect {
    val r = intersect(other)
    return if (r.width <= 0 || r.height <= 0) IntRect.Zero else r
}

/** A growable bounding box; [toIntRect] of an empty one is [IntRect.Zero]. */
private class MutableIntRect {
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var empty = true

    fun setEmpty() {
        empty = true
    }

    fun union(r: IntRect) {
        if (r.width <= 0 || r.height <= 0) return
        if (empty) {
            left = r.left
            top = r.top
            right = r.right
            bottom = r.bottom
            empty = false
        } else {
            if (r.left < left) left = r.left
            if (r.top < top) top = r.top
            if (r.right > right) right = r.right
            if (r.bottom > bottom) bottom = r.bottom
        }
    }

    fun toIntRect(): IntRect = if (empty) IntRect.Zero else IntRect(left, top, right, bottom)
}

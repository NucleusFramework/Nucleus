package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.snapshots.Snapshot
import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Only the Linux backend (AccessKit) consumes partial snapshots; mac/win
 *  must always receive full snapshots otherwise small state diffs would be
 *  silently dropped by the no-op `nativeA11yApplyPartialSnapshot` stubs. */
private val TAO_PARTIAL_SUPPORTED: Boolean =
    System.getProperty("os.name", "").lowercase().let { os ->
        !os.contains("win") && !os.contains("mac") && !os.contains("darwin")
    }

/**
 * macOS accessibility plumbing.
 *
 * Architecture (per the macOS a11y design report, sections 1–3):
 *  - [TaoAccessibilityController] owns the projection of one Compose
 *    `SemanticsOwner` onto the NSAccessibility tree of one TaoView. It builds
 *    flat [TaoA11yNode] lists, serialises them to the wire format documented
 *    in `macos/a11y.m`, and pushes them to native via JNI.
 *  - [TaoAccessibilityRegistry] indexes controllers by window handle so the
 *    JNI callback ([NativeTaoBridge.dispatchA11yAction]) can find the
 *    receiver for an incoming VoiceOver action.
 *
 * Threading: the controller is single-threaded — Compose's snapshot observer
 * runs through `TaoMainDispatcher`, which serialises every push onto the Tao
 * main thread. Action callbacks come back on the AppKit main thread (same
 * thread), so no locking is needed beyond [TaoAccessibilityRegistry]'s
 * concurrent map for cross-thread registration.
 */

/**
 * Compose-agnostic node descriptor produced by a SemanticsTree walk.
 *
 * Frame fields are in window-local **logical points** with **top-left
 * origin** (matching Compose's coordinate system). The native side flips to
 * AppKit's bottom-left screen origin lazily on every `accessibilityFrame`
 * read, so window drags / display moves don't require re-pushing the
 * snapshot.
 */
data class TaoA11yNode(
    val nodeId: Long,
    val parentId: Long,
    val role: TaoA11yRole,
    val flags: Int,
    val extraFlags: Int = 0,
    val actions: Int,
    val frameX: Float,
    val frameY: Float,
    val frameW: Float,
    val frameH: Float,
    val minValue: Float = 0f,
    val maxValue: Float = 0f,
    val numericValue: Float = 0f,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    /** Horizontal scroll axis: total scrollable extent. 0 = no horizontal scroll. */
    val hScrollMax: Float = 0f,
    val hScrollValue: Float = 0f,
    val vScrollMax: Float = 0f,
    val vScrollValue: Float = 0f,
    val label: String = "",
    val valueString: String = "",
    /**
     * Compose-defined custom action labels. Their dispatch index is the
     * position in this list; native invokes them back via
     * `dispatchA11yCustomAction(nsView, nodeId, index)`.
     */
    val customActions: List<String> = emptyList(),
    /**
     * Compose `Modifier.testTag(...)` value. Forwarded as AT-SPI's
     * `Accessible.GetAccessibleId()` (via AccessKit's `set_author_id`) so UI
     * automation, Accerciser and screen readers can identify widgets
     * symbolically rather than by visual label. Maps to `AXIdentifier` on
     * macOS and `UIA_AutomationIdPropertyId` on Windows.
     */
    val testTag: String = "",
    /**
     * Direct children, in tree order. Computed by the observer after the
     * full DFS walk so each node knows its own subtree topology. Used by
     * the wire format v7 partial-update path: emitting one node fully
     * describes its place in the tree without forcing a re-emit of all
     * siblings.
     */
    val children: List<Long> = emptyList(),
)

@Suppress("MagicNumber")
enum class TaoA11yRole(val code: Int) {
    Unknown(0),
    Group(1),
    Button(2),
    StaticText(3),
    Checkbox(4),
    RadioButton(5),
    Switch(6),
    TextField(7),
    TextArea(8),
    Slider(9),
    Progress(10),
    Image(11),
    ScrollArea(12),
    Heading(13),
    Tab(14),
    PopupMenu(15),
    Table(16),
    Outline(17),
    Row(18),
    Cell(19),
    SpinButton(20),
    TabPanel(21),
    Tooltip(22),
}

@Suppress("MagicNumber")
object TaoA11yFlag {
    const val IS_ELEMENT = 1 shl 0
    const val ENABLED    = 1 shl 1
    const val FOCUSED    = 1 shl 2
    const val SELECTED   = 1 shl 3
    const val CHECKED    = 1 shl 4
    const val MIXED      = 1 shl 5
    const val HEADING    = 1 shl 6
    const val PASSWORD   = 1 shl 7
    const val MULTILINE  = 1 shl 8
    const val MODAL                = 1 shl 9
    const val LIVE_REGION_POLITE   = 1 shl 10
    const val LIVE_REGION_ASSERTIVE = 1 shl 11
    // Linux/AT-SPI-only bits — macOS and Windows ignore them. Wire format kept
    // at v4 because flags ride in a u16 we already had spare bits in.
    const val MULTI_SELECTABLE = 1 shl 12
    const val EXPANDED_TRUE    = 1 shl 13
    const val EXPANDED_FALSE   = 1 shl 14
    /**
     * Reserved. The observer prunes invisible nodes (`InvisibleToUser` /
     * `HideFromAccessibility`) before serialisation, so this bit is currently
     * never set by Compose. Kept available for future "projected but hidden"
     * cases (off-viewport scrollable items, aria-hidden mirroring).
     */
    const val HIDDEN           = 1 shl 15
}

/**
 * Extra per-node flags carried in the wire format's previously-reserved u16
 * after `actions`. Wire-format v6+. Linux uses these for AT-SPI-specific bits
 * that don't have macOS / Windows equivalents.
 */
@Suppress("MagicNumber")
object TaoA11yExtraFlag {
    /** Compose `BasicTextField(readOnly = true)` — drops `SetText` action. */
    const val READ_ONLY = 1 shl 0
    /**
     * Compose `SemanticsProperties.Error` — invalid form-field value. Linux
     * exposes this as AT-SPI `STATE_INVALID_ENTRY` so screen readers announce
     * "invalid" / form validators stop on the field.
     */
    const val INVALID = 1 shl 1
}

@Suppress("MagicNumber")
object TaoA11yAction {
    const val CLICK         = 1 shl 0
    const val INCREMENT     = 1 shl 1
    const val DECREMENT     = 1 shl 2
    const val SET_TEXT      = 1 shl 3
    const val REQUEST_FOCUS = 1 shl 4
    const val SCROLL_UP     = 1 shl 5
    const val SCROLL_DOWN   = 1 shl 6
    const val SCROLL_LEFT   = 1 shl 7
    const val SCROLL_RIGHT  = 1 shl 8
    const val DISMISS       = 1 shl 9
}

/**
 * Per-window registry. Looked up by:
 *  - [TaoAccessibilityController] on construction (registers itself);
 *  - [NativeTaoBridge.dispatchA11yAction] when VoiceOver invokes an action.
 */
internal object TaoAccessibilityRegistry {
    private val byHandle = ConcurrentHashMap<Long, TaoAccessibilityController>()
    // Action callbacks from native arrive with the NSView pointer (not the
    // window handle) so the Rust callback doesn't have to re-lock the
    // WINDOWS map. Both indexes are kept in sync.
    private val byNsView = ConcurrentHashMap<Long, TaoAccessibilityController>()

    fun register(handle: Long, controller: TaoAccessibilityController) {
        byHandle[handle] = controller
    }

    fun unregister(handle: Long) {
        byHandle.remove(handle)
    }

    fun registerNsView(nsView: Long, controller: TaoAccessibilityController) {
        byNsView[nsView] = controller
    }

    fun unregisterNsView(nsView: Long) {
        byNsView.remove(nsView)
    }

    /** Lookup by NSView pointer — used by the action callback from native. */
    fun dispatchActionByNsView(nsView: Long, nodeId: Long, action: Int) {
        byNsView[nsView]?.onActionInvoked(nodeId, action)
    }

    fun dispatchAction(handle: Long, nodeId: Long, action: Int) {
        byHandle[handle]?.onActionInvoked(nodeId, action)
    }

    fun dispatchSetText(nsView: Long, nodeId: Long, text: String) {
        byNsView[nsView]?.onSetTextInvoked(nodeId, text)
    }

    fun dispatchSetSelection(nsView: Long, nodeId: Long, start: Int, end: Int) {
        byNsView[nsView]?.onSetSelectionInvoked(nodeId, start, end)
    }

    fun dispatchCustomAction(nsView: Long, nodeId: Long, index: Int) {
        byNsView[nsView]?.onCustomActionInvoked(nodeId, index)
    }

    fun dispatchScrollBy(nsView: Long, nodeId: Long, dx: Float, dy: Float) {
        byNsView[nsView]?.onScrollByInvoked(nodeId, dx, dy)
    }

    fun dispatchSetValue(nsView: Long, nodeId: Long, value: Double) {
        byNsView[nsView]?.onSetValueInvoked(nodeId, value)
    }
}

/**
 * Owns the a11y projection for one decorated window. Construction calls
 * [NativeTaoBridge.nativeA11yAttach]; [dispose] calls
 * [NativeTaoBridge.nativeA11yDetach].
 *
 * Action handlers ([onClick], [onIncrement], [onDecrement]) are wired up by
 * the SemanticsTree observer once it ingests the Compose tree. The
 * controller looks them up by node id when VoiceOver invokes an action.
 */
internal class TaoAccessibilityController(
    private val windowHandle: Long,
) {
    private val actionHandlers = HashMap<Long, ActionHandlers>()

    /**
     * Cached NSView pointer captured at [attach] time. We pass this to every
     * native a11y call instead of [windowHandle] to avoid re-locking Rust's
     * `WINDOWS` mutex (which is already held when EVENT_DESTROYED is being
     * dispatched).
     */
    private var nsView: Long = 0L

    /**
     * Opaque native view handle cached at attach time, or 0 before attach.
     * On macOS this is the NSView pointer, on Windows the HWND, on Linux the
     * Tao window handle (used by AccessKit as an opaque registry key — never
     * dereferenced by the AT-SPI side). Exposed for platform integration code
     * that needs to forward focus / bounds updates to native a11y backends.
     */
    val nativeViewHandle: Long get() = nsView

    /**
     * Whether the next [pushSnapshot] should bypass the `nativeA11yIsActive`
     * gate. Set to `true` initially so the first observer tick after attach
     * always seeds the native tree — otherwise the very first AX query
     * (which races with the first push) sees an empty tree.
     *
     * The flag also flips to `true` whenever the native side detects an AX
     * query but the JVM has been skipping pushes during an idle window.
     * That's checked in [pushSnapshot] before deciding to skip.
     */
    private var pendingForcedPush: Boolean = true

    /**
     * Previous snapshot indexed by node id. `null` means "nothing pushed yet";
     * the next push will be encoded as a full snapshot. Subsequent pushes
     * compare against this map and emit only the nodes whose content or
     * children list changed (plus the parents of any added / removed
     * children — re-emitting a parent updates its children list, which is
     * how AccessKit observes topology changes on incremental updates).
     */
    private var prevNodesById: Map<Long, TaoA11yNode>? = null

    /**
     * Once disposed, every public entry point becomes a no-op. Necessary
     * because `composition.dispose()` (during app shutdown) fires
     * `onSemanticsOwnerRemoved` callbacks that schedule a final sync —
     * which would otherwise re-enter into native AX code on a window that
     * has already been detached.
     */
    @Volatile
    var isDisposed: Boolean = false
        private set

    fun attach() {
        if (isDisposed) return
        // Resolve and cache the native window handle BEFORE entering the
        // destroy path. Safe here because attach runs inside `onWindowReady`,
        // well before any Tao close machinery.
        //
        // The "nsView" field name is historical — on Windows it stores the
        // HWND, on Linux the X11 Window XID. The Kotlin-side action registry
        // treats it as an opaque key and the native side resolves it back to
        // the actual HWND/NSView/XID.
        val os = System.getProperty("os.name", "").lowercase()
        nsView = when {
            os.contains("win") -> {
                // Force-load nucleus_tao_a11y.dll so the Rust side can
                // resolve its exports via GetModuleHandleW.
                NativeTaoA11yWindowsBridge.isLoaded
                NativeTaoBridge.nativeHwndHandle(windowHandle)
            }
            os.contains("mac") || os.contains("darwin") ->
                NativeTaoBridge.nativeNsViewHandle(windowHandle)
            else -> {
                // Linux: AT-SPI projection lives inside nucleus_tao itself
                // (no sibling .so to load). On the X11 path the handle was
                // historically the X11 Window XID — but `a11y_linux.rs`
                // treats it as an opaque `i64` registry key (see WindowState
                // doc comment), it never dereferences it. Using the Tao
                // window handle directly means the EGL+Wayland path
                // (kind=2, no XID) keeps a11y working without changes on
                // the Rust side. AT-SPI itself is D-Bus and backend-agnostic
                // (accesskit_unix has zero X11/Wayland deps).
                if (NativeTaoBridge.nativeLinuxHandles(windowHandle) != null) windowHandle else 0L
            }
        }
        if (nsView == 0L) return
        // Override AT-SPI's app name before the first Adapter spins up.
        // accesskit_unix defaults to `current_exe()` — on the JVM that's
        // "java", which is what every screen reader and Accerciser would
        // otherwise display. Idempotent: only the first call sticks.
        runCatching {
            val displayName = NucleusApp.appName ?: NucleusApp.appId
            if (displayName.isNotBlank()) {
                NativeTaoBridge.nativeA11ySetAppName(displayName)
            }
        }
        TaoAccessibilityRegistry.register(windowHandle, this)
        TaoAccessibilityRegistry.registerNsView(nsView, this)
        NativeTaoBridge.nativeA11yAttach(nsView)
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        TaoAccessibilityRegistry.unregister(windowHandle)
        if (nsView != 0L) {
            TaoAccessibilityRegistry.unregisterNsView(nsView)
            NativeTaoBridge.nativeA11yDetach(nsView)
        }
        actionHandlers.clear()
        prevNodesById = null
    }

    fun pushSnapshot(nodes: List<TaoA11yNode>) {
        if (isDisposed || nsView == 0L) return
        // Smart gating: skip when no AX client is active AND no resync was
        // requested. The initial push at attach time has
        // `pendingForcedPush = true`, so it always seeds the native tree —
        // that's what makes the first AX query find a populated tree.
        val active = NativeTaoBridge.nativeA11yIsActive()
        val needsResync = NativeTaoBridge.nativeA11yConsumeResync()
        if (!pendingForcedPush && !active && !needsResync) return

        val newMap = nodes.associateBy { it.nodeId }
        val focusId = nodes.firstOrNull { (it.flags and TaoA11yFlag.FOCUSED) != 0 }?.nodeId ?: 0L
        val prev = prevNodesById

        // Decide between full and partial:
        //  - First push, forced push, or AT-requested resync → full.
        //  - Otherwise compute the changed-node set and emit a partial.
        if (prev == null || pendingForcedPush || needsResync) {
            val bytes = TaoA11ySnapshotSerializer.encodeFull(nodes)
            NativeTaoBridge.nativeA11yApplySnapshot(nsView, bytes)
            NativeTaoBridge.nativeA11yNotePushed()
            pendingForcedPush = false
            prevNodesById = newMap
            return
        }

        val toEmit = computeChangedNodes(prev, newMap, nodes)
        if (toEmit.isEmpty()) {
            // Nothing observable changed — keep the cached map (it's
            // already equal) and skip the native hop entirely.
            return
        }
        // Heuristic: when the delta is a large fraction of the tree, the
        // partial-update bookkeeping (per-node children list rewrite) is
        // not cheaper than a full re-push and it complicates AccessKit's
        // internal diff. The 50 % cutoff matches accesskit_consumer's own
        // batching threshold.
        //
        // Only Linux (AccessKit) implements partial snapshots. On macOS and
        // Windows `nativeA11yApplyPartialSnapshot` is a no-op stub, so a
        // partial push silently disappears and small state changes (a single
        // counter tick, a toggle) never reach the OS a11y tree. Always emit
        // a full snapshot on those platforms.
        val emitPartial = TAO_PARTIAL_SUPPORTED && toEmit.size * 2 < nodes.size
        if (!emitPartial) {
            val bytes = TaoA11ySnapshotSerializer.encodeFull(nodes)
            NativeTaoBridge.nativeA11yApplySnapshot(nsView, bytes)
        } else {
            val bytes = TaoA11ySnapshotSerializer.encodePartial(toEmit, focusId)
            NativeTaoBridge.nativeA11yApplyPartialSnapshot(nsView, bytes)
        }
        NativeTaoBridge.nativeA11yNotePushed()
        prevNodesById = newMap
    }

    /**
     * Compute the minimal set of nodes that must be re-emitted to make the
     * AT-SPI projection match [newMap].
     *
     *  - Any node whose contents differ from the previous version must be
     *    re-emitted (children-list comparison is part of `equals` because
     *    [TaoA11yNode] is a data class).
     *  - When a child is added or removed, the parent's children list
     *    changes; the parent's data-class equality already catches that
     *    via the `children` field, so no extra topology bookkeeping is
     *    needed.
     */
    private fun computeChangedNodes(
        prev: Map<Long, TaoA11yNode>,
        newMap: Map<Long, TaoA11yNode>,
        ordered: List<TaoA11yNode>,
    ): List<TaoA11yNode> {
        if (prev.size == newMap.size && prev == newMap) return emptyList()
        val out = ArrayList<TaoA11yNode>(8)
        for (n in ordered) {
            val before = prev[n.nodeId]
            if (before == null || before != n) out.add(n)
        }
        return out
    }

    fun setActionHandlers(nodeId: Long, handlers: ActionHandlers) {
        if (isDisposed) return
        actionHandlers[nodeId] = handlers
    }

    fun clearStaleHandlers(liveNodeIds: Set<Long>) {
        if (isDisposed) return
        actionHandlers.keys.retainAll(liveNodeIds)
    }

    internal fun onActionInvoked(nodeId: Long, action: Int) {
        if (isDisposed) return
        val h = actionHandlers[nodeId] ?: return
        when (action) {
            TaoA11yAction.CLICK         -> h.onClick?.invoke()
            TaoA11yAction.INCREMENT     -> h.onIncrement?.invoke()
            TaoA11yAction.DECREMENT     -> h.onDecrement?.invoke()
            TaoA11yAction.REQUEST_FOCUS -> h.onRequestFocus?.invoke()
            TaoA11yAction.SCROLL_UP     -> h.onScrollUp?.invoke()
            TaoA11yAction.SCROLL_DOWN   -> h.onScrollDown?.invoke()
            TaoA11yAction.SCROLL_LEFT   -> h.onScrollLeft?.invoke()
            TaoA11yAction.SCROLL_RIGHT  -> h.onScrollRight?.invoke()
            TaoA11yAction.DISMISS       -> h.onDismiss?.invoke()
        }
        wakeEventLoop()
    }

    internal fun onSetTextInvoked(nodeId: Long, text: String) {
        if (isDisposed) return
        actionHandlers[nodeId]?.onSetText?.invoke(text)
        wakeEventLoop()
    }

    internal fun onSetSelectionInvoked(nodeId: Long, start: Int, end: Int) {
        if (isDisposed) return
        actionHandlers[nodeId]?.onSetSelection?.invoke(start, end)
        wakeEventLoop()
    }

    private fun wakeEventLoop() {
        // Action lambdas mutate Compose state directly from a non-pump thread.
        // pump() only emits sendApplyNotifications when it has run blocks, so
        // without this explicit call here the state writes can sit invisible
        // until the next unrelated dispatcher tick. Push them through now so
        // the recomposer schedules a frame and the snapshot encoder sees the
        // change on the next pump.
        Snapshot.sendApplyNotifications()
        // The action just wrote to Compose state on the AppKit main thread,
        // outside any Tao event handler. The Recomposer is suspended on
        // `TaoMainDispatcher`, which is only pumped from `MAIN_EVENTS_CLEARED`
        // — i.e. from the next Tao event-loop iteration. Without a wake-up
        // there is nothing to drive that iteration, so the recomposition
        // (and our snapshot push) would stall until some unrelated OS event
        // arrived. A redraw request posts a UserEvent that wakes the loop
        // immediately.
        NativeTaoBridge.nativeRequestRedraw(windowHandle)
    }

    data class ActionHandlers(
        val onClick: (() -> Unit)? = null,
        val onIncrement: (() -> Unit)? = null,
        val onDecrement: (() -> Unit)? = null,
        val onSetText: ((String) -> Unit)? = null,
        val onRequestFocus: (() -> Unit)? = null,
        val onScrollUp: (() -> Unit)? = null,
        val onScrollDown: (() -> Unit)? = null,
        val onScrollLeft: (() -> Unit)? = null,
        val onScrollRight: (() -> Unit)? = null,
        val onDismiss: (() -> Unit)? = null,
        val onSetSelection: ((start: Int, end: Int) -> Unit)? = null,
        /**
         * Compose-side custom actions, invoked by index. The order MUST match
         * the [TaoA11yNode.customActions] labels list pushed in the same
         * snapshot.
         */
        val customActions: List<() -> Unit> = emptyList(),
        /** Absolute scroll delta in pixels. Wired to `SemanticsActions.ScrollBy`. */
        val onScrollBy: ((dx: Float, dy: Float) -> Unit)? = null,
        /**
         * Slider / progress value setter (Linux AT-SPI `Value.SetCurrentValue`).
         * Receives the absolute value in the slider's own range; the handler
         * clamps and forwards to `SemanticsActions.SetProgress`.
         */
        val onSetValue: ((Float) -> Unit)? = null,
    )

    internal fun onCustomActionInvoked(nodeId: Long, index: Int) {
        if (isDisposed) return
        val list = actionHandlers[nodeId]?.customActions ?: return
        if (index < 0 || index >= list.size) return
        list[index].invoke()
        wakeEventLoop()
    }

    internal fun onScrollByInvoked(nodeId: Long, dx: Float, dy: Float) {
        if (isDisposed) return
        actionHandlers[nodeId]?.onScrollBy?.invoke(dx, dy)
        wakeEventLoop()
    }

    internal fun onSetValueInvoked(nodeId: Long, value: Double) {
        if (isDisposed) return
        actionHandlers[nodeId]?.onSetValue?.invoke(value.toFloat())
        wakeEventLoop()
    }
}

/**
 * Wire-format serialiser. The Linux Rust decoder (`src/a11y_linux.rs`) is the
 * authoritative parser at v7; the macOS / Windows readers are at v4 and only
 * accept full snapshots — they reject v7 and stay dormant on this branch.
 *
 * v7 layout (little-endian throughout):
 *
 *   Header (24 bytes):
 *     u32 magic     = 0xA110A11A
 *     u16 version   = 7
 *     u16 flags     (bit 0 = partial update; bits 1..15 reserved)
 *     u32 nodeCount
 *     u64 focusId   (0 = no explicit focus, fall back to root)
 *     u32 reserved
 *
 *   Per-node:
 *     u64 nodeId
 *     u64 parentId
 *     u16 role
 *     u16 flags
 *     u16 actions
 *     u16 extraFlags
 *     f32 frameX, frameY, frameW, frameH
 *     f32 minValue, maxValue, numericValue
 *     u32 selectionStart, selectionEnd
 *     f32 hScrollMax, hScrollValue, vScrollMax, vScrollValue
 *     u16 labelLen + labelBytes
 *     u16 valueLen + valueBytes
 *     u16 customCount + (u16 nameLen + nameBytes)*
 *     u16 testTagLen + testTagBytes
 *     u32 childCount + (u64 childId)*
 *
 * Length-prefix UTF-8 fields are clamped to 65 535 bytes at codepoint
 * boundaries by [clampUtf8] — the alternative would be widening every
 * length to u32. 65 KB per label/value is far beyond any reasonable AT-SPI
 * announcement; editable text is delivered via the dedicated
 * `org.a11y.atspi.Text` interface which chunks naturally.
 *
 * Partial updates carry only the nodes whose contents or children list
 * changed since the last full push. AccessKit merges them into its
 * existing tree — un-emitted nodes keep their state.
 */
internal object TaoA11ySnapshotSerializer {
    private const val MAGIC = 0xA110A11A.toInt()
    private const val VERSION: Short = 7
    private const val FLAG_PARTIAL: Short = 0x0001
    private const val MAX_FIELD_BYTES = 65_535

    private fun clampUtf8(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.size <= MAX_FIELD_BYTES) return raw
        var cut = MAX_FIELD_BYTES
        while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) cut--
        return raw.copyOf(cut)
    }

    fun encodeFull(nodes: List<TaoA11yNode>): ByteArray =
        encodeImpl(nodes, partial = false, focusId = 0L)

    fun encodePartial(nodes: List<TaoA11yNode>, focusId: Long): ByteArray =
        encodeImpl(nodes, partial = true, focusId = focusId)

    private fun encodeImpl(
        nodes: List<TaoA11yNode>,
        partial: Boolean,
        focusId: Long,
    ): ByteArray {
        var size = 24 // header
        val labelBytes = ArrayList<ByteArray>(nodes.size)
        val valueBytes = ArrayList<ByteArray>(nodes.size)
        val customBytes = ArrayList<List<ByteArray>>(nodes.size)
        val testTagBytes = ArrayList<ByteArray>(nodes.size)
        // Per-node fixed-section size:
        //   8(id)+8(parent)+2(role)+2(flags)+2(actions)+2(extraFlags)
        //   +16(frame)+12(range)+8(selection)+16(scroll axes) = 76
        for (n in nodes) {
            val lb = clampUtf8(n.label)
            val vb = clampUtf8(n.valueString)
            labelBytes += lb
            valueBytes += vb
            val cb = n.customActions.map { clampUtf8(it) }
            customBytes += cb
            val tb = clampUtf8(n.testTag)
            testTagBytes += tb
            var nodeSize = 76 + 2 + lb.size + 2 + vb.size + 2 + 2 + tb.size + 4
            for (a in cb) nodeSize += 2 + a.size
            nodeSize += 8 * n.children.size
            size += nodeSize
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putShort(VERSION)
        buf.putShort(if (partial) FLAG_PARTIAL else 0)
        buf.putInt(nodes.size)
        buf.putLong(focusId)
        buf.putInt(0) // reserved
        for ((i, n) in nodes.withIndex()) {
            buf.putLong(n.nodeId)
            buf.putLong(n.parentId)
            buf.putShort(n.role.code.toShort())
            buf.putShort(n.flags.toShort())
            buf.putShort(n.actions.toShort())
            buf.putShort(n.extraFlags.toShort())
            buf.putFloat(n.frameX); buf.putFloat(n.frameY)
            buf.putFloat(n.frameW); buf.putFloat(n.frameH)
            buf.putFloat(n.minValue); buf.putFloat(n.maxValue); buf.putFloat(n.numericValue)
            buf.putInt(n.selectionStart)
            buf.putInt(n.selectionEnd)
            buf.putFloat(n.hScrollMax); buf.putFloat(n.hScrollValue)
            buf.putFloat(n.vScrollMax); buf.putFloat(n.vScrollValue)
            val lb = labelBytes[i]
            buf.putShort(lb.size.toShort())
            buf.put(lb)
            val vb = valueBytes[i]
            buf.putShort(vb.size.toShort())
            buf.put(vb)
            val cb = customBytes[i]
            val cbCount = cb.size.coerceAtMost(MAX_FIELD_BYTES)
            buf.putShort(cbCount.toShort())
            for (idx in 0 until cbCount) {
                val ab = cb[idx]
                buf.putShort(ab.size.toShort())
                buf.put(ab)
            }
            val tb = testTagBytes[i]
            buf.putShort(tb.size.toShort())
            buf.put(tb)
            buf.putInt(n.children.size)
            for (cid in n.children) buf.putLong(cid)
        }
        return buf.array()
    }
}

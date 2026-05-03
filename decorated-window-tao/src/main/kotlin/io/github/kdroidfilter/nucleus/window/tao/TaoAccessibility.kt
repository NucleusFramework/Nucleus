package io.github.kdroidfilter.nucleus.window.tao

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * macOS accessibility plumbing.
 *
 * Architecture (per the macOS a11y design report, sections 1–3):
 *  - [TaoAccessibilityController] owns the projection of one Compose
 *    `SemanticsOwner` onto the NSAccessibility tree of one TaoView. It builds
 *    flat [TaoA11yNode] lists, serialises them to the wire format documented
 *    in `objc/a11y.m`, and pushes them to native via JNI.
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
    const val HIDDEN           = 1 shl 15
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
     * Linux-only convenience accessor: the X11 Window XID cached at attach
     * time, or 0 before attach / on other platforms. Used by the
     * DecoratedWindow Linux path to push focus + bounds updates to AccessKit.
     */
    val linuxXid: Long get() = nsView

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
    private var lastPushedNonceMonotonicNs: Long = 0L

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
                // (no sibling .so to load). The handle is the X11 Window
                // XID — index 2 of the `nativeLinuxHandles` triple,
                // assuming kind=1 (Xlib). GLX path forces GDK_BACKEND=x11
                // so we never see kind=2 here.
                val handles = NativeTaoBridge.nativeLinuxHandles(windowHandle)
                if (handles != null && handles.size == 3 && handles[0].toInt() == 1) {
                    handles[2]
                } else {
                    0L
                }
            }
        }
        if (nsView == 0L) return
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
    }

    fun pushSnapshot(nodes: List<TaoA11yNode>) {
        if (isDisposed || nsView == 0L) return
        // Smart gating: skip when no AX client is active AND no resync was
        // requested (which would happen if an AX query landed during a skip).
        // The initial push at attach time has `pendingForcedPush = true`, so
        // it always seeds the native tree — that's what made the first AX
        // query find a populated tree before we had this fix.
        val active = NativeTaoBridge.nativeA11yIsActive()
        val needsResync = NativeTaoBridge.nativeA11yConsumeResync()
        if (!pendingForcedPush && !active && !needsResync) return
        val bytes = TaoA11ySnapshotSerializer.encode(nodes)
        NativeTaoBridge.nativeA11yApplySnapshot(nsView, bytes)
        NativeTaoBridge.nativeA11yNotePushed()
        pendingForcedPush = false
        lastPushedNonceMonotonicNs = System.nanoTime()
    }

    /**
     * Called by the observer on a low-frequency tick (or by the runtime when
     * a user interaction occurs). If a fresh AX query has arrived since the
     * last push, we force the next [pushSnapshot] to actually go through —
     * this keeps the tree current after waking up from an idle period.
     */
    fun maybeForceResync() {
        // No longer needed — pushSnapshot consumes the native resync flag
        // directly. Kept as a no-op to preserve the observer's call site.
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
 * Wire-format serialiser. Layout must match the parser in `objc/a11y.m`.
 */
internal object TaoA11ySnapshotSerializer {
    private const val MAGIC = 0xA110A11A.toInt()
    private const val VERSION: Short = 4  // bumped to add scroll axis info

    fun encode(nodes: List<TaoA11yNode>): ByteArray {
        var size = 12 // header
        val labelBytes = ArrayList<ByteArray>(nodes.size)
        val valueBytes = ArrayList<ByteArray>(nodes.size)
        val customBytes = ArrayList<List<ByteArray>>(nodes.size)
        // Per-node fixed size:
        //   8(id)+8(parent)+2(role)+2(flags)+2(actions)+2(reserved)
        //   +16(frame)+12(range)+8(selection)+16(scroll axes) = 76
        for (n in nodes) {
            val lb = n.label.toByteArray(Charsets.UTF_8)
            val vb = n.valueString.toByteArray(Charsets.UTF_8)
            labelBytes += lb
            valueBytes += vb
            val cb = n.customActions.map { it.toByteArray(Charsets.UTF_8) }
            customBytes += cb
            var nodeSize = 76 + 2 + lb.size + 2 + vb.size + 2
            for (a in cb) nodeSize += 2 + a.size
            size += nodeSize
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putShort(VERSION)
        buf.putShort(0) // reserved
        buf.putInt(nodes.size)
        for ((i, n) in nodes.withIndex()) {
            buf.putLong(n.nodeId)
            buf.putLong(n.parentId)
            buf.putShort(n.role.code.toShort())
            buf.putShort(n.flags.toShort())
            buf.putShort(n.actions.toShort())
            buf.putShort(0) // reserved2
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
            buf.putShort(cb.size.toShort())
            for (ab in cb) {
                buf.putShort(ab.size.toShort())
                buf.put(ab)
            }
        }
        return buf.array()
    }
}

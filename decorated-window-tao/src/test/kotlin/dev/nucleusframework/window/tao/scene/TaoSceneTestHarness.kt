@file:OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.event.TaoSyntheticMouseWheelEvent
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.Surface
import kotlin.coroutines.CoroutineContext

/**
 * Offscreen test harness for the Tao Compose backend — the stage-1 tier of the
 * testing pyramid (see JetBrains' Compose Desktop / kotlin-desktop-toolkit
 * test layout): thousands of fast, deterministic tests against the same
 * ComposeScene plumbing the real host drives, with no native window, no JNI
 * and no display.
 *
 * What is production code here (the code under test):
 * - the event-translation layer: [dispatchNativeKeyEvent], [taoKeyboardModifiers],
 *   [TaoSyntheticMouseWheelEvent], mac/linux key tables;
 * - the CPU record path [recordSceneToPicture] the host uses for every frame;
 * - the `CanvasLayersComposeScene` configuration mirrored from
 *   `TaoComposeSceneHost.attach()` (same scene type, same clock/dispatcher
 *   context shape).
 *
 * What is replicated from the host (kept in sync deliberately): the thin
 * `sendPointerEvent` dispatch shapes of `onPointerMove` / `onPointerButton` /
 * `onPointerScroll`, including the cursor-move-before-click and press-dedup
 * guards and the sub-pixel deadband ([TaoPointerDeadband], #615), so a
 * regression in those contracts fails here first.
 *
 * Time is fully synthetic: [frame] advances a virtual clock, pumps the
 * single-threaded dispatcher, delivers frame-clock ticks and
 * records the scene through [recordSceneToPicture] — one call, one frame,
 * bit-for-bit reproducible.
 */
internal fun runTaoSceneTest(
    width: Int = 400,
    height: Int = 300,
    density: Float = 1f,
    block: TaoSceneTestScope.() -> Unit,
) {
    val scope = TaoSceneTestScope(width, height, density)
    try {
        scope.block()
    } finally {
        scope.close()
    }
}

/**
 * Manually pumped dispatcher — the test thread runs every task, but tasks may
 * be ENQUEUED from other threads (coroutine resumptions can hop through
 * Dispatchers.Default internals), so the queue must be thread-safe: an
 * unsynchronized ArrayDeque here caused rare cross-thread corruption (NPE in
 * pump, dropped frames, scroll residue) under load. Same model as
 * TaoMainDispatcher's ConcurrentLinkedQueue.
 *
 * Also implements [Delay] against the harness's VIRTUAL clock. Without it,
 * every `delay` / `withTimeout` inside scene code (most notably Compose's
 * MouseWheelScrollingLogic) falls back to kotlinx's DefaultExecutor — REAL
 * time on another thread — while [TaoSceneTestScope.frame] advances a
 * synthetic clock with no wall-time in between. On a loaded runner the
 * pending real-time resumption fires after `frameUntilIdle` has already
 * declared the scene quiet, leaving a scroll animation half-applied (the
 * rare 8px residue in the wheel-scroll symmetry test). Timers keyed on the
 * virtual clock make those paths deterministic: [advanceTo] releases due
 * timers into the ordinary queue, where the test thread pumps them.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
private class QueueDispatcher :
    CoroutineDispatcher(),
    kotlinx.coroutines.Delay {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

    private class TimedTask(
        val deadlineNanos: Long,
        val sequence: Long,
        val task: Runnable,
    ) : Comparable<TimedTask> {
        override fun compareTo(other: TimedTask): Int {
            val byDeadline = deadlineNanos.compareTo(other.deadlineNanos)
            return if (byDeadline != 0) byDeadline else sequence.compareTo(other.sequence)
        }
    }

    private val timers = java.util.concurrent.PriorityBlockingQueue<TimedTask>()
    private val timerSequence =
        java.util.concurrent.atomic
            .AtomicLong()

    @Volatile
    private var nowNanos = 0L

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.add(block)
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: kotlinx.coroutines.CancellableContinuation<Unit>,
    ) {
        val timed =
            TimedTask(
                deadlineNanos = nowNanos + timeMillis * 1_000_000L,
                sequence = timerSequence.incrementAndGet(),
                task =
                    Runnable {
                        with(continuation) { resumeUndispatched(Unit) }
                    },
            )
        timers.add(timed)
        continuation.invokeOnCancellation { timers.remove(timed) }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): kotlinx.coroutines.DisposableHandle {
        val timed =
            TimedTask(
                deadlineNanos = nowNanos + timeMillis * 1_000_000L,
                sequence = timerSequence.incrementAndGet(),
                task = block,
            )
        timers.add(timed)
        return kotlinx.coroutines.DisposableHandle { timers.remove(timed) }
    }

    /** Releases every timer due at [timeNanos] into the pump queue. */
    fun advanceTo(timeNanos: Long) {
        nowNanos = timeNanos
        var head = timers.peek()
        while (head != null && head.deadlineNanos <= timeNanos) {
            timers.remove(head)
            queue.add(head.task)
            head = timers.peek()
        }
    }

    /** True when a timer is due within [horizonNanos] of the current virtual time. */
    fun hasTimerWithin(horizonNanos: Long): Boolean {
        val head = timers.peek() ?: return false
        return head.deadlineNanos - nowNanos <= horizonNanos
    }

    fun pump(): Boolean {
        var ran = false
        while (true) {
            val task = queue.poll() ?: break
            task.run()
            ran = true
        }
        return ran
    }
}

internal class TaoSceneTestScope(
    val width: Int,
    val height: Int,
    val density: Float,
) {
    private val dispatcher = QueueDispatcher()
    private var timeNanos = 0L
    private var invalidated = false

    /** Captured through the same PlatformContext hook the host exposes. */
    private val owners = mutableListOf<SemanticsOwner>()

    /** The production window-info implementation, configured like the host does on attach/resize. */
    val windowInfo: TaoWindowInfo =
        TaoWindowInfo().apply {
            isWindowFocused = true
            containerSize = IntSize(width, height)
            containerDpSize = DpSize((width / density).dp, (height / density).dp)
        }

    private val platformContext =
        object : TaoPlatformContextBase() {
            override val sceneScale: Float get() = this@TaoSceneTestScope.density

            override val windowInfo: TaoWindowInfo = this@TaoSceneTestScope.windowInfo

            // Mirrors `TaoPlatformContext.startInputMethod`: publish the text-input
            // session request so IME callbacks can edit the focused field, clear it
            // when the session ends (focus loss / field disposal).
            override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                inputMethodRequest = request
                imeSession.onInputSession(request)
                try {
                    awaitCancellation()
                } finally {
                    inputMethodRequest = null
                    imeSession.onInputSession(null)
                }
            }

            override val semanticsOwnerListener =
                object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                        owners += semanticsOwner
                    }

                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                        owners -= semanticsOwner
                    }

                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit

                    override fun onLayoutChange(
                        semanticsOwner: SemanticsOwner,
                        semanticsNodeId: Int,
                    ) = Unit
                }
        }

    private val sceneBundle: TaoSceneBundle =
        canvasLayersSceneBundle(
            coroutineContext = dispatcher,
            density = Density(density),
            layoutDirection = GlobalLayoutDirection,
            size = IntSize(width, height),
            platformContext = platformContext,
            requestFrame = { invalidated = true },
        )

    val scene: ComposeScene get() = sceneBundle.scene

    /**
     * Mirrors the scene host's `exceptionHandler` field (#621): installed on the
     * bundle, so frames go through the production guard in
     * [TaoSceneBundle.render], and consulted at the input / IME entry points the
     * host guards in `DecoratedWindow`. `null` — the default — propagates, like
     * a window whose app installed no factory and whose default one rethrows.
     */
    var exceptionHandler: WindowExceptionHandler? = null
        set(value) {
            field = value
            sceneBundle.exceptionHandler = value
        }

    /** See [TaoSceneBundle.isRecomposerAlive] — false once the scene can no longer recompose. */
    val isRecomposerAlive: Boolean get() = sceneBundle.isRecomposerAlive

    /** True while the scene has asked for another frame — the host's repaint signal. */
    val isSceneInvalidated: Boolean get() = invalidated

    // ── Host-mirrored pointer state (same guards as TaoComposeSceneHost) ────
    private val pointerDeadband = TaoPointerDeadband()
    private var hasReceivedCursorMove = false
    private var isPressed = false
    private var modifierState = 0

    var lastPicture: Picture? = null
        private set

    fun setContent(content: @Composable () -> Unit) {
        exceptionHandler.catchExceptions { scene.setContent(content = content) }
        frame()
    }

    /** Drains dispatcher + snapshot notifications until quiescent. */
    fun pumpUntilIdle() {
        do {
            val ran = dispatcher.pump()
            Snapshot.sendApplyNotifications()
        } while (ran || dispatcher.pump())
    }

    /**
     * Advances virtual time and produces one frame, exactly like the host's
     * render pass: pump continuations, deliver the frame clock, then record
     * the scene through the production CPU record path.
     */
    fun frame(deltaMillis: Long = FRAME_DELTA_MILLIS): Picture {
        timeNanos += deltaMillis * NANOS_PER_MILLI
        // Release virtual-clock timers (delay / withTimeout) due at the new
        // time BEFORE pumping, so their continuations run in this frame.
        dispatcher.advanceTo(timeNanos)
        // Cleared before delivering the frame: anything that re-invalidates
        // during or after it (a running animation awaiting the next
        // withFrameNanos) marks the scene dirty for frameUntilIdle.
        invalidated = false
        pumpUntilIdle()
        // The frame clock is ticked inside `recordSceneToPicture` (Compose 1.12
        // drives it through `FrameRecomposer.performFrame`, which flushes its own
        // dispatchers around the tick), so the recompose triggered by this
        // frame's `withFrameNanos` continuations is part of the recorded picture
        // — same guarantee the explicit sendFrame + pump used to give.
        return recordSceneToPicture(sceneBundle, width, height, timeNanos).also { lastPicture = it }
    }

    /**
     * Renders frames until the scene stops self-invalidating (animations
     * settle). Requires [quietFrames] CONSECUTIVE frames without an
     * invalidation before declaring idle: a single quiet frame can race the
     * scrollable/animation pipeline re-arming itself one dispatch later
     * (observed as a rare 8px residue in the wheel-scroll symmetry test).
     *
     * A frame also counts as busy while a virtual-clock timer (delay /
     * withTimeout inside scene code, e.g. the wheel-scroll gesture's
     * coalescing timeout) is due within the next few frames — its
     * continuation may re-invalidate, so idling before it fires would
     * observe a half-settled scene. Far-out timers (caret blink and other
     * periodic housekeeping) don't hold up idle.
     */
    fun frameUntilIdle(
        maxFrames: Int = 240,
        quietFrames: Int = 3,
    ): Picture {
        val timerHorizonNanos = TIMER_HORIZON_FRAMES * FRAME_DELTA_MILLIS * NANOS_PER_MILLI

        fun busy() = invalidated || dispatcher.hasTimerWithin(timerHorizonNanos)
        var picture = frame()
        var quiet = if (busy()) 0 else 1
        var remaining = maxFrames
        while (quiet < quietFrames && remaining-- > 0) {
            picture = frame()
            quiet = if (busy()) 0 else quiet + 1
        }
        return picture
    }

    // ── Pointer input (wire-format shaped, host dispatch mirrored) ──────────

    fun setModifiers(modifiers: Int) {
        modifierState = modifiers
    }

    /**
     * Mirrors `TaoComposeSceneHost.onPointerMove` (fixed-point 1024 wire
     * format), including the sub-pixel deadband (#615).
     */
    fun moveMouseFixed(
        aFixed: Int,
        bFixed: Int,
    ) {
        val xPx = aFixed / FIXED_POINT_SCALE
        val yPx = bFixed / FIXED_POINT_SCALE
        hasReceivedCursorMove = true
        windowInfo.keyboardModifiers = taoKeyboardModifiers(modifierState)
        if (!pointerDeadband.shouldDispatchMove(xPx, yPx, density)) {
            frame()
            return
        }
        exceptionHandler.catchExceptions {
            scene.sendPointerEvent(
                eventType = PointerEventType.Move,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                keyboardModifiers = taoKeyboardModifiers(modifierState),
            )
        }
        frame()
    }

    fun moveMouse(
        x: Float,
        y: Float,
    ) = moveMouseFixed((x * FIXED_POINT_SCALE).toInt(), (y * FIXED_POINT_SCALE).toInt())

    /** Mirrors `TaoComposeSceneHost.onPointerButton`, including its two guards. */
    fun pointerButton(
        button: PointerButton,
        pressed: Boolean,
    ) {
        if (!hasReceivedCursorMove) return // host guard: no click before a cursor move
        val modifiers = taoKeyboardModifiers(modifierState)
        if (pressed && isPressed) {
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                keyboardModifiers = modifiers,
                button = button,
            )
        } else if (!pressed && !isPressed) {
            return // host guard: stray release
        }
        isPressed = pressed
        exceptionHandler.catchExceptions {
            scene.sendPointerEvent(
                eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                keyboardModifiers = modifiers,
                button = button,
            )
        }
        frame()
    }

    fun click(
        x: Float,
        y: Float,
        button: PointerButton = PointerButton.Primary,
    ) {
        moveMouse(x, y)
        pointerButton(button, pressed = true)
        pointerButton(button, pressed = false)
    }

    fun exitPointer() {
        scene.sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = taoKeyboardModifiers(modifierState),
        )
        frame()
    }

    /** Mirrors `TaoComposeSceneHost.onPointerScroll` (AWT-shaped native event attached). */
    fun scroll(event: TaoPointerScrollEvent) {
        val modifiers = taoKeyboardModifiers(modifierState)
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            scrollDelta = Offset(event.dxAwt, event.dyAwt),
            type = PointerType.Mouse,
            keyboardModifiers = modifiers,
            nativeEvent =
                TaoSyntheticMouseWheelEvent.create(
                    event = event,
                    x = pointerDeadband.x,
                    y = pointerDeadband.y,
                    keyboardModifiers = modifiers,
                ),
        )
        frame()
    }

    // ── Key input (production dispatch pipeline) ────────────────────────────

    /**
     * Full production key pipeline: wire ints → [dispatchNativeKeyEvent]
     * (per-platform vk translation, KeyDown, synthetic KEY_TYPED, KeyUp).
     */
    fun keyDown(
        vkCode: Int,
        codePoint: Int = 0,
        modifiers: Int = modifierState,
    ) {
        exceptionHandler.catchExceptions {
            scene.dispatchNativeKeyEvent(TaoNativeWireFormat.KEY_DOWN, vkCode, codePoint, modifiers)
        }
        frame()
    }

    fun keyUp(
        vkCode: Int,
        codePoint: Int = 0,
        modifiers: Int = modifierState,
    ) {
        exceptionHandler.catchExceptions {
            scene.dispatchNativeKeyEvent(TaoNativeWireFormat.KEY_UP, vkCode, codePoint, modifiers)
        }
        frame()
    }

    fun pressKey(
        vkCode: Int,
        codePoint: Int = 0,
        modifiers: Int = modifierState,
    ) {
        keyDown(vkCode, codePoint, modifiers)
        keyUp(vkCode, codePoint, modifiers)
    }

    /** Types printable text through the same KEY_DOWN + synthetic KEY_TYPED path. */
    fun typeText(text: String) {
        for (ch in text) {
            val vk = ch.uppercaseChar().code
            pressKey(vkCode = vk, codePoint = ch.code)
        }
    }

    // ── IME input (macOS marked-text / NSTextInputClient wire) ──────────────

    /**
     * The active Compose text-input session request, captured through the same
     * `startInputMethod` hook the host's `TaoPlatformContext` implements.
     * Non-null while an editable field is focused.
     */
    @Volatile
    var inputMethodRequest: PlatformTextInputMethodRequest? = null

    /** Production IME routing under test — the host owns the same object. */
    val imeSession: TaoImeSession = TaoImeSession()

    /**
     * Simulates the IME updating the marked text (macOS `setMarkedText:`), as
     * delivered to the JVM by the native side. Mirrors the host's
     * `window.imePreedit` wiring. An empty [text] is `unmarkText`.
     */
    fun imePreedit(text: String) {
        exceptionHandler.catchExceptions { imeSession.preedit(text) }
        frame()
    }

    /**
     * Simulates the IME committing the composition (macOS `insertText:`
     * while marked text is active). Mirrors the host's `window.imeCommit`
     * wiring (`TextEditingScope.commitText`).
     */
    fun imeCommit(text: String) {
        exceptionHandler.catchExceptions { imeSession.commit(text) }
        frame()
    }

    /**
     * Simulates a replacement commit (macOS `insertText:` with a valid
     * `replacementRange`, outside a composition — the press-and-hold accent
     * picker replacing its base letter, #611/#612). [start] / [length] are
     * UTF-16 document-absolute offsets. Mirrors the host's
     * `window.imeReplaceCommit` wiring.
     */
    fun imeReplaceCommit(
        text: String,
        start: Long,
        length: Long,
    ) {
        imeSession.replaceCommit(text, start, length)
        frame()
    }

    /**
     * Named keys at the wire level: the native vk code each platform's event
     * source would actually put on the wire (kVK_* on macOS, XK_* keysyms on
     * Linux, AWT-compatible VK_* on Windows), so tests exercise the real
     * per-platform translation tables.
     */
    enum class NamedKey(
        val mac: Int,
        val linux: Int,
        val windows: Int,
        val typedCodePoint: Int = 0,
    ) {
        Enter(mac = 36, linux = 0xFF0D, windows = 10, typedCodePoint = 13),
        Tab(mac = 48, linux = 0xFF09, windows = 9, typedCodePoint = 9),
        Backspace(mac = 51, linux = 0xFF08, windows = 8, typedCodePoint = 8),
        Escape(mac = 53, linux = 0xFF1B, windows = 27, typedCodePoint = 27),
        ArrowLeft(mac = 123, linux = 0xFF51, windows = 37, typedCodePoint = 0xF702),
        ArrowRight(mac = 124, linux = 0xFF53, windows = 39, typedCodePoint = 0xF703),
        ArrowUp(mac = 126, linux = 0xFF52, windows = 38, typedCodePoint = 0xF700),
        ArrowDown(mac = 125, linux = 0xFF54, windows = 40, typedCodePoint = 0xF701),
    }

    fun pressKey(
        key: NamedKey,
        modifiers: Int = modifierState,
    ) {
        val vk =
            when (Platform.Current) {
                Platform.MacOS -> key.mac
                Platform.Linux -> key.linux
                else -> key.windows
            }
        keyDown(vk, key.typedCodePoint, modifiers)
        keyUp(vk, key.typedCodePoint, modifiers)
    }

    // ── Pixels ──────────────────────────────────────────────────────────────

    /** Rasterizes the last recorded frame (CPU) and returns it as a Skia bitmap. */
    fun renderToBitmap(
        clearColor: Int = COLOR_WHITE,
        surfaceProps: org.jetbrains.skia.SurfaceProps? = null,
    ): Bitmap {
        val picture = lastPicture ?: frame()
        val surface =
            Surface.makeRaster(
                ImageInfo.makeN32Premul(width, height),
                0,
                surfaceProps,
            )
        surface.canvas.clear(clearColor)
        surface.canvas.drawPicture(picture)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))
        surface.readPixels(bitmap, 0, 0)
        return bitmap
    }

    /** ARGB color of the pixel at ([x], [y]) in the last rendered frame. */
    fun pixelAt(
        x: Int,
        y: Int,
    ): Int = renderToBitmap().getColor(x, y)

    // ── Semantics ───────────────────────────────────────────────────────────

    fun semanticsRoots(): List<SemanticsNode> = owners.map { it.rootSemanticsNode }

    /** The captured owners themselves — for tests that drive [PlatformContext.SemanticsOwnerListener] consumers. */
    fun semanticsOwners(): List<SemanticsOwner> = owners.toList()

    fun allNodes(): List<SemanticsNode> {
        fun collect(
            node: SemanticsNode,
            out: MutableList<SemanticsNode>,
        ) {
            out += node
            node.children.forEach { collect(it, out) }
        }
        return buildList { semanticsRoots().forEach { collect(it, this) } }
    }

    fun nodeWithTag(tag: String): SemanticsNode =
        requireNotNull(allNodes().find { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) {
            "No semantics node with test tag '$tag'"
        }

    fun nodeWithText(text: String): SemanticsNode =
        requireNotNull(
            allNodes().find { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
            },
        ) { "No semantics node with text '$text'" }

    fun hasNodeWithText(text: String): Boolean =
        allNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
        }

    fun clickNode(node: SemanticsNode) {
        val center = node.boundsInRoot.center
        click(center.x, center.y)
    }

    fun close() {
        sceneBundle.close()
        dispatcher.pump()
    }

    private companion object {
        const val FIXED_POINT_SCALE = 1024f
        const val FRAME_DELTA_MILLIS = 16L
        const val NANOS_PER_MILLI = 1_000_000L
        const val COLOR_WHITE = 0xFFFFFFFF.toInt()

        /**
         * How many frames ahead a pending virtual timer keeps
         * [frameUntilIdle] framing. Covers the wheel-scroll gesture's
         * coalescing timeout (~100 ms) with margin, while staying far below
         * periodic housekeeping timers (caret blink at 500 ms).
         */
        const val TIMER_HORIZON_FRAMES = 12L
    }
}

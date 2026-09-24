package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.headful.MacTrackpadGestureProbe.Kind
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * #660 monkeys: random streams of real AppKit gesture NSEvents (magnify,
 * rotate, smart-magnify) interleaved with trackpad scroll gestures, one case
 * per (profile, seed), each checked against an exact model of the host.
 *
 * Unlike a layout monkey there *is* a right answer here: [GestureOracle]
 * replays every injected event through a reference copy of the host's rules —
 * the IOHID → `NSEventPhase` → wire phase mapping, the 1/10 000 fixed-point
 * value, `TaoTrackpadScaleSession`, and "the gesture that begins first owns
 * it" — and predicts the exact Scale stream Compose must see and how many
 * times the synthetic rotation contacts go down. The invariants:
 *
 *  - **Scale stream**: the ScaleStart / ScaleChange / ScaleEnd sequence at the
 *    root equals the oracle's, factor by factor;
 *  - **one pointer per Scale event**: Compose stamps the factor on every
 *    pointer and foundation multiplies it per pointer, so a second pointer is
 *    a double-counted zoom;
 *  - **no overlap**: no Scale event while a synthetic contact is pressed (a
 *    Scale event without them reads as their release — a touch tap);
 *  - **contacts**: exactly the oracle's number of touch downs (a re-press is
 *    a tap on whatever is under the finger), never more than two pressed;
 *  - **quiescence**: once every gesture is closed nothing stays pressed or
 *    open, and a canonical pinch zooms `Modifier.transformable` by exactly its
 *    factor;
 *  - **liveness**: `Dispatchers.Main` keeps answering ([MainLoopWatchdog]).
 *
 * Profiles: `trackpad` (well-formed gestures a real trackpad produces, mixed
 * in either order, with swipes and momentum), `chaos` (single events with
 * arbitrary phases — orphans, double Began, NSEventPhaseNone — extreme values,
 * no pause at all between some of them) and `burst` (well-formed gestures
 * posted back to back, hundreds deep, before the loop sees any). Every failure
 * carries the profile, the seed and the last actions;
 * `-Dnucleus.tao.headful.monkeySeed=<seed>` replays one.
 */
internal object MacOsTrackpadGestureMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        GestureMonkeyProfile.entries.flatMap { profile ->
            SEEDS.map { seed -> randomGesturesMatchTheModel(profile, seed, profile.steps) }
        } + randomGesturesMatchTheModel(GestureMonkeyProfile.CHAOS, LONG_RUN_SEED, LONG_RUN_STEPS) +
            DEGENERATE_ROTATIONS.map { (label, magnification) -> degenerateRotation(label, magnification) } +
            listOf(
                offscreenGestures(),
                windowClosesWithGesturesInFlight(),
                pinchWorksAfterAWindowClosedMidGesture(),
                rotationOnTheTitleBarNeverDragsTheWindow(),
            ) +
            nightWindows()

    /**
     * A rotation centred in the title bar puts its synthetic contacts on the
     * window-drag area, which arms a drag on any touch press — and the
     * macOS drag replays the last real mouseDown AppKit saw. After a real
     * click on the bar (which leaves that mouseDown saved), rotations there
     * must neither move nor maximize the window.
     */
    private fun rotationOnTheTitleBarNeverDragsTheWindow(): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        val frames = AtomicLong()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey degenerate: rotations on the title bar never drag the window",
            skip = { macOnly() ?: robotDriverSkipReason() },
            paintDefaultBackground = false,
            content = { NightContent(this, trace, zoom, frames) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("the animation renders") { frames.get() > NIGHT_WARMUP_FRAMES }
            settle()
            val scale = window.scaleFactor
            // No host listens on macOS: the hook only counts the drags the bar starts.
            val drags = AtomicLong()
            window.onDragWindow { drags.incrementAndGet() }
            val driver =
                RobotPointerDriver(window) {
                    IntSize((WINDOW_W * scale).toInt(), (WINDOW_H * scale).toInt())
                }
            repeat(TITLE_BAR_ROUNDS) { round ->
                // A real click on the bar, no drag: AppKit keeps that mouseDown.
                driver.click(Offset(TITLE_BAR_X * scale, TITLE_BAR_Y * scale))
                settle()
                val before = checkNotNull(bounds()).copyOf()
                val maximizedBefore = window.isMaximized
                gesture(Kind.ROTATE, 1, TITLE_BAR_X, TITLE_BAR_Y, 0.0)
                repeat(TITLE_BAR_ROTATE_STEPS) { gesture(Kind.ROTATE, 2, TITLE_BAR_X, TITLE_BAR_Y, 4.0) }
                gesture(Kind.ROTATE, 4, TITLE_BAR_X, TITLE_BAR_Y, 0.0)
                settle(TITLE_BAR_SETTLE_MILLIS)
                val after = bounds()
                check(after != null && after.contentEquals(before)) {
                    "round $round: a rotation on the title bar moved the window: " +
                        "${before.toList()} → ${after?.toList()}"
                }
                check(
                    window.isMaximized == maximizedBefore,
                ) { "round $round: a rotation on the title bar toggled maximize" }
                check(
                    drags.get() == 0L,
                ) { "round $round: the rotation's contacts started ${drags.get()} window drag(s)" }
                val touches = trace.snapshot().flatMap { e -> e.changes.filter { it.type == PointerType.Touch } }
                check(touches.isNotEmpty()) { "round $round: the rotation never reached the scene" }
            }
        }
    }

    /**
     * The overnight run: the three profiles back to back, seed after seed,
     * in a real `DecoratedWindow` with a `TitleBar` and content animating
     * every frame, for `-Dnucleus.tao.headful.monkeyNightMinutes=<n>` (one
     * short window without it). A fresh window every [NIGHT_WINDOW_MINUTES].
     * A quarter of the well-formed gestures are centred in the title bar, so
     * the synthetic contacts land on the window-drag area. On top of the
     * model: the window never moves, resizes, maximizes or goes fullscreen,
     * and the animation keeps producing frames between checkpoints.
     */
    private fun nightWindows(): List<TaoWindowTestCase> {
        val minutes = System.getProperty(NIGHT_MINUTES_PROPERTY)?.toLongOrNull()
        if (minutes == null || minutes <= 0) return listOf(nightWindow(0, 1, NIGHT_SMOKE_MILLIS))
        val count = ((minutes + NIGHT_WINDOW_MINUTES - 1) / NIGHT_WINDOW_MINUTES).toInt()
        return List(count) { index ->
            val left = minutes - index * NIGHT_WINDOW_MINUTES
            nightWindow(index, count, minOf(left, NIGHT_WINDOW_MINUTES) * MILLIS_PER_MINUTE)
        }
    }

    private fun nightWindow(
        index: Int,
        count: Int,
        durationMillis: Long,
    ): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        val frames = AtomicLong()
        return TaoWindowTestCase(
            name =
                "#660 macOS gesture monkey night window ${index + 1}/$count: ${durationMillis / 1000}s " +
                    "in a real decorated window with title bar and animation",
            timeoutMillis = durationMillis + NIGHT_SLACK_MILLIS,
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { NightContent(this, trace, zoom, frames) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("the animation renders") { frames.get() > NIGHT_WARMUP_FRAMES }
            settle()
            val initialBounds = checkNotNull(bounds()).copyOf()
            var lastFrames = frames.get()
            val drags = AtomicLong()
            window.onDragWindow { drags.incrementAndGet() }
            val chrome: () -> String? = {
                val b = bounds()
                val f = frames.get()
                when {
                    b == null -> "the window is gone"
                    !b.contentEquals(initialBounds) ->
                        "the window moved or resized: ${initialBounds.toList()} → ${b.toList()}"
                    window.isMaximized -> "the window maximized"
                    window.isFullscreen -> "the window went fullscreen"
                    drags.get() != 0L -> "synthetic contacts started ${drags.get()} window drag(s)"
                    f <= lastFrames -> "no frame rendered since the last checkpoint ($f)"
                    else -> {
                        lastFrames = f
                        null
                    }
                }
            }
            val deadline = System.currentTimeMillis() + durationMillis
            val base = monkeySeedOr(NIGHT_SEED) + index * NIGHT_SEEDS_PER_WINDOW
            var session = 0
            val runtime = Runtime.getRuntime()
            while (System.currentTimeMillis() < deadline) {
                val profile = GestureMonkeyProfile.entries[session % GestureMonkeyProfile.entries.size]
                GestureMonkey(
                    scope = this,
                    trace = trace,
                    zoom = zoom,
                    profile = profile,
                    seed = base + session,
                    steps = profile.steps,
                    titleBarBand = NIGHT_TITLE_BAR_BAND_DP,
                    extraCheck = chrome,
                    echo = false,
                    canonicalAt = NIGHT_BODY_POINT,
                ).run()
                session++
                if (session % NIGHT_REPORT_EVERY == 0) {
                    System.gc()
                    System.err.println(
                        "[gesture-monkey-night] window ${index + 1}/$count: $session sessions, " +
                            "frames=${frames.get()}, heap=${(runtime.totalMemory() - runtime.freeMemory()) shr 20} MB",
                    )
                }
            }
            System.err.println("[gesture-monkey-night] window ${index + 1}/$count survived $session sessions")
        }
    }

    @Composable
    private fun NightContent(
        scope: TaoDecoratedWindowScope,
        trace: GestureTrace,
        zoom: ZoomProbe,
        frames: AtomicLong,
    ) {
        val transition = rememberInfiniteTransition(label = "night")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
            label = "spin",
        )
        val pulse by transition.animateFloat(
            initialValue = PULSE_MIN,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
            label = "pulse",
        )
        LaunchedEffect(Unit) { while (true) withFrameNanos { frames.incrementAndGet() } }
        val state =
            rememberTransformableState {
                _,
                zoomChange,
                _,
                rotationChange,
                ->
                zoom.apply(zoomChange, rotationChange)
            }
        Box(
            Modifier.fillMaxSize().pointerInput(trace) {
                awaitPointerEventScope {
                    while (true) trace.add(awaitPointerEvent(PointerEventPass.Initial))
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                with(scope) {
                    TitleBar { _ ->
                        Box(Modifier.width(TITLE_BAR_PULSE_DP.dp * pulse).height(10.dp).background(Color.Cyan))
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF15181D))
                        .transformable(state),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(SPINNER_DP.dp)
                            .graphicsLayer {
                                rotationZ = angle
                                alpha = pulse
                            }.background(Color.Magenta),
                    )
                }
            }
        }
    }

    /**
     * Gestures centred far outside the window (and absurd rotations): the
     * contacts land nowhere the scene can hit-test. Nothing may throw, no
     * non-finite transform may reach the content, and an in-window pinch
     * afterwards must be exact.
     */
    private fun offscreenGestures(): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey degenerate: gestures centred far outside the window",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Target(trace, zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val random = Random(monkeySeedOr(OFFSCREEN_SEED))
            repeat(OFFSCREEN_STEPS) {
                val (x, y) = OFFSCREEN_POINTS[random.nextInt(OFFSCREEN_POINTS.size)]
                val kind = random.nextInt(3)
                val phase = intArrayOf(0, 1, 2, 2, 4, 8)[random.nextInt(6)]
                val value =
                    if (kind ==
                        Kind.ROTATE
                    ) {
                        (random.nextDouble() - 0.5) * 2e6
                    } else {
                        random.nextInt(-256, 768) / 256.0
                    }
                gesture(kind, phase, x, y, value)
                if (it % DEGENERATE_FLUSH_EVERY == 0) settle(DEGENERATE_FLUSH_MILLIS)
            }
            gesture(Kind.MAGNIFY, 4, TARGET_X, TARGET_Y, 0.0)
            gesture(Kind.ROTATE, 4, TARGET_X, TARGET_Y, 0.0)
            settle()
            zoom.badChange?.let { error("transformable received a non-finite transform: $it") }
            canonicalPinch(zoom)
        }
    }

    /**
     * The window closes while a rotation owns a pinch and hundreds of gesture
     * events are still queued for it: the queued NSEvents name a window that
     * is gone. Nothing may crash (a native use-after-free kills the suite
     * here) — [pinchWorksAfterAWindowClosedMidGesture] runs right after.
     */
    private fun windowClosesWithGesturesInFlight(): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey degenerate: the window closes with gestures in flight",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Target(trace, zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            gesture(Kind.ROTATE, 1, TARGET_X, TARGET_Y, 0.0)
            gesture(Kind.MAGNIFY, 1, TARGET_X, TARGET_Y, 0.0)
            settle(DEGENERATE_FLUSH_MILLIS)
            // Queued, never flushed: the case returns and the window closes under them.
            repeat(IN_FLIGHT_EVENTS) {
                gesture(if (it % 2 == 0) Kind.MAGNIFY else Kind.ROTATE, 2, TARGET_X, TARGET_Y, 0.01)
            }
        }
    }

    private fun pinchWorksAfterAWindowClosedMidGesture(): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey degenerate: a new window pinches after one closed mid-gesture",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Target(trace, zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            check(trace.snapshot().none { e -> e.isScale || e.changes.any { it.type == PointerType.Touch } }) {
                "the closed window's queued gestures leaked into this one: ${trace.snapshot()}"
            }
            canonicalPinch(zoom)
        }
    }

    private suspend fun TaoWindowTestScope.canonicalPinch(zoom: ZoomProbe) {
        val before = zoom.logZoom
        gesture(Kind.MAGNIFY, 1, TARGET_X, TARGET_Y, 0.0)
        gesture(Kind.MAGNIFY, 2, TARGET_X, TARGET_Y, CANONICAL_PINCH)
        gesture(Kind.MAGNIFY, 4, TARGET_X, TARGET_Y, 0.0)
        settle()
        val ratio = kotlin.math.exp(zoom.logZoom - before)
        check(abs(ratio - (1 + CANONICAL_PINCH)) <= CANONICAL_TOLERANCE) {
            "an in-window pinch zoomed by $ratio instead of ${1 + CANONICAL_PINCH}"
        }
    }

    /**
     * A rotation that owns the fingers folds every magnify into the contacts'
     * spacing. Hundreds of floored collapses (or ×4 expansions) drive that
     * spacing to 0 or past Float range: the contacts must stay finite points,
     * `transformable` must never see a non-finite transform, and the pipeline
     * must still pinch afterwards.
     */
    private fun degenerateRotation(
        label: String,
        magnification: Double,
    ): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey degenerate rotation: $DEGENERATE_STEPS magnifies $label the contacts",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Target(trace, zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val x = TARGET_X
            val y = TARGET_Y
            gesture(Kind.ROTATE, 1, x, y, 0.0)
            gesture(Kind.ROTATE, 2, x, y, DEGENERATE_ROTATE_DEGREES)
            repeat(DEGENERATE_STEPS) {
                gesture(Kind.MAGNIFY, 2, x, y, magnification)
                gesture(Kind.ROTATE, 2, x, y, DEGENERATE_ROTATE_DEGREES)
                if (it % DEGENERATE_FLUSH_EVERY == 0) settle(DEGENERATE_FLUSH_MILLIS)
            }
            gesture(Kind.ROTATE, 4, x, y, 0.0)
            settle()
            zoom.badChange?.let { error("transformable received a non-finite transform: $it") }
            val events = trace.snapshot()
            val touches = events.flatMap { e -> e.changes.filter { it.type == PointerType.Touch } }
            check(touches.isNotEmpty()) { "the rotation never reached the scene" }
            check(!touches.last().pressed) { "the contacts are still pressed: ${events.takeLast(4)}" }
            check(
                events.none { it.isScale },
            ) { "a magnify inside a rotation must not scale: ${events.filter { it.isScale }}" }
            canonicalPinch(zoom)
        }
    }

    private fun TaoWindowTestScope.gesture(
        kind: Int,
        phase: Int,
        x: Float,
        y: Float,
        value: Double,
    ) {
        check(MacTrackpadGestureProbe.inject(window, kind, phase, x, y, value)) { "the gesture injector refused" }
    }

    private fun randomGesturesMatchTheModel(
        profile: GestureMonkeyProfile,
        seed: Long,
        steps: Int,
    ): TaoWindowTestCase {
        val trace = GestureTrace()
        val zoom = ZoomProbe()
        return TaoWindowTestCase(
            name = "#660 macOS gesture monkey ${profile.label} seed $seed: $steps random gesture steps match the model",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Target(trace, zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val monkey = GestureMonkey(this, trace, zoom, profile, monkeySeedOr(seed), steps)
            monkey.run()
        }
    }

    @Composable
    private fun Target(
        trace: GestureTrace,
        zoom: ZoomProbe,
    ) {
        val state =
            rememberTransformableState {
                _,
                zoomChange,
                _,
                rotationChange,
                ->
                zoom.apply(zoomChange, rotationChange)
            }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(trace) {
                    awaitPointerEventScope {
                        while (true) trace.add(awaitPointerEvent(PointerEventPass.Initial))
                    }
                }.transformable(state),
        )
    }

    private fun monkeySeedOr(default: Long): Long = System.getProperty(MONKEY_SEED_PROPERTY)?.toLongOrNull() ?: default

    private fun macOnly(): String? =
        when {
            Platform.Current != Platform.MacOS -> "macOS only — AppKit gesture NSEvent injection"
            !MacTrackpadGestureProbe.available -> "nucleus_tao_metal not loaded"
            else -> null
        }

    private val SEEDS = longArrayOf(MONKEY_DEFAULT_SEED, 42L, 7L)

    private const val WINDOW_W = 800f
    private const val WINDOW_H = 600f
    private const val TITLE_BAR_X = 600f
    private const val TITLE_BAR_Y = 20f
    private const val TITLE_BAR_ROUNDS = 3
    private const val TITLE_BAR_ROTATE_STEPS = 6
    private const val TITLE_BAR_SETTLE_MILLIS = 700L

    private const val NIGHT_MINUTES_PROPERTY = "nucleus.tao.headful.monkeyNightMinutes"
    private const val NIGHT_WINDOW_MINUTES = 15L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val NIGHT_SMOKE_MILLIS = 60_000L
    private const val NIGHT_SLACK_MILLIS = 600_000L
    private const val NIGHT_SEED = 20_260_924L
    private const val NIGHT_SEEDS_PER_WINDOW = 100_000L
    private const val NIGHT_WARMUP_FRAMES = 10L
    private const val NIGHT_REPORT_EVERY = 10

    /** Inside the macOS title bar (the bar is ~40 dp): contacts land on the window-drag area. */
    private val NIGHT_TITLE_BAR_BAND_DP = 14f..30f
    private val NIGHT_BODY_POINT = 400f to 360f
    private const val FULL_TURN = 360f
    private const val SPIN_MILLIS = 2_000
    private const val PULSE_MILLIS = 700
    private const val PULSE_MIN = 0.2f
    private const val TITLE_BAR_PULSE_DP = 120
    private const val SPINNER_DP = 160
    private val DEGENERATE_ROTATIONS = listOf("collapse" to -1.5, "explode" to 3.0)
    private const val DEGENERATE_STEPS = 300
    private const val OFFSCREEN_SEED = 660L
    private const val OFFSCREEN_STEPS = 400
    private const val IN_FLIGHT_EVENTS = 200
    private val OFFSCREEN_POINTS =
        listOf(-5_000f to 300f, 400f to -5_000f, 1e6f to 1e6f, -1e6f to 1e6f, 799f to 599f, 0f to 0f, 1e7f to -1e7f)
    private const val DEGENERATE_ROTATE_DEGREES = 3.0
    private const val DEGENERATE_FLUSH_EVERY = 20
    private const val DEGENERATE_FLUSH_MILLIS = 16L
    private const val TARGET_X = 400f
    private const val TARGET_Y = 300f
    private const val CANONICAL_PINCH = 0.125
    private const val CANONICAL_TOLERANCE = 1e-3
    private const val LONG_RUN_SEED = 1_000_003L
    private const val LONG_RUN_STEPS = 2_000
}

private enum class GestureMonkeyProfile(
    val label: String,
    val steps: Int,
) {
    /** Well-formed gestures, one gesture per step. */
    TRACKPAD("trackpad", 60),

    /** One arbitrary event per step. */
    CHAOS("chaos", 500),

    /** Well-formed pinch / rotate gestures posted back to back, one gesture per step. */
    BURST("burst", 80),
}

/**
 * What `transformable` applied: the zoom in log space (hundreds of extreme
 * factors overflow a Float product), and the first change that was not a
 * finite positive ratio.
 */
private class ZoomProbe {
    @Volatile var logZoom: Double = 0.0

    @Volatile var badChange: String? = null

    fun apply(
        zoomChange: Float,
        rotationChange: Float,
    ) {
        if (!zoomChange.isFinite() || zoomChange <= 0f || !rotationChange.isFinite()) {
            if (badChange == null) badChange = "zoomChange=$zoomChange rotationChange=$rotationChange"
            return
        }
        logZoom += kotlin.math.ln(zoomChange.toDouble())
    }
}

/** One pointer change as the root saw it. */
private class TracedChange(
    val id: Long,
    val type: PointerType,
    val pressed: Boolean,
    val down: Boolean,
    val scaleFactor: Float,
)

private class TracedEvent(
    val type: PointerEventType,
    val changes: List<TracedChange>,
) {
    val isScale: Boolean
        get() =
            type == PointerEventType.ScaleStart ||
                type == PointerEventType.ScaleChange ||
                type == PointerEventType.ScaleEnd

    override fun toString(): String =
        when (type) {
            PointerEventType.ScaleChange -> "ScaleChange(${changes.firstOrNull()?.scaleFactor})"
            else ->
                "$type" +
                    changes.filter { it.type == PointerType.Touch }.joinToString("", prefix = "") {
                        "[t${it.id and 0xF}${if (it.pressed) "↓" else "↑"}]"
                    }
        }
}

/** Every pointer event the root saw on the Initial pass, in order. */
private class GestureTrace {
    private val events = Collections.synchronizedList(mutableListOf<TracedEvent>())

    fun add(event: PointerEvent) {
        events +=
            TracedEvent(
                event.type,
                event.changes.map {
                    TracedChange(it.id.value, it.type, it.pressed, it.changedToDownIgnoreConsumed(), it.scaleFactor)
                },
            )
    }

    fun snapshot(): List<TracedEvent> = synchronized(events) { events.toList() }

    fun reset() = events.clear()
}

// ── Actions ─────────────────────────────────────────────────────────────────

/** IOHID phase encodings the injectors take. */
private object IoPhase {
    const val NONE = 0
    const val BEGAN = 1
    const val CHANGED = 2
    const val ENDED = 4
    const val CANCELLED = 8
}

private const val SCROLL_BEGAN = 1
private const val SCROLL_CHANGED = 2
private const val SCROLL_ENDED = 4
private const val MOMENTUM_BEGAN = 1
private const val MOMENTUM_CHANGED = 2
private const val MOMENTUM_ENDED = 3

private sealed class GestureAction {
    /** Content-local injection point, dp. */
    abstract val x: Float
    abstract val y: Float

    data class Magnify(
        val phase: Int,
        val value: Double,
        override val x: Float,
        override val y: Float,
    ) : GestureAction()

    data class Rotate(
        val phase: Int,
        val degrees: Double,
        override val x: Float,
        override val y: Float,
    ) : GestureAction()

    data class Smart(
        override val x: Float,
        override val y: Float,
    ) : GestureAction()

    /** A precise (trackpad) scroll step: scroll-phase / momentum-phase encodings of [MacScrollWheelProbe]. */
    data class Scroll(
        val phase: Int,
        val momentum: Int,
        val dx: Float,
        val dy: Float,
        override val x: Float,
        override val y: Float,
    ) : GestureAction()

    /** No pause before the next action. */
    var immediate: Boolean = false
}

// ── Oracle ──────────────────────────────────────────────────────────────────

/**
 * Reference model of `TaoComposeSceneHost.onTrackpadGesture` + the
 * `touchpad_gestures.m` / Rust wire. Kept deliberately independent of the
 * production classes: it restates the rules, so a change to either side that
 * the other does not follow turns a monkey red.
 */
private class GestureOracle {
    /** Expected Scale events: type and, for a change, the factor. */
    val scale = mutableListOf<Pair<PointerEventType, Float>>()

    /** Expected touch-down transitions of the synthetic contacts. */
    var downs = 0
        private set

    var scaleOpen = false
        private set
    var rotateActive = false
        private set

    /** An interrupted rotation ignores its remaining steps until it ends. */
    private var rotateInterrupted = false

    /** A phased trackpad scroll opened a pan the router has not closed yet (see [panSettled]). */
    var panOpen = false
        private set

    /** Last cursor position the host dispatched, dp (its 1 dp deadband). */
    private var cursor: Pair<Float, Float>? = null

    fun apply(action: GestureAction) {
        when (action) {
            is GestureAction.Magnify -> magnify(wirePhase(action.phase), action.value)
            is GestureAction.Rotate -> rotate(wirePhase(action.phase))
            is GestureAction.Smart -> smart()
            is GestureAction.Scroll -> scroll(action)
        }
    }

    /** The router's grace ran out: the generator waited long enough for the PanEnd. */
    fun panSettled() {
        panOpen = false
    }

    private fun scroll(action: GestureAction.Scroll) {
        // tao moves the cursor before it delivers the scroll; a move past the
        // deadband reaches the scene, and a mouse-only event interrupts a rotation.
        val last = cursor
        val dx = last?.let { action.x - it.first } ?: Float.MAX_VALUE
        val dy = last?.let { action.y - it.second } ?: 0f
        if (last == null || dx * dx + dy * dy >= 1f) {
            cursor = action.x to action.y
            if (rotateActive) {
                rotateActive = false
                rotateInterrupted = true
            }
        }
        // A rotation owns the fingers: its scroll is dropped.
        if (rotateActive) return
        when (action.phase) {
            SCROLL_BEGAN, SCROLL_CHANGED -> panOpen = true
            0 -> if (action.momentum == 0) panOpen = false // a phase-less scroll closes the pan now
        }
    }

    private fun magnify(
        phase: WirePhase,
        value: Double,
    ) {
        if (rotateActive) return // folded into the contacts: a touch Move, no Scale
        when (phase) {
            WirePhase.BEGAN -> {
                open()
                change(factor(value))
            }
            WirePhase.CHANGED -> change(factor(value))
            WirePhase.ENDED, WirePhase.CANCELLED -> close()
        }
    }

    private fun rotate(phase: WirePhase) {
        if (phase == WirePhase.ENDED || phase == WirePhase.CANCELLED) {
            rotateInterrupted = false
            rotateActive = false
            return
        }
        if (scaleOpen || panOpen) return
        if (phase == WirePhase.BEGAN) {
            rotateInterrupted = false
        } else if (rotateInterrupted) {
            return
        }
        // A second Began re-presses already pressed contacts: filtered as no change.
        if (!rotateActive) downs += 2
        rotateActive = true
    }

    private fun smart() {
        if (rotateActive || scaleOpen) return
        open()
        change(SMART_MAGNIFY_FACTOR)
        close()
    }

    private fun open() {
        if (scaleOpen) return
        scaleOpen = true
        scale += PointerEventType.ScaleStart to 1f
    }

    private fun change(factor: Float) {
        if (factor == 1f) return
        open()
        scale += PointerEventType.ScaleChange to factor
    }

    private fun close() {
        if (!scaleOpen) return
        scaleOpen = false
        scale += PointerEventType.ScaleEnd to 1f
    }

    private enum class WirePhase { BEGAN, CHANGED, ENDED, CANCELLED }

    /** IOHID → NSEventPhase → `touchpad_gestures.m`'s `phase_from_event` (None → Changed). */
    private fun wirePhase(ioPhase: Int): WirePhase =
        when (ioPhase) {
            IoPhase.BEGAN -> WirePhase.BEGAN
            IoPhase.ENDED -> WirePhase.ENDED
            IoPhase.CANCELLED -> WirePhase.CANCELLED
            else -> WirePhase.CHANGED
        }

    /** Rust truncates `value × 10 000` to an int; the host divides back in Float. */
    private fun factor(value: Double): Float {
        val fixed = (value * VALUE_FIXED_SCALE).toInt()
        val delta = fixed / VALUE_FIXED_SCALE.toFloat()
        return (1f + delta).coerceAtLeast(MIN_GESTURE_SCALE)
    }

    private companion object {
        const val VALUE_FIXED_SCALE = 10_000.0
        const val MIN_GESTURE_SCALE = 0.05f
        const val SMART_MAGNIFY_FACTOR = 1.5f
    }
}

// ── Driver ──────────────────────────────────────────────────────────────────

@Suppress("LongParameterList")
private class GestureMonkey(
    private val scope: TaoWindowTestScope,
    private val trace: GestureTrace,
    private val zoom: ZoomProbe,
    private val profile: GestureMonkeyProfile,
    seed: Long,
    private val steps: Int,
    /** When set, a quarter of the well-formed gestures are centred at a y in this band (dp). */
    private val titleBarBand: ClosedFloatingPointRange<Float>? = null,
    /** Extra invariant run at every checkpoint: a failure reason, or null. */
    private val extraCheck: (() -> String?)? = null,
    echo: Boolean = true,
    /** Where the closing canonical pinch lands (a `transformable` must be under it); random by default. */
    private val canonicalAt: Pair<Float, Float>? = null,
) {
    private val random = Random(seed)
    private val journal = MonkeyJournal("gesture-monkey[${profile.label}]", seed, echo = echo)
    private val oracle = GestureOracle()
    private var scrollOpen = false
    private var lastScroll: Pair<Float, Float>? = null

    suspend fun run() {
        System.err.println("[gesture-monkey] profile=${profile.label} seed=${journal.seed} steps=$steps")
        trace.reset()
        val watchdog = MainLoopWatchdog("gesture-monkey") { journal.report() }.start()
        try {
            for (step in 0 until steps) {
                journal.step = step
                val actions =
                    when (profile) {
                        GestureMonkeyProfile.TRACKPAD -> wellFormedGesture(withScroll = true)
                        GestureMonkeyProfile.BURST ->
                            wellFormedGesture(
                                withScroll = false,
                            ).onEach { it.immediate = true }
                        GestureMonkeyProfile.CHAOS -> listOf(chaosEvent())
                    }
                monkeyAction({ "step $step (${actions.size} events)" }) { perform(actions) }
                if (actions.any { it is GestureAction.Scroll && it.phase != 0 }) {
                    // Let the router's grace close the pan before the next gesture can rotate.
                    scope.settle(PAN_GRACE_MILLIS)
                    oracle.panSettled()
                }
                if (step % CHECKPOINT_EVERY == CHECKPOINT_EVERY - 1) checkpoint()
            }
            quiesce()
        } finally {
            val worst = watchdog.stop()
            check(worst < MONKEY_MAX_STALL_MILLIS) {
                journal.failure("Dispatchers.Main stalled for ${worst}ms", state())
            }
        }
        System.err.println(
            "[gesture-monkey] profile=${profile.label} seed=${journal.seed} survived $steps steps; " +
                "reached ${journal.reachedSummary()}",
        )
    }

    // ── Generators ──────────────────────────────────────────────────────────

    private fun wellFormedGesture(withScroll: Boolean): List<GestureAction> {
        val (cx, cy) = center()
        val kinds = if (withScroll) GESTURES_WITH_SCROLL else GESTURES
        val kind = kinds[random.nextInt(kinds.size)]
        journal.reach(kind)
        val script = GestureScript(random, cx, cy, steps = 1 + random.nextInt(MAX_GESTURE_STEPS))
        if (withScroll) script.out += cursorTo(cx, cy)
        with(script) {
            when (kind) {
                "pinch" -> single(::mag)
                "rotate" -> single(::rot)
                "pinch+rotate" -> interleaved(::mag, ::rot)
                "rotate+pinch" -> interleaved(::rot, ::mag)
                "smart" -> out += GestureAction.Smart(cx, cy)
                "swipe" -> swipeAlone()
                "pinch+swipe" -> withSwipe(::mag, swipeFirst = false)
                "rotate+swipe" -> withSwipe(::rot, swipeFirst = false)
                "swipe+rotate" -> withSwipe(::rot, swipeFirst = true)
            }
        }
        return script.out
    }

    /**
     * Builds one well-formed gesture around ([cx], [cy]). Gesture events
     * jitter a few dp; scrolls stay on the cursor — it does not move while
     * fingers gesture.
     */
    private class GestureScript(
        private val random: Random,
        private val cx: Float,
        private val cy: Float,
        private val steps: Int,
    ) {
        val out = mutableListOf<GestureAction>()

        private fun jx() = cx + random.nextInt(-JITTER_DP, JITTER_DP + 1)

        private fun jy() = cy + random.nextInt(-JITTER_DP, JITTER_DP + 1)

        fun mag(phase: Int): GestureAction =
            GestureAction.Magnify(phase, if (phase == IoPhase.CHANGED) pinchStep() else 0.0, jx(), jy())

        fun rot(phase: Int): GestureAction =
            GestureAction.Rotate(phase, if (phase == IoPhase.CHANGED) rotateStep() else 0.0, jx(), jy())

        private fun swipe(phase: Int): GestureAction {
            fun delta() = if (phase == SCROLL_CHANGED) random.nextInt(-SWIPE_PT, SWIPE_PT + 1).toFloat() else 0f
            return GestureAction.Scroll(phase = phase, momentum = 0, dx = delta(), dy = delta(), x = cx, y = cy)
        }

        private fun end(): Int = if (random.nextInt(CANCEL_ONE_IN) == 0) IoPhase.CANCELLED else IoPhase.ENDED

        private fun pinchStep(): Double = random.nextInt(-PINCH_STEP, PINCH_STEP + 1) / DYADIC.toDouble()

        private fun rotateStep(): Double = random.nextInt(-ROTATE_STEP, ROTATE_STEP + 1).toDouble()

        fun single(g: (Int) -> GestureAction) {
            out += g(IoPhase.BEGAN)
            repeat(steps) { out += g(IoPhase.CHANGED) }
            out += g(end())
        }

        /** Both recognizers: [a] begins first, [b] possibly a few steps late; either may end first. */
        fun interleaved(
            a: (Int) -> GestureAction,
            b: (Int) -> GestureAction,
        ) {
            out += a(IoPhase.BEGAN)
            repeat(random.nextInt(LATE_START_MAX)) { out += a(IoPhase.CHANGED) }
            out += b(IoPhase.BEGAN)
            repeat(steps) { out += if (random.nextBoolean()) a(IoPhase.CHANGED) else b(IoPhase.CHANGED) }
            val (first, second) = if (random.nextBoolean()) a to b else b to a
            out += first(end())
            out += second(end())
        }

        fun swipeAlone() {
            single(::swipe)
            out.removeAt(out.lastIndex)
            out += swipe(SCROLL_ENDED)
            if (random.nextBoolean()) {
                out += GestureAction.Scroll(0, MOMENTUM_BEGAN, 0f, SWIPE_PT.toFloat(), cx, cy)
                out += GestureAction.Scroll(0, MOMENTUM_CHANGED, 0f, 2f, cx, cy)
                out += GestureAction.Scroll(0, MOMENTUM_ENDED, 0f, 0f, cx, cy)
            }
        }

        /**
         * Fingers that travel while pinching / rotating: AppKit sends both
         * streams. With [swipeFirst] the pan owns the fingers and a rotation
         * must not press.
         */
        fun withSwipe(
            g: (Int) -> GestureAction,
            swipeFirst: Boolean,
        ) {
            if (swipeFirst) {
                out += swipe(SCROLL_BEGAN)
                out += g(IoPhase.BEGAN)
            } else {
                out += g(IoPhase.BEGAN)
                out += swipe(SCROLL_BEGAN)
            }
            repeat(steps) { out += if (random.nextBoolean()) g(IoPhase.CHANGED) else swipe(SCROLL_CHANGED) }
            out += swipe(SCROLL_ENDED)
            out += g(end())
        }
    }

    /** A zero, phase-less scroll: tao moves the cursor there first, nothing scrolls. */
    private fun cursorTo(
        x: Float,
        y: Float,
    ): GestureAction = GestureAction.Scroll(0, 0, 0f, 0f, x, y)

    private fun chaosEvent(): GestureAction {
        val (x, y) = center()
        val action =
            when (random.nextInt(CHAOS_KINDS)) {
                0, 1, 2 -> GestureAction.Magnify(chaosPhase(), chaosMagnification(), x, y)
                3, 4 -> GestureAction.Rotate(chaosPhase(), (random.nextDouble() - 0.5) * CHAOS_MAX_DEGREES, x, y)
                5 -> GestureAction.Smart(x, y)
                else -> chaosScroll(x, y)
            }
        action.immediate = random.nextInt(IMMEDIATE_ONE_IN) != 0
        journal.reach(action::class.simpleName ?: "?")
        return action
    }

    private fun chaosPhase(): Int = CHAOS_PHASES[random.nextInt(CHAOS_PHASES.size)]

    /** Dyadic, so `value × 10 000` is exact whatever precision the CGEvent field keeps. */
    private fun chaosMagnification(): Double =
        when (random.nextInt(4)) {
            0 -> 0.0
            1 -> random.nextInt(-DYADIC, DYADIC + 1) / DYADIC.toDouble() / 8 // small: ±1/8
            2 -> random.nextInt(-DYADIC, DYADIC * 3) / DYADIC.toDouble() // wild: -1 … 3
            else -> -random.nextInt(DYADIC, DYADIC * 2) / DYADIC.toDouble() // collapses: ≤ -1, floored
        }

    /**
     * Phase-less only: a phased pan's end is a timer the model cannot place
     * in a burst of events, and a phase-less scroll still moves the cursor —
     * which is what interrupts a rotation. Phased pans are the trackpad
     * profile's.
     */
    private fun chaosScroll(
        x: Float,
        y: Float,
    ): GestureAction {
        // Half of them on the cursor's last position: a scroll that does not move it.
        val here = lastScroll?.takeIf { random.nextBoolean() }
        return GestureAction.Scroll(
            0,
            0,
            random.nextInt(-CHAOS_SCROLL_PT, CHAOS_SCROLL_PT + 1).toFloat(),
            random.nextInt(-CHAOS_SCROLL_PT, CHAOS_SCROLL_PT + 1).toFloat(),
            here?.first ?: x,
            here?.second ?: y,
        )
    }

    /**
     * A gesture centre far enough inside the window that the synthetic
     * contacts (120 px either side, 60 dp on a 2× display) press inside it.
     */
    private fun center(): Pair<Float, Float> {
        val x = MARGIN_DP + random.nextFloat() * (WINDOW_W_DP - 2 * MARGIN_DP)
        val band = titleBarBand
        if (band != null && profile != GestureMonkeyProfile.CHAOS && random.nextInt(TITLE_BAR_ONE_IN) == 0) {
            journal.reach("title bar")
            return x to band.start + random.nextFloat() * (band.endInclusive - band.start)
        }
        return x to (MARGIN_DP + random.nextFloat() * (WINDOW_H_DP - 2 * MARGIN_DP))
    }

    // ── Execution ───────────────────────────────────────────────────────────

    private suspend fun perform(actions: List<GestureAction>) {
        for (action in actions) {
            journal.record(action)
            when (action) {
                is GestureAction.Magnify -> post(Kind.MAGNIFY, action.phase, action.x, action.y, action.value)
                is GestureAction.Rotate -> post(Kind.ROTATE, action.phase, action.x, action.y, action.degrees)
                is GestureAction.Smart -> post(Kind.SMART_MAGNIFY, IoPhase.NONE, action.x, action.y, 0.0)
                is GestureAction.Scroll -> scroll(action)
            }
            oracle.apply(action)
            if (!action.immediate) scope.settle(STEP_MILLIS)
        }
    }

    private fun post(
        kind: Int,
        phase: Int,
        x: Float,
        y: Float,
        value: Double,
    ) {
        check(MacTrackpadGestureProbe.inject(scope.window, kind, phase, x, y, value)) {
            journal.failure("nativeDiagInjectTrackpadGesture refused the event", state())
        }
    }

    /**
     * The scroll injector is synchronous while gesture events are posted, and
     * tao buffers the events it raises from inside a loop callback (the
     * cursor move, the wheel) until that callback returns — while a gesture
     * posted meanwhile reaches the host straight from the monitor. Flush
     * both ways so the two streams arrive in the order the model applies
     * them; real events are dispatched one by one and never race like this.
     */
    private suspend fun scroll(action: GestureAction.Scroll) {
        scope.settle(FLUSH_MILLIS)
        val delivered =
            MacScrollWheelProbe.inject(
                window = scope.window,
                x = action.x,
                y = action.y,
                dx = action.dx,
                dy = action.dy,
                precise = true,
                phase = action.phase,
                momentum = action.momentum,
            )
        check(delivered) { journal.failure("nativeDiagInjectScrollWheel refused the event", state()) }
        scope.settle(FLUSH_MILLIS)
        scrollOpen = action.phase == SCROLL_BEGAN || action.phase == SCROLL_CHANGED
        lastScroll = action.x to action.y
    }

    // ── Invariants ──────────────────────────────────────────────────────────

    private suspend fun checkpoint() {
        scope.settle(FLUSH_MILLIS)
        verify("checkpoint")
        extraCheck?.invoke()?.let { fail("checkpoint", it) }
    }

    /** Closes whatever the walk left open, lets every timer run out, then checks the rest state. */
    private suspend fun quiesce() {
        journal.step = steps
        val (x, y) = center()
        perform(
            listOf(
                GestureAction.Magnify(IoPhase.ENDED, 0.0, x, y),
                GestureAction.Rotate(IoPhase.ENDED, 0.0, x, y),
            ),
        )
        if (scrollOpen) {
            val ended = GestureAction.Scroll(SCROLL_ENDED, 0, 0f, 0f, lastScroll?.first ?: x, lastScroll?.second ?: y)
            journal.record(ended)
            scroll(ended)
            oracle.apply(ended)
        }
        scope.settle(QUIESCE_MILLIS)
        oracle.panSettled()
        verify("quiescence")
        val events = trace.snapshot()
        check(!oracle.scaleOpen && !oracle.rotateActive) { journal.failure("the model left a gesture open", state()) }
        check(pressedTouches(events).isEmpty()) {
            journal.failure("synthetic contacts still pressed at rest: ${pressedTouches(events)}", state())
        }
        val panStarts = events.count { it.type == PointerEventType.PanStart }
        val panEnds = events.count { it.type == PointerEventType.PanEnd }
        check(panStarts == panEnds) {
            journal.failure("unbalanced pan: $panStarts PanStart vs $panEnds PanEnd", state())
        }

        extraCheck?.invoke()?.let { fail("quiescence", it) }

        // The pipeline still works: a canonical pinch zooms by exactly its factor.
        val (cx, cy) = canonicalAt ?: (x to y)
        val before = zoom.logZoom
        perform(
            listOf(
                GestureAction.Magnify(IoPhase.BEGAN, 0.0, cx, cy),
                GestureAction.Magnify(IoPhase.CHANGED, CANONICAL_PINCH, cx, cy),
                GestureAction.Magnify(IoPhase.ENDED, 0.0, cx, cy),
            ),
        )
        scope.settle(FLUSH_MILLIS)
        verify("canonical pinch")
        val ratio = kotlin.math.exp(zoom.logZoom - before).toFloat()
        check(abs(ratio - (1f + CANONICAL_PINCH.toFloat())) <= FACTOR_TOLERANCE) {
            journal.failure("a canonical pinch after the walk zoomed transformable by $ratio", state())
        }
    }

    private fun verify(where: String) {
        zoom.badChange?.let { fail(where, "transformable received a non-finite or non-positive transform: $it") }
        val events = trace.snapshot()
        val scale = events.filter { it.isScale }

        scale.firstOrNull { it.changes.size != 1 || it.changes[0].type != PointerType.Mouse }?.let {
            fail(where, "a Scale event must carry exactly one mouse pointer, got ${it.changes.map { c -> c.type }}")
        }
        val actual = scale.map { it.type to (it.changes.firstOrNull()?.scaleFactor ?: 1f) }
        val expected = oracle.scale
        val firstDiff =
            (0 until maxOf(actual.size, expected.size)).firstOrNull { i ->
                val a = actual.getOrNull(i)
                val e = expected.getOrNull(i)
                a == null ||
                    e == null ||
                    a.first != e.first ||
                    (a.first == PointerEventType.ScaleChange && abs(a.second - e.second) > FACTOR_TOLERANCE)
            }
        if (firstDiff != null) {
            fail(
                where,
                "Scale stream diverges from the model at #$firstDiff: " +
                    "got ${actual.window(firstDiff)} expected ${expected.window(firstDiff)} " +
                    "(${actual.size} vs ${expected.size} events)",
            )
        }

        // No Scale event while a contact is pressed; never more than two contacts.
        val pressed = mutableSetOf<Long>()
        var downs = 0
        for ((index, event) in events.withIndex()) {
            // Every event lists the active pointers: one without the pressed
            // contacts is their (synthetic) release — a touch tap.
            if (pressed.isNotEmpty() && event.changes.none { it.type == PointerType.Touch }) {
                fail(where, "$index: ${event.type} without the pressed contacts $pressed: ${events.around(index)}")
            }
            for (change in event.changes) {
                if (change.type != PointerType.Touch) continue
                if (change.down) downs++
                if (change.pressed) pressed += change.id else pressed -= change.id
            }
            if (pressed.size > MAX_CONTACTS) fail(where, "${pressed.size} contacts pressed at #$index")
        }
        if (downs != oracle.downs) {
            val firstDown = events.indexOfFirst { e -> e.changes.any { it.down } }
            fail(
                where,
                "$downs touch downs, the model expects ${oracle.downs} (a re-press is a tap); " +
                    "trace around the first: ${events.around(firstDown)}",
            )
        }
    }

    private fun pressedTouches(events: List<TracedEvent>): Set<Long> {
        val pressed = mutableSetOf<Long>()
        for (event in events) {
            for (change in event.changes) {
                if (change.type != PointerType.Touch) continue
                if (change.pressed) pressed += change.id else pressed -= change.id
            }
        }
        return pressed
    }

    private fun fail(
        where: String,
        reason: String,
    ): Nothing = throw IllegalStateException(journal.failure("$where: $reason", state()))

    private fun state(): String =
        "scaleOpen=${oracle.scaleOpen} rotateActive=${oracle.rotateActive} expectedDowns=${oracle.downs} " +
            "expectedScale=${oracle.scale.size} logZoom=${zoom.logZoom} scale=${scope.window.scaleFactor}"

    private fun <T> List<T>.window(at: Int): List<T> = subList(maxOf(0, at - 3), minOf(size, at + 4))

    private fun List<TracedEvent>.around(at: Int): String =
        if (at < 0) "[]" else subList(maxOf(0, at - 6), minOf(size, at + 6)).joinToString(prefix = "[", postfix = "]")

    private companion object {
        val GESTURES = listOf("pinch", "rotate", "pinch+rotate", "rotate+pinch", "smart")
        val GESTURES_WITH_SCROLL = GESTURES + listOf("swipe", "pinch+swipe", "rotate+swipe", "swipe+rotate")

        val CHAOS_PHASES =
            intArrayOf(IoPhase.NONE, IoPhase.BEGAN, IoPhase.CHANGED, IoPhase.CHANGED, IoPhase.ENDED, IoPhase.CANCELLED)

        const val CHAOS_KINDS = 8
        const val CHAOS_MAX_DEGREES = 720.0
        const val CHAOS_SCROLL_PT = 40
        const val IMMEDIATE_ONE_IN = 3

        const val DYADIC = 256
        const val PINCH_STEP = 16 // ±1/16 per step
        const val ROTATE_STEP = 8 // ±8° per step
        const val SWIPE_PT = 12
        const val MAX_GESTURE_STEPS = 10
        const val LATE_START_MAX = 4
        const val CANCEL_ONE_IN = 8
        const val JITTER_DP = 3

        const val WINDOW_W_DP = 800f
        const val WINDOW_H_DP = 600f
        const val MARGIN_DP = 140f

        const val MAX_CONTACTS = 2
        const val TITLE_BAR_ONE_IN = 4

        /** The pan router's 150 ms momentum grace plus delivery. */
        const val PAN_GRACE_MILLIS = 300L
        const val CHECKPOINT_EVERY = 10
        const val STEP_MILLIS = 4L
        const val FLUSH_MILLIS = 60L

        /** Past the pan router's 150 ms grace and its 1 s stall watchdog. */
        const val QUIESCE_MILLIS = 1_600L

        const val CANONICAL_PINCH = 0.125
        const val FACTOR_TOLERANCE = 1e-4f
    }
}

private const val MONKEY_CASE_TIMEOUT_MILLIS = 600_000L

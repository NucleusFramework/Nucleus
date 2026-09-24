package dev.nucleusframework.window.tao.event

import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #706: the DirectManipulation viewport stream → pan / pinch phases. Scripted
 * sequences pin the recognition rules; the seeded walks at the end throw
 * thousands of random — valid and invalid — streams at it and check what must
 * hold for any of them.
 */
class TaoDirectManipulationGestureTest {
    @Test
    fun pinchOpensOnTheFirstScaleAndClosesOnReady() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(scale = 1.1f)
        dm.content(scale = 1.21f)
        dm.status(READY)
        assertEquals(
            listOf("pinch BEGAN 1.1000", "pinch CHANGED 1.1000", "pinch ENDED 1.0000"),
            out.map { it.label },
        )
    }

    @Test
    fun pinchFactorsMultiplyToTheViewportsScale() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        var scale = 1f
        repeat(40) {
            scale *= 0.97f
            dm.content(scale = scale)
        }
        dm.status(READY)
        val product = out.filter { it.pinch }.fold(1.0) { acc, e -> acc * e.factor }
        assertEquals(scale.toDouble(), product, 1e-4)
    }

    @Test
    fun pinchAtItsFocalPoint() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture, focalX = 120f, focalY = 80f)
        dm.status(RUNNING)
        dm.content(scale = 1.2f)
        dm.status(READY)
        assertTrue(out.all { it.x == 120f && it.y == 80f }, "every step at the focal point: $out")
    }

    @Test
    fun panCarriesContentMotionAndEndsOnReadyWithoutInertia() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(y = -10f)
        dm.content(y = -25f)
        dm.status(READY)
        assertEquals(
            listOf("pan BEGAN 0.0,-10.0", "pan CHANGED 0.0,-15.0", "pan ENDED 0.0,0.0"),
            out.map { it.label },
        )
    }

    @Test
    fun inertiaContinuesThePanAsItsMomentumTail() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(x = 8f)
        dm.status(INERTIA)
        dm.content(x = 12f)
        dm.status(READY)
        assertEquals(
            listOf(
                "pan BEGAN 8.0,0.0",
                "pan ENDED 0.0,0.0",
                "pan MOMENTUM_BEGAN 0.0,0.0",
                "pan MOMENTUM_CHANGED 4.0,0.0",
                "pan MOMENTUM_ENDED 0.0,0.0",
            ),
            out.map { it.label },
        )
    }

    @Test
    fun aFlickTooQuickForAnUpdateIsStillAPanWithItsTail() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.status(INERTIA)
        dm.content(y = 30f)
        dm.status(READY)
        assertEquals(
            listOf("BEGAN", "ENDED", "MOMENTUM_BEGAN", "MOMENTUM_CHANGED", "MOMENTUM_ENDED"),
            out.map { it.phase },
        )
    }

    @Test
    fun aPanThatStartsScalingBecomesAPinchForGood() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(x = 5f)
        dm.content(x = 6f, scale = 1.1f)
        dm.content(x = 30f, scale = 1.1f)
        dm.content(x = 31f, scale = 1.2f)
        dm.status(READY)
        assertEquals(
            listOf(
                "pan BEGAN 5.0,0.0",
                "pan CANCELLED 0.0,0.0",
                "pinch BEGAN 1.1000",
                // Translation inside a pinch is noise and is dropped.
                "pinch CHANGED 1.0909",
                "pinch ENDED 1.0000",
            ),
            out.map { it.label },
        )
    }

    @Test
    fun scaleJitterUnderTheEpsilonKeepsAPanAPan() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(y = 4f, scale = 1.0004f)
        dm.content(y = 8f, scale = 0.9995f)
        dm.status(READY)
        assertTrue(out.none { it.pinch }, "sub-epsilon scale noise must not turn a pan into a pinch: $out")
    }

    @Test
    fun aPinchLiftingIntoInertiaEndsAndTheTailIsIgnored() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(scale = 1.3f)
        dm.status(INERTIA)
        dm.content(scale = 1.3f, x = 40f)
        dm.status(READY)
        assertEquals(listOf("pinch BEGAN 1.3000", "pinch ENDED 1.0000"), out.map { it.label })
    }

    @Test
    fun fingersBackOnThePadStopTheGlide() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(y = 10f)
        dm.status(INERTIA)
        dm.content(y = 14f)
        dm.status(RUNNING)
        dm.content(y = 20f)
        dm.status(READY)
        assertEquals(
            listOf("BEGAN", "ENDED", "MOMENTUM_BEGAN", "MOMENTUM_CHANGED", "MOMENTUM_ENDED", "BEGAN", "ENDED"),
            out.map { it.phase },
        )
    }

    @Test
    fun aSuspendedManipulationCancelsItsGesture() {
        for (status in listOf(SUSPENDED, DISABLED, ENABLED, BUILDING)) {
            val (gesture, out) = recorder()
            val dm = Stream(gesture)
            dm.status(RUNNING)
            dm.content(scale = 1.2f)
            dm.status(status)
            assertEquals("pinch CANCELLED 1.0000", out.last().label, "status $status")
            dm.content(scale = 1.4f)
            assertEquals(2, out.size, "nothing after the cancel until the next RUNNING (status $status): $out")
        }
    }

    @Test
    fun anInertiaNobodyStartedIsNoGesture() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        // A programmatic ZoomToRect / a wheel handed to the viewport: no RUNNING.
        dm.status(INERTIA)
        dm.content(y = -120f)
        dm.status(READY)
        assertTrue(out.isEmpty(), "got $out")
    }

    @Test
    fun everySequenceIsRebasedOnTheTransformItStartsFrom() {
        val (gesture, out) = recorder()
        // A reset that never happened: the next gesture starts at scale 2, x 300.
        val dm = Stream(gesture, scale = 2f, x = 300f)
        dm.status(RUNNING)
        dm.content(scale = 2f, x = 310f)
        dm.status(READY)
        assertEquals(listOf("pan BEGAN 10.0,0.0", "pan ENDED 0.0,0.0"), out.map { it.label })
    }

    @Test
    fun aContentUpdateWhoseRunningWasMissedAdoptsTheSequence() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.content(scale = 1.1f, status = RUNNING)
        dm.content(scale = 1.21f, status = RUNNING)
        dm.status(READY)
        assertEquals(listOf("pinch BEGAN 1.1000", "pinch ENDED 1.0000"), out.map { it.label })
    }

    @Test
    fun aTransformThatJumpsIsRebasedNotZoomed() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(scale = 1.1f)
        dm.content(scale = 1_000f)
        dm.content(scale = 1_100f)
        dm.status(READY)
        val product = out.filter { it.pinch }.fold(1.0) { acc, e -> acc * e.factor }
        assertEquals(1.21, product, 1e-3, "the jump must be dropped, the moves around it kept: $out")
    }

    @Test
    fun cancelClosesWhateverIsOpen() {
        val (gesture, out) = recorder()
        val dm = Stream(gesture)
        dm.status(RUNNING)
        dm.content(y = 5f)
        dm.status(INERTIA)
        gesture.cancel()
        assertEquals("pan CANCELLED 0.0,0.0", out.last().label)
        dm.content(y = 9f)
        assertEquals("pan CANCELLED 0.0,0.0", out.last().label, "nothing after the cancel")
    }

    // ── Seeded walks ─────────────────────────────────────────────────────────

    /**
     * Random streams — statuses in any order, scales and offsets including
     * NaN, zero, negative and infinite — must still produce well-formed
     * phases with finite values, and leave nothing open after a READY.
     */
    @Test
    fun randomStreamsStayWellFormed() {
        repeat(SEEDS) { seed ->
            val random = Random(BASE_SEED + seed)
            val checker = Checker(seed)
            val gesture = TaoDirectManipulationGesture(checker)
            repeat(EVENTS_PER_SEED) {
                val kind = if (random.nextInt(10) == 0) random.nextInt(-1, 4) else random.nextInt(2)
                gesture.onEvent(
                    TaoDirectManipulationEvent(
                        kind = kind,
                        status = random.nextInt(-1, 8),
                        previousStatus = random.nextInt(-1, 8),
                        scale =
                            SCALES[random.nextInt(SCALES.size)] *
                                (if (random.nextBoolean()) 1f else random.nextFloat() * 2f),
                        offsetX = OFFSETS[random.nextInt(OFFSETS.size)] + random.nextInt(-50, 51),
                        offsetY = OFFSETS[random.nextInt(OFFSETS.size)] + random.nextInt(-50, 51),
                        focalX = random.nextFloat() * 800f,
                        focalY = if (random.nextInt(20) == 0) Float.NaN else random.nextFloat() * 600f,
                    ),
                )
                checker.checkpoint(gesture)
            }
            gesture.onEvent(event(TaoDirectManipulationEvent.STATUS, READY, RUNNING))
            checker.assertClosed("after READY")
        }
    }

    /**
     * Well-formed random gestures, checked against the model: a pinch's
     * factors multiply to the scale the viewport reached, a pan's deltas add
     * up to the distance it travelled, fingers and inertia included.
     */
    @Test
    fun randomWellFormedGesturesMatchTheModel() {
        repeat(SEEDS) { seed ->
            val random = Random(BASE_SEED * 7 + seed)
            val checker = Checker(seed)
            val gesture = TaoDirectManipulationGesture(checker)
            // Where the content is: the native reset may or may not have run.
            var scale = if (random.nextBoolean()) 1f else random.nextFloat() * 3f + 0.2f
            var x = if (random.nextBoolean()) 0f else random.nextInt(-500, 500).toFloat()
            var y = 0f
            repeat(GESTURES_PER_SEED) {
                checker.reset()
                gesture.onEvent(event(TaoDirectManipulationEvent.STATUS, RUNNING, READY, scale, x, y))
                val pinch = random.nextBoolean()
                var expectedScale = 1.0
                var expectedX = 0f
                var expectedY = 0f
                repeat(1 + random.nextInt(30)) {
                    if (pinch) {
                        val step = random.nextDouble(0.85, 1.18).toFloat()
                        scale *= step
                        expectedScale *= step
                    } else {
                        val dx = random.nextInt(-20, 21).toFloat()
                        val dy = random.nextInt(-20, 21).toFloat()
                        x += dx
                        y += dy
                        expectedX += dx
                        expectedY += dy
                    }
                    gesture.onEvent(event(TaoDirectManipulationEvent.CONTENT, RUNNING, RUNNING, scale, x, y))
                }
                if (!pinch && random.nextBoolean()) {
                    gesture.onEvent(event(TaoDirectManipulationEvent.STATUS, INERTIA, RUNNING, scale, x, y))
                    repeat(random.nextInt(20)) {
                        val dy = random.nextInt(-5, 6).toFloat()
                        y += dy
                        expectedY += dy
                        gesture.onEvent(event(TaoDirectManipulationEvent.CONTENT, INERTIA, INERTIA, scale, x, y))
                    }
                    gesture.onEvent(event(TaoDirectManipulationEvent.STATUS, READY, INERTIA, scale, x, y))
                } else {
                    gesture.onEvent(event(TaoDirectManipulationEvent.STATUS, READY, RUNNING, scale, x, y))
                }
                checker.assertClosed("gesture end")
                if (pinch) {
                    assertEquals(expectedScale, checker.pinchProduct, 1e-3 * expectedScale, "seed $seed pinch")
                } else {
                    assertEquals(expectedX, checker.panX, 1e-2f, "seed $seed pan x")
                    assertEquals(expectedY, checker.panY, 1e-2f, "seed $seed pan y")
                }
                // The native reset, most of the time.
                if (random.nextInt(4) != 0) {
                    scale = 1f
                    x = 0f
                    y = 0f
                }
            }
        }
    }

    // ── Support ─────────────────────────────────────────────────────────────

    private class Out(
        val pinch: Boolean,
        val phase: String,
        val factor: Float,
        val dx: Float,
        val dy: Float,
        val x: Float,
        val y: Float,
    ) {
        val label: String
            get() = if (pinch) "pinch $phase ${"%.4f".format(java.util.Locale.ROOT, factor)}" else "pan $phase $dx,$dy"

        override fun toString(): String = label
    }

    private fun recorder(): Pair<TaoDirectManipulationGesture, MutableList<Out>> {
        val out = mutableListOf<Out>()
        val gesture =
            TaoDirectManipulationGesture(
                object : TaoDirectManipulationGesture.Sink {
                    override fun pan(
                        phase: TaoScrollGesturePhase,
                        dxPx: Float,
                        dyPx: Float,
                        focalX: Float,
                        focalY: Float,
                    ) {
                        out += Out(false, phase.name, 1f, dxPx, dyPx, focalX, focalY)
                    }

                    override fun pinch(
                        phase: Int,
                        scaleFactor: Float,
                        focalX: Float,
                        focalY: Float,
                    ) {
                        out += Out(true, trackpadPhase(phase), scaleFactor, 0f, 0f, focalX, focalY)
                    }
                },
            )
        return gesture to out
    }

    /** A viewport's transform, reported step by step. */
    private class Stream(
        private val gesture: TaoDirectManipulationGesture,
        private var scale: Float = 1f,
        private var x: Float = 0f,
        private var y: Float = 0f,
        private val focalX: Float = 10f,
        private val focalY: Float = 20f,
    ) {
        private var status = READY

        fun status(next: Int) {
            gesture.onEvent(
                TaoDirectManipulationEvent(
                    TaoDirectManipulationEvent.STATUS,
                    next,
                    status,
                    scale,
                    x,
                    y,
                    focalX,
                    focalY,
                ),
            )
            status = next
        }

        fun content(
            scale: Float = this.scale,
            x: Float = this.x,
            y: Float = this.y,
            status: Int = this.status,
        ) {
            this.scale = scale
            this.x = x
            this.y = y
            this.status = status
            gesture.onEvent(
                TaoDirectManipulationEvent(
                    TaoDirectManipulationEvent.CONTENT,
                    status,
                    status,
                    scale,
                    x,
                    y,
                    focalX,
                    focalY,
                ),
            )
        }
    }

    /** Checks phase well-formedness as the stream goes, and sums what it saw. */
    private class Checker(
        private val seed: Int,
    ) : TaoDirectManipulationGesture.Sink {
        private var panOpen = false
        private var momentum = false
        private var tailAllowed = false
        private var pinchOpen = false
        var pinchProduct = 1.0
            private set
        var panX = 0f
            private set
        var panY = 0f
            private set

        fun reset() {
            pinchProduct = 1.0
            panX = 0f
            panY = 0f
        }

        @Suppress("CyclomaticComplexMethod") // one branch per phase rule, read top to bottom
        override fun pan(
            phase: TaoScrollGesturePhase,
            dxPx: Float,
            dyPx: Float,
            focalX: Float,
            focalY: Float,
        ) {
            if (!dxPx.isFinite() || !dyPx.isFinite() || !focalX.isFinite() || !focalY.isFinite()) {
                fail("seed $seed: pan $phase with $dxPx,$dyPx at $focalX,$focalY")
            }
            if (pinchOpen) fail("seed $seed: pan $phase while a pinch is open")
            when (phase) {
                TaoScrollGesturePhase.BEGAN -> {
                    if (panOpen) fail("seed $seed: pan BEGAN inside an open pan")
                    panOpen = true
                    momentum = false
                    tailAllowed = false
                }
                TaoScrollGesturePhase.CHANGED ->
                    if (!panOpen ||
                        momentum
                    ) {
                        fail("seed $seed: pan CHANGED outside the fingers' part")
                    }
                TaoScrollGesturePhase.ENDED -> {
                    if (!panOpen || momentum) fail("seed $seed: pan ENDED outside the fingers' part")
                    panOpen = false
                    tailAllowed = true
                }
                TaoScrollGesturePhase.MOMENTUM_BEGAN -> {
                    if (!tailAllowed) fail("seed $seed: momentum without the pan it continues")
                    panOpen = true
                    momentum = true
                    tailAllowed = false
                }
                TaoScrollGesturePhase.MOMENTUM_CHANGED ->
                    if (!panOpen ||
                        !momentum
                    ) {
                        fail("seed $seed: pan $phase without its pan")
                    }
                TaoScrollGesturePhase.MOMENTUM_ENDED -> {
                    if (!panOpen || !momentum) fail("seed $seed: pan $phase without its tail")
                    panOpen = false
                    momentum = false
                }
                TaoScrollGesturePhase.CANCELLED -> {
                    if (!panOpen) fail("seed $seed: pan CANCELLED without a pan")
                    panOpen = false
                    momentum = false
                }
                TaoScrollGesturePhase.MAY_BEGIN -> fail("seed $seed: MAY_BEGIN is macOS-only")
            }
            panX += dxPx
            panY += dyPx
        }

        override fun pinch(
            phase: Int,
            scaleFactor: Float,
            focalX: Float,
            focalY: Float,
        ) {
            if (!scaleFactor.isFinite() || scaleFactor <= 0f || !focalX.isFinite() || !focalY.isFinite()) {
                fail("seed $seed: pinch ${trackpadPhase(phase)} with $scaleFactor at $focalX,$focalY")
            }
            if (panOpen) fail("seed $seed: pinch while a pan is open")
            tailAllowed = false
            when (phase) {
                TaoTrackpadPhase.BEGAN -> {
                    if (pinchOpen) fail("seed $seed: pinch BEGAN inside an open pinch")
                    pinchOpen = true
                }
                TaoTrackpadPhase.CHANGED -> if (!pinchOpen) fail("seed $seed: pinch CHANGED outside a pinch")
                TaoTrackpadPhase.ENDED, TaoTrackpadPhase.CANCELLED -> {
                    if (!pinchOpen) fail("seed $seed: pinch end without a pinch")
                    pinchOpen = false
                    if (scaleFactor != 1f) fail("seed $seed: a pinch end carries no step")
                }
                else -> fail("seed $seed: unknown pinch phase $phase")
            }
            pinchProduct *= scaleFactor
        }

        fun checkpoint(gesture: TaoDirectManipulationGesture) {
            if (gesture.gestureOpen != (panOpen || pinchOpen)) {
                fail("seed $seed: gestureOpen=${gesture.gestureOpen} but pan=$panOpen pinch=$pinchOpen")
            }
        }

        fun assertClosed(where: String) {
            if (panOpen || pinchOpen) fail("seed $seed: $where left pan=$panOpen pinch=$pinchOpen open")
        }
    }

    private companion object {
        const val BUILDING = TaoDirectManipulationStatus.BUILDING
        const val ENABLED = TaoDirectManipulationStatus.ENABLED
        const val DISABLED = TaoDirectManipulationStatus.DISABLED
        const val RUNNING = TaoDirectManipulationStatus.RUNNING
        const val INERTIA = TaoDirectManipulationStatus.INERTIA
        const val READY = TaoDirectManipulationStatus.READY
        const val SUSPENDED = TaoDirectManipulationStatus.SUSPENDED

        const val BASE_SEED = 20_260_924
        const val SEEDS = 400
        const val EVENTS_PER_SEED = 200
        const val GESTURES_PER_SEED = 20

        val SCALES = floatArrayOf(1f, 1f, 1f, 1.05f, 0.95f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 1e-30f, 1e30f)
        val OFFSETS = floatArrayOf(0f, 0f, 0f, 10f, -10f, Float.NaN, Float.NEGATIVE_INFINITY, 1e9f)

        fun event(
            kind: Int,
            status: Int,
            previous: Int,
            scale: Float = 1f,
            x: Float = 0f,
            y: Float = 0f,
        ) = TaoDirectManipulationEvent(kind, status, previous, scale, x, y, 0f, 0f)

        fun trackpadPhase(phase: Int): String =
            when (phase) {
                TaoTrackpadPhase.BEGAN -> "BEGAN"
                TaoTrackpadPhase.CHANGED -> "CHANGED"
                TaoTrackpadPhase.ENDED -> "ENDED"
                TaoTrackpadPhase.CANCELLED -> "CANCELLED"
                else -> "?$phase"
            }
    }
}

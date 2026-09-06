@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Compose and an embedded native widget under one pointer, hit faster than a
 * human can and in every order a random walk finds.
 *
 * The failures this is after are the ones a user reports as "it went dead":
 * after a few quick clicks between a Compose control and a native view, the
 * Compose side stops taking clicks, the I-beam never comes back over the text
 * field, or keystrokes go to whichever side had focus last but the caret
 * shows on the other. None of those is a crash; each is a state two input
 * routers — Compose's hit-testing and the platform's own (GtkEventBox capture,
 * AppKit's responder chain, Win32 focus) — disagree about, reached through an
 * interleaving nobody wrote a case for.
 *
 * The fixture is the smallest desktop that has both routers: a `BasicTextField`
 * (I-beam, Compose focus), a Compose button, a [NativeView] embedding a real
 * text widget ([NativeProbe]: it *takes* native focus and *shows* an I-beam of
 * its own), and a Compose button drawn *over* the native view through the
 * `content` slot — the blending path.
 *
 * What is asserted is not "the right thing happened" but that both routers
 * still agree and still answer, re-checked after every burst:
 *
 *  - **responsiveness** — a click on either Compose button is counted, a
 *    click on the field focuses it;
 *  - **one keyboard owner** — Compose focus and native focus are never both
 *    held, and a typed letter lands on exactly the side that holds it;
 *  - **the cursor** — a still pointer over the field leaves `TEXT` as the
 *    last requested cursor and keeps it (no flicker from a stray move);
 *  - **no leak, no wedge** — every probe an unmount disposed is disposed,
 *    and the main dispatcher keeps answering ([MainLoopWatchdog]).
 *
 * Every case runs twice: with the [SyntheticPointerDriver] (everywhere, native
 * Wayland included) and with the [RobotPointerDriver] (a real X server or a
 * real desktop), which is the only one that reaches the platform half.
 */
internal object NativeViewMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            alternatingClicksKeepComposeResponsive(synthetic = true),
            alternatingClicksKeepComposeResponsive(synthetic = false),
            aRightClickOnTheEmbedDoesNotSwallowLaterClicks(synthetic = true),
            aRightClickOnTheEmbedDoesNotSwallowLaterClicks(synthetic = false),
            resizeStormKeepsTheEmbedOnItsSlot(),
            randomActionsLeaveBothRoutersAgreeing(synthetic = true),
            randomActionsLeaveBothRoutersAgreeing(synthetic = false),
        )

    /**
     * Pinned from the robot monkey's journal: a right click on the embed is
     * forwarded to the widget, whose own context menu takes a grab and eats
     * the button *release*. Compose then holds a button that was never let go
     * of, and every later click on Compose is dead — no down transition. The
     * plain left click that follows has to be counted.
     */
    private fun aRightClickOnTheEmbedDoesNotSwallowLaterClicks(synthetic: Boolean): TaoWindowTestCase {
        val fixture = NativeViewFixture()
        return TaoWindowTestCase(
            name = "native view ${driverName(synthetic)} a right click on the embed does not swallow later clicks",
            skip = { skipReason(synthetic) },
            windowState = caseWindowState(),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
            driver = {
                fixture.awaitReady(this)
                val driver = newDriver(synthetic, window, fixture)
                val probe = ResponsivenessProbe(this, fixture, driver)
                probe.expectResponsive("before the right click")
                driver.click(fixture.center(Region.Native), TaoMouseButton.RIGHT)
                settle()
                // Whatever menu the embed opened, a click on plain ground
                // dismisses it — GTK gives that click to the menu, which is the
                // platform's contract, not the bug. The bug is everything after.
                driver.click(fixture.backdropPoint())
                settle()
                probe.expectResponsive("after a right click on the embed")
                driver.exit()
            },
        )
    }

    /**
     * The embed has to *follow* its slot through a resize: sizes asked for one
     * after another with no pause, then a smooth animated resize. After each
     * step the platform widget's own frame is compared with the Compose rect
     * of the slot, the lag between the two is measured, and at the end they
     * have to agree. Purely programmatic — no pointer, so it runs everywhere.
     */
    private fun resizeStormKeepsTheEmbedOnItsSlot(): TaoWindowTestCase {
        val fixture = NativeViewFixture()
        return TaoWindowTestCase(
            name = "native view resize storm keeps the embed on its slot",
            timeoutMillis = STORM_CASE_TIMEOUT_MILLIS,
            skip = { NativeProbe.skipReason() },
            windowState = caseWindowState(),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
            driver = {
                fixture.awaitReady(this)
                val geometry = EmbedGeometryProbe(this, fixture)
                geometry.expectOnSlot("before the storm")

                // 1. Discrete steps, each awaited: how long does the embed trail the layout?
                var worstLagMillis = 0L
                for (round in 0 until RESIZE_ROUNDS) {
                    // Never the current size: a step that changes nothing has no lag to measure.
                    val w = WINDOW_W_DP - (round % RESIZE_SPAN + 1) * RESIZE_STEP_DP
                    val h = WINDOW_H_DP - (round % RESIZE_SPAN + 1) * RESIZE_STEP_DP
                    worstLagMillis = maxOf(worstLagMillis, geometry.resizeAndMeasureLag(w, h))
                }

                // 2. A burst with no waiting at all, then a smooth animation.
                for (round in 0 until RESIZE_BURST) {
                    window.setInnerSize((WINDOW_W_DP - round * RESIZE_STEP_DP).toDouble(), WINDOW_H_DP.toDouble())
                }
                geometry.expectOnSlot("after a burst of resizes")
                for (step in 0..ANIMATION_STEPS) {
                    val t = step / ANIMATION_STEPS.toFloat()
                    window.setInnerSize(
                        (MIN_INNER_W_DP + (WINDOW_W_DP - MIN_INNER_W_DP) * t),
                        (MIN_INNER_H_DP + (WINDOW_H_DP - MIN_INNER_H_DP) * t),
                    )
                    settle(ANIMATION_FRAME_MILLIS)
                    geometry.sample()
                }
                geometry.expectOnSlot("after an animated resize")
                System.err.println(
                    "[native-view-resize] worst lag ${worstLagMillis}ms over $RESIZE_ROUNDS steps; " +
                        "animated: ${geometry.offSlotSamples} of ${geometry.samples} samples off the slot, " +
                        "worst ${geometry.worstDistancePx}px behind",
                )

                // 3. The user's gesture: a real pointer dragging the corner of
                // the frame, so the sizes flow in from the window manager at
                // its cadence instead of from setInnerSize. Robot hosts only.
                if (robotDriverSkipReason() == null) {
                    val interactive = EmbedGeometryProbe(this, fixture)
                    val sizeBefore = window.outerBoundsPx()?.drop(2)
                    dragBottomRightCorner(interactive)
                    interactive.expectOnSlot("after an interactive edge drag")
                    val sizeAfter = window.outerBoundsPx()?.drop(2)
                    System.err.println(
                        "[native-view-resize] interactive: ${interactive.offSlotSamples} of ${interactive.samples} " +
                            "samples off the slot, worst ${interactive.worstDistancePx}px behind; frame " +
                            if (sizeBefore == sizeAfter) {
                                "$sizeBefore unchanged (the press started no resize on this host)"
                            } else {
                                "$sizeBefore -> $sizeAfter"
                            },
                    )
                    check(interactive.worstDistancePx <= ANIMATION_LAG_FRAMES * EDGE_DRAG_STEP_PX) {
                        "the embed fell ${interactive.worstDistancePx}px behind its slot during an interactive " +
                            "resize " +
                            "(${EDGE_DRAG_STEP_PX}px per step, budget $ANIMATION_LAG_FRAMES steps)"
                    }
                }
                check(worstLagMillis <= EMBED_LAG_BUDGET_MILLIS) {
                    "the embed trailed its slot by ${worstLagMillis}ms after a resize (budget $EMBED_LAG_BUDGET_MILLIS)"
                }
                // One frame behind the layout is the pipeline (Compose places,
                // then the platform allocates); several frames is the embed
                // visibly peeling away from the window edge as it is dragged.
                val perFramePx = ((WINDOW_W_DP - MIN_INNER_W_DP) / ANIMATION_STEPS * window.scaleFactor).roundToInt()
                check(geometry.worstDistancePx <= ANIMATION_LAG_FRAMES * perFramePx) {
                    "the embed fell ${geometry.worstDistancePx}px behind its slot during an animated resize " +
                        "(${perFramePx}px per frame, budget $ANIMATION_LAG_FRAMES frames)"
                }
            },
        )
    }

    /**
     * The bug report, verbatim: click Compose, click native, click Compose
     * over native, click the field, again, as fast as possible. Every click
     * must have been counted at the end, and the desktop must still answer.
     */
    private fun alternatingClicksKeepComposeResponsive(synthetic: Boolean): TaoWindowTestCase {
        val fixture = NativeViewFixture()
        return TaoWindowTestCase(
            name = "native view ${driverName(synthetic)} alternating clicks keep compose responsive",
            timeoutMillis = STORM_CASE_TIMEOUT_MILLIS,
            skip = { skipReason(synthetic) },
            windowState = caseWindowState(),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
            driver = {
                fixture.awaitReady(this)
                val driver = newDriver(synthetic, window, fixture)
                val probe = ResponsivenessProbe(this, fixture, driver)
                probe.expectResponsive("before the storm")

                val headerBefore = fixture.headerClicks
                val overlayBefore = fixture.overlayClicks
                for (round in 0 until STORM_ROUNDS) {
                    driver.click(fixture.center(Region.HeaderButton))
                    driver.click(fixture.center(Region.Native))
                    driver.click(fixture.center(Region.OverlayButton))
                    driver.click(fixture.center(Region.Field))
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // Every click must have landed: a lost one is the report.
                awaitUntil(
                    "every header click of the storm was counted",
                    detail = { "counted ${fixture.headerClicks - headerBefore} of $STORM_ROUNDS; ${robotAim()}" },
                ) { fixture.headerClicks - headerBefore == STORM_ROUNDS }
                awaitUntil(
                    "every overlay click of the storm was counted",
                    detail = { "counted ${fixture.overlayClicks - overlayBefore} of $STORM_ROUNDS; ${robotAim()}" },
                ) { fixture.overlayClicks - overlayBefore == STORM_ROUNDS }
                probe.expectResponsive("after the storm")
                probe.expectKeyboardAgrees("after the storm")
                driver.exit()
            },
        )
    }

    /** A seeded random walk over every gesture the fixture knows, checked every few steps. */
    private fun randomActionsLeaveBothRoutersAgreeing(synthetic: Boolean): TaoWindowTestCase {
        val fixture = NativeViewFixture()
        return TaoWindowTestCase(
            name = "native view ${driverName(
                synthetic,
            )} monkey $MONKEY_ACTIONS random actions leave both routers agreeing",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = { skipReason(synthetic) },
            windowState = caseWindowState(),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
            driver = {
                fixture.awaitReady(this)
                val driver = newDriver(synthetic, window, fixture)
                val monkey = NativeViewMonkey(this, fixture, driver, monkeySeed())
                monkey.run()
                monkey.quiesceAndAssert()
            },
        )
    }

    private fun skipReason(synthetic: Boolean): String? =
        NativeProbe.skipReason() ?: if (synthetic) null else robotDriverSkipReason()

    /**
     * Presses the resize band at the bottom-right corner of the frame with the
     * real pointer and drags it inwards, sampling the embed against its slot
     * after every step. Whether the platform turns the press into a resize is
     * its business (Tao's own band on X11 and Win32, AppKit's edges on macOS);
     * a press that resizes nothing simply leaves nothing to trail.
     */
    private suspend fun TaoWindowTestScope.dragBottomRightCorner(geometry: EmbedGeometryProbe) {
        val outer = requireNotNull(window.outerBoundsPx()) { "the case window is not mapped" }
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        val startX = outer[0] + outer[OUTER_W] - EDGE_PRESS_INSET_PX
        val startY = outer[1] + outer[OUTER_H] - EDGE_PRESS_INSET_PX
        val moved =
            HeadfulRobot.inject { robot ->
                robot.mouseMove((startX / scale).roundToInt(), (startY / scale).roundToInt())
                HeadfulRobot.noteAim((startX / scale).roundToInt(), (startY / scale).roundToInt())
                Thread.sleep(ROBOT_PRESS_SETTLE_MILLIS)
                HeadfulRobot.notePress()
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                true
            }
        checkNotNull(moved) { "the AWT Robot became unavailable: ${HeadfulRobot.unavailableReason}" }
        for (step in 1..EDGE_DRAG_STEPS) {
            val x = startX - step * EDGE_DRAG_STEP_PX
            val y = startY - step * EDGE_DRAG_STEP_PX
            HeadfulRobot.inject { robot ->
                robot.mouseMove((x / scale).roundToInt(), (y / scale).roundToInt())
                true
            }
            settle(EDGE_DRAG_STEP_MILLIS)
            geometry.sample()
        }
        HeadfulRobot.inject { robot ->
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            true
        }
    }

    private fun driverName(synthetic: Boolean) = if (synthetic) "synthetic" else "robot"

    private fun newDriver(
        synthetic: Boolean,
        window: TaoWindow,
        fixture: NativeViewFixture,
    ): PointerDriver =
        if (synthetic) SyntheticPointerDriver(window) else RobotPointerDriver(window) { fixture.sceneSize }

    private fun caseWindowState() =
        WindowState(
            position = WindowPosition.Absolute(WINDOW_X_DP.dp, WINDOW_Y_DP.dp),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
        )
}

/** The hit targets the fixture lays out, each with a rect in content px. */
private enum class Region {
    /** The `BasicTextField` in the header row. */
    Field,

    /** The Compose button beside it — plain Compose ground, no embed underneath. */
    HeaderButton,

    /** The embedded native widget's slot (its centre is clear of the overlay button). */
    Native,

    /** The Compose button drawn over the native view through `NativeView`'s content slot. */
    OverlayButton,
}

/**
 * The desktop described in [NativeViewMonkeyHeadfulCases], publishing its
 * rects, its counters and its focus state for the driver to read.
 */
private class NativeViewFixture {
    var fieldText by mutableStateOf("")
    var fieldFocused by mutableStateOf(false)
    var headerClicks by mutableIntStateOf(0)
    var overlayClicks by mutableIntStateOf(0)

    /** Whether the native view is in composition; flipped by the monkey. */
    var nativeMounted by mutableStateOf(true)

    /** The probe currently embedded, or the last one when unmounted. */
    var probe: NativeProbe? = null
        private set

    var sceneSize: IntSize = IntSize.Zero
        private set

    /** The case window, once composed. */
    var window: TaoWindow? = null
        private set

    private val rects = java.util.concurrent.ConcurrentHashMap<Region, Rect>()

    /**
     * The last presses and releases the Compose scene received, as seen from
     * the root in the initial pass — so a lost click can be told apart from a
     * click that arrived at the wrong place, or never arrived at all.
     */
    private val recentPointerEvents = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun recentPointerEvents(): List<String> = recentPointerEvents.toList()

    /** Interleaves a driver-side marker with the scene's events, so intent and reception read together. */
    fun note(marker: String) {
        if (recentPointerEvents.size >= POINTER_LOG_DEPTH) recentPointerEvents.pollFirst()
        recentPointerEvents.addLast(marker)
    }

    fun rect(region: Region): Rect? = rects[region]

    fun center(region: Region): Offset = requireNotNull(rect(region)) { "$region has no rect yet" }.center

    /**
     * A point on plain Compose ground: the gap between the header row and the
     * native slot, well clear of the resize band. Where a context menu the
     * embed opened gets dismissed — that click is the menu's, not Compose's.
     */
    fun backdropPoint(): Offset {
        val native = requireNotNull(rect(Region.Native)) { "the native slot has no rect yet" }
        return Offset(native.center.x, native.top - (native.top - requireNotNull(rect(Region.Field)).bottom) / 2f)
    }

    @Composable
    fun Content() {
        val window = LocalTaoWindow.current
        this.window = window
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(BACKDROP_ARGB))
                .onGloballyPositioned { sceneSize = it.size }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                                val position = event.changes.firstOrNull()?.position
                                if (recentPointerEvents.size >= POINTER_LOG_DEPTH) recentPointerEvents.pollFirst()
                                recentPointerEvents.addLast(
                                    "${event.type}(${event.button})@${position?.x?.toInt()},${position?.y?.toInt()}",
                                )
                            }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(HEADER_H_DP.dp).padding(PAD_DP.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.White)
                            .recordRect(Region.Field),
                    ) {
                        BasicTextField(
                            value = fieldText,
                            onValueChange = { fieldText = it },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(PAD_DP.dp)
                                    .onFocusChanged { fieldFocused = it.isFocused }
                                    .onPreviewKeyEvent { event ->
                                        // Logged, never consumed: which keys reach the field.
                                        note("key ${event.type} ${event.key}")
                                        false
                                    },
                            textStyle = TextStyle(color = Color.Black, fontSize = FONT_SP.sp),
                        )
                    }
                    Spacer(Modifier.width(PAD_DP.dp))
                    Box(
                        Modifier
                            .width(BUTTON_W_DP.dp)
                            .fillMaxHeight()
                            .background(Color(HEADER_BUTTON_ARGB))
                            .clickable { headerClicks++ }
                            .recordRect(Region.HeaderButton),
                    )
                }
                Box(Modifier.fillMaxSize().padding(PAD_DP.dp)) {
                    if (nativeMounted && window != null) {
                        NativeView(
                            factory = {
                                requireNotNull(NativeProbe.create(window)) { "the platform refused a probe widget" }
                                    .also { probe = it }
                                    .platformView
                            },
                            modifier = Modifier.fillMaxSize().recordRect(Region.Native),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(BUTTON_W_DP.dp, OVERLAY_H_DP.dp)
                                        .background(Color(OVERLAY_BUTTON_ARGB))
                                        .clickable { overlayClicks++ }
                                        .recordRect(Region.OverlayButton),
                                )
                            }
                        }
                    } else {
                        // The slot without its embed: the same rect, plain Compose.
                        Box(Modifier.fillMaxSize().background(Color(EMPTY_SLOT_ARGB)).recordRect(Region.Native))
                    }
                }
            }
        }
    }

    private fun Modifier.recordRect(region: Region): Modifier =
        onGloballyPositioned { coords ->
            val origin = coords.positionInRoot()
            rects[region] =
                Rect(
                    origin,
                    androidx.compose.ui.geometry
                        .Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                )
        }

    suspend fun awaitReady(scope: TaoWindowTestScope) {
        with(scope) {
            awaitUntil("the case window is mapped with a real frame") { window.hasRealFramePx() }
            // On top, please: a window an earlier case leaked may sit where the
            // real pointer is about to click, and the WM places this one
            // wherever it finds room. `focus()` is an activation request the WM
            // may refuse; always-on-top is a stacking order it honours.
            window.setAlwaysOnTop(true)
            window.focus()
            awaitUntil("every region has a rect") { Region.entries.all { rect(it) != null } }
            awaitUntil("the overlay button sits inside the native slot") {
                val native = rect(Region.Native) ?: return@awaitUntil false
                val overlay = rect(Region.OverlayButton) ?: return@awaitUntil false
                native.contains(overlay.center) && !overlay.contains(native.center)
            }
            awaitUntil("a probe widget was created") { probe != null }
            // The platform must have mapped the widget where Compose put the
            // slot: this is the "the native view never shows up" check, the
            // first setFrame routinely beats the attach effect and is what
            // mounts the widget.
            awaitUntil("the embed is mapped on its slot", detail = { describeGeometry() }) {
                val slot = rect(Region.Native) ?: return@awaitUntil false
                val frame = probe?.framePx() ?: return@awaitUntil false
                kotlin.math.abs(frame[2] - slot.width.roundToInt()) <= GEOMETRY_TOLERANCE_PX &&
                    kotlin.math.abs(frame[3] - slot.height.roundToInt()) <= GEOMETRY_TOLERANCE_PX
            }
            settle(SETTLE_AFTER_MAP_MILLIS)
        }
    }
}

/**
 * The checks every case ends on and the monkey repeats at each checkpoint —
 * each one a gesture followed by a converging assertion, because the point
 * is not the state the desktop is in but whether it still *answers*.
 */
private class ResponsivenessProbe(
    private val scope: TaoWindowTestScope,
    private val fixture: NativeViewFixture,
    private val driver: PointerDriver,
) {
    private var typed = 'a'

    /** Compose still takes clicks and focus, and still asks for the I-beam. */
    suspend fun expectResponsive(moment: String) {
        val header = fixture.headerClicks
        driver.click(fixture.center(Region.HeaderButton))
        converge("$moment: a click on the header button is counted") { fixture.headerClicks == header + 1 }

        if (fixture.nativeMounted) {
            val overlay = fixture.overlayClicks
            driver.click(fixture.center(Region.OverlayButton))
            converge("$moment: a click on the button over the native view is counted") {
                fixture.overlayClicks == overlay + 1
            }
        }

        driver.click(fixture.center(Region.Field))
        converge("$moment: a click on the text field focuses it") { fixture.fieldFocused }

        expectTextCursor(moment)
    }

    /**
     * A still pointer over the field must have left `TEXT` as the last cursor
     * request and must keep it: a change while nothing moves is the flicker
     * a stray, mis-positioned move produces.
     */
    suspend fun expectTextCursor(moment: String) {
        driver.moveTo(fixture.center(Region.Field) + Offset(CURSOR_NUDGE_PX, 0f))
        converge("$moment: the I-beam is requested over the text field") { lastCursor() == TaoCursorIcon.TEXT }
        // Stability is the robot's to check: it owns the real pointer. With
        // the synthetic driver the real pointer is wherever the desktop left
        // it — on a live session, possibly over this very window's edge.
        if (driver !is RobotPointerDriver) return
        repeat(CURSOR_STILL_SAMPLES) {
            scope.settle(CURSOR_STILL_SAMPLE_MILLIS)
            val now = lastCursor()
            check(now == TaoCursorIcon.TEXT) {
                "$moment: the cursor flickered to $now over a text field under a still pointer"
            }
        }
    }

    /**
     * One keyboard owner, and the right one. Clicking the field gives Compose
     * the keys and takes them from the embed; clicking the embed does the
     * reverse; a typed letter lands where the focus says. The embed half only
     * runs when the driver reaches the widget at all.
     */
    suspend fun expectKeyboardAgrees(moment: String) {
        driver.click(fixture.center(Region.Field))
        converge("$moment: the field takes Compose focus") { fixture.fieldFocused }
        converge("$moment: the embed does not hold native focus while the field is focused") {
            fixture.probe?.hasNativeFocus() != true
        }
        val fieldBefore = fixture.fieldText
        val letter = nextLetter()
        driver.type(letter)
        converge("$moment: a letter typed into the focused field arrives there") {
            fixture.fieldText == fieldBefore + letter
        }
        // Caret keys travel as KeyDown, not as typed text — a second path an
        // embed's focus can cut: the left arrow must move the caret back one.
        // A frame on either side of the caret move: the legacy text field
        // lays the new text out and applies the move through recomposition,
        // and a real keyboard never delivers two keys inside one frame.
        scope.settle(KEY_SETTLE_MILLIS)
        driver.arrowLeft()
        scope.settle(KEY_SETTLE_MILLIS)
        val inserted = nextLetter()
        driver.type(inserted)
        converge("$moment: the left arrow moved the caret so the next letter lands before the last") {
            fixture.fieldText == fieldBefore + inserted + letter
        }

        val probe = fixture.probe
        if (!driver.reachesNative || probe == null || !fixture.nativeMounted) return
        driver.click(fixture.center(Region.Native))
        converge("$moment: a click on the embed gives it native focus") { probe.hasNativeFocus() }
        converge("$moment: the field drops Compose focus once the embed has the keyboard") { !fixture.fieldFocused }
        val fieldNow = fixture.fieldText
        val second = nextLetter()
        driver.type(second)
        // "Ends with", not "appended": a GtkEntry selects its whole text when
        // it takes focus (`gtk-entry-select-on-focus`), so the letter may as
        // well have replaced what an earlier keystroke left there.
        converge(
            "$moment: a letter typed into the focused embed arrives there",
        ) { probe.text().endsWith(second) }
        check(fixture.fieldText == fieldNow) {
            "$moment: a letter typed into the embed also reached the Compose field ('${fixture.fieldText}')"
        }
    }

    private fun lastCursor(): Int? = NativeTaoBridge.lastCursorIcon[scope.window.handle]

    private fun nextLetter(): Char {
        val letter = typed
        typed = if (typed == 'z') 'a' else typed + 1
        return letter
    }

    private suspend fun converge(
        description: String,
        predicate: () -> Boolean,
    ) {
        scope.awaitUntil(
            description,
            timeoutMillis = CONVERGE_MILLIS,
            detail = { fixture.describe(driver) },
            predicate = predicate,
        )
    }
}

/**
 * The embed against its slot: the platform's own frame for the widget versus
 * the Compose rect of the `NativeView`, both in content px. Off by more than
 * [tolerancePx] is "not on the slot".
 */
private class EmbedGeometryProbe(
    private val scope: TaoWindowTestScope,
    private val fixture: NativeViewFixture,
) {
    var samples = 0
        private set
    var offSlotSamples = 0
        private set

    /** The farthest the embed was seen from its slot across [sample] calls, in px. */
    var worstDistancePx = 0
        private set

    private val tolerancePx: Int get() = maxOf(GEOMETRY_TOLERANCE_PX, scope.window.scaleFactor.roundToInt())

    /** How far the embed is from its slot right now, in px, or null when either side is unknown. */
    fun distancePx(): Int? {
        val slot = fixture.rect(Region.Native) ?: return null
        val frame = fixture.probe?.framePx() ?: return null
        return maxOf(
            kotlin.math.abs(frame[0] - slot.left.roundToInt()),
            kotlin.math.abs(frame[1] - slot.top.roundToInt()),
            kotlin.math.abs(frame[2] - slot.width.roundToInt()),
            kotlin.math.abs(frame[3] - slot.height.roundToInt()),
        )
    }

    fun isOnSlot(): Boolean = distancePx()?.let { it <= tolerancePx } == true

    fun sample() {
        samples++
        val distance = distancePx() ?: return
        if (distance > tolerancePx) offSlotSamples++
        worstDistancePx = maxOf(worstDistancePx, distance)
    }

    suspend fun expectOnSlot(moment: String) {
        scope.awaitUntil(
            "$moment: the embed sits on its Compose slot",
            timeoutMillis = CONVERGE_MILLIS,
            detail = { "distance=${distancePx()} tolerance=$tolerancePx ${fixture.describeGeometry()}" },
        ) { isOnSlot() }
    }

    /**
     * Asks for [wDp]×[hDp], waits until Compose has laid the slot out at the
     * new size, then measures how long the embed takes to land on it.
     */
    suspend fun resizeAndMeasureLag(
        wDp: Int,
        hDp: Int,
    ): Long {
        val before = fixture.rect(Region.Native)
        scope.window.setInnerSize(wDp.toDouble(), hDp.toDouble())
        scope.awaitUntil("Compose laid the slot out for ${wDp}x$hDp", detail = { fixture.describeGeometry() }) {
            fixture.rect(Region.Native) != before && fixture.sceneSize.width > 0
        }
        val start = System.nanoTime()
        expectOnSlot("after resizing to ${wDp}x$hDp")
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }
}

private fun NativeViewFixture.describeGeometry(): String =
    "slot=${rect(Region.Native)} frame=${probe?.framePx()?.toList()} scene=$sceneSize " +
        "outer=${window?.outerBoundsPx()?.toList()} scale=${window?.scaleFactor}"

private fun NativeViewFixture.describe(driver: PointerDriver): String =
    "driver=${driver.name} windowFocused=${window?.isFocused} fieldFocused=$fieldFocused field='$fieldText' " +
        "header=$headerClicks overlay=$overlayClicks mounted=$nativeMounted " +
        "probe=${probe?.handle?.toString(HEX)}/disposed=${probe?.isDisposed}/nativeFocus=${probe?.hasNativeFocus()}" +
        "/text='${probe?.text()}' cursor=${NativeTaoBridge.lastCursorIcon} " +
        "probes=${NativeProbe.createdCount.get()}/${NativeProbe.disposedCount.get()} ${robotAim()} " +
        "rects=${Region.entries.map {
            "$it=${rect(
                it,
            )}"
        }} scene=$sceneSize outer=${window?.outerBoundsPx()?.toList()} " +
        "sceneEvents=${recentPointerEvents()}"

/** One atomic thing the monkey can do; drawn uniformly. */
private enum class NativeViewAction {
    ClickField,
    ClickHeaderButton,
    ClickOverlayButton,
    ClickNative,
    DoubleClickNative,
    RightClickNative,
    HoverField,
    HoverNative,
    HoverHeaderButton,

    /** A press on one region released on another — the gesture that crosses the boundary. */
    DragAcross,

    /** Six clicks alternating between two random regions with no settle at all. */
    Burst,
    TypeLetter,
    PointerExit,

    /** Drops the native view from composition, or puts it back. */
    ToggleNativeMounted,
    ResizeWindow,

    /** Injects a scale change (synthetic driver only: the robot aims through the real scale). */
    ChangeDpi,

    /** Asks the OS to focus the window again. */
    RefocusWindow,
}

private class NativeViewMonkey(
    private val scope: TaoWindowTestScope,
    private val fixture: NativeViewFixture,
    private val driver: PointerDriver,
    seed: Long,
) {
    private val random = Random(seed)
    private val journal = MonkeyJournal("native-view-monkey", seed)
    private val probe = ResponsivenessProbe(scope, fixture, driver)
    private val geometry = EmbedGeometryProbe(scope, fixture)
    private var worstStallMillis = 0L
    private var letter = 'a'

    /** A journal pasted back through the script property, or null for the random walk. */
    private val script: List<NativeViewAction>? = monkeyScript()?.map { NativeViewAction.valueOf(it) }

    /** Windows alive when the run started: earlier cases may have left some behind, they are not this run's. */
    private val windowsAtStart = TaoApplication.liveWindowCount()

    suspend fun run() {
        System.err.println(
            "[native-view-monkey] seed=${journal.seed} driver=${driver.name} actions=$MONKEY_ACTIONS " +
                "(replay with -D$MONKEY_SEED_PROPERTY=${journal.seed})",
        )
        val watchdog = MainLoopWatchdog("native-view-monkey", journal::report).start()
        try {
            while (journal.step < (script?.size ?: MONKEY_ACTIONS)) {
                val action =
                    script?.get(journal.step) ?: NativeViewAction.entries[random.nextInt(NativeViewAction.entries.size)]
                journal.record(action)
                fixture.note("> ${journal.step} $action")
                monkeyAction({ journal.failure("$action", fixture.describe(driver)) }) { apply(action) }
                if ((journal.step + 1) % CHECKPOINT_EVERY == 0) checkpoint()
                journal.step++
            }
        } finally {
            worstStallMillis = watchdog.stop()
        }
    }

    /** Back to the plain desktop, and every probe of [ResponsivenessProbe] strictly. */
    suspend fun quiesceAndAssert() {
        // No blind release here: every gesture above released what it
        // pressed, and a Robot release of a button that was never pressed
        // segfaults the JVM on macOS.
        restoreScale()
        scope.window.setInnerSize(WINDOW_W_DP.toDouble(), WINDOW_H_DP.toDouble())
        if (!fixture.nativeMounted) {
            fixture.nativeMounted = true
            journal.reach("remountedForQuiesce")
        }
        scope.window.focus()
        scope.settle(SETTLE_AFTER_MAP_MILLIS)
        scope.awaitUntil("the native view is back with a live probe", detail = { fixture.describe(driver) }) {
            fixture.probe?.isDisposed == false
        }

        geometry.expectOnSlot("after the monkey")
        probe.expectResponsive("after the monkey")
        probe.expectKeyboardAgrees("after the monkey")

        // An unmount must dispose the probe it embedded, and a remount must
        // bring a fresh one: created − disposed is the number still mounted.
        fixture.nativeMounted = false
        scope.awaitUntil("unmounting disposes the embedded probe", detail = { fixture.describe(driver) }) {
            fixture.probe?.isDisposed == true
        }
        fixture.nativeMounted = true
        scope.awaitUntil("remounting creates a fresh probe", detail = { fixture.describe(driver) }) {
            fixture.probe?.isDisposed == false
        }
        val live = NativeProbe.createdCount.get() - NativeProbe.disposedCount.get()
        check(live == 1) { "$live probes are alive with one native view mounted — an unmount leaked its widget" }

        check(TaoApplication.liveWindowCount() == windowsAtStart) {
            "${TaoApplication.liveWindowCount()} native windows are alive, $windowsAtStart when the run started"
        }
        // Park the real pointer outside the window: a later case's windows may
        // map under wherever the last gesture left it.
        driver.exit()
        System.err.println(
            "[native-view-monkey] seed=${journal.seed} driver=${driver.name} survived $MONKEY_ACTIONS actions; " +
                "worst main-dispatcher round trip ${worstStallMillis}ms; reached ${journal.reachedSummary()}",
        )
        check(worstStallMillis <= MONKEY_MAX_STALL_MILLIS) {
            "the main dispatcher took ${worstStallMillis}ms to answer a heartbeat — the loop stalled"
        }
        if (script == null) {
            check(journal.reachedCount("clickNative") > 0) { "the run never clicked the native view" }
            check(journal.reachedCount("toggledMount") > 0) { "the run never unmounted the native view" }
        }
    }

    private suspend fun apply(action: NativeViewAction) {
        when (action) {
            NativeViewAction.ClickField -> driver.click(fixture.center(Region.Field))
            NativeViewAction.ClickHeaderButton -> driver.click(fixture.center(Region.HeaderButton))
            NativeViewAction.ClickOverlayButton ->
                if (fixture.nativeMounted) {
                    driver.click(
                        fixture.center(Region.OverlayButton),
                    )
                }
            NativeViewAction.ClickNative -> {
                driver.click(fixture.center(Region.Native))
                journal.reach("clickNative")
            }
            NativeViewAction.DoubleClickNative -> {
                val point = fixture.center(Region.Native)
                driver.click(point)
                driver.click(point)
            }
            NativeViewAction.RightClickNative -> {
                driver.click(fixture.center(Region.Native), TaoMouseButton.RIGHT)
                // Dismiss the embed's menu, if it opened one; see the pinned case.
                scope.settle(STEP_SETTLE_MILLIS)
                driver.click(fixture.backdropPoint())
            }
            NativeViewAction.HoverField -> driver.moveTo(randomPointIn(Region.Field))
            NativeViewAction.HoverNative -> driver.moveTo(randomPointIn(Region.Native))
            NativeViewAction.HoverHeaderButton -> driver.moveTo(randomPointIn(Region.HeaderButton))
            NativeViewAction.DragAcross -> dragAcross()
            NativeViewAction.Burst -> burst()
            NativeViewAction.TypeLetter -> driver.type(nextLetter())
            NativeViewAction.PointerExit -> driver.exit()
            NativeViewAction.ToggleNativeMounted -> {
                fixture.nativeMounted = !fixture.nativeMounted
                journal.reach("toggledMount")
            }
            NativeViewAction.ResizeWindow ->
                scope.window.setInnerSize(
                    MIN_INNER_W_DP + random.nextDouble(INNER_W_SPAN_DP),
                    MIN_INNER_H_DP + random.nextDouble(INNER_H_SPAN_DP),
                )
            NativeViewAction.ChangeDpi ->
                if (driver is SyntheticPointerDriver) {
                    val scale = SCALE_HOPS[random.nextInt(SCALE_HOPS.size)]
                    scope.window.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (scale * SCALE_MILLI).roundToInt(), 0)
                    journal.reach("dpiChanged")
                }
            NativeViewAction.RefocusWindow -> scope.window.focus()
        }
        scope.settle(STEP_SETTLE_MILLIS)
    }

    private suspend fun dragAcross() {
        val from = randomRegion()
        val to = randomRegion()
        fixture.note("> drag $from -> $to")
        driver.moveTo(fixture.center(from))
        driver.press()
        val start = fixture.center(from)
        val end = fixture.center(to)
        for (step in 1..DRAG_STEPS) {
            val t = step / DRAG_STEPS.toFloat()
            driver.moveTo(start + (end - start) * t)
        }
        driver.release()
        journal.reach("dragged")
    }

    private suspend fun burst() {
        val a = randomRegion()
        val b = randomRegion()
        fixture.note("> burst $a/$b")
        repeat(BURST_CLICKS / 2) {
            driver.click(fixture.center(a))
            driver.click(fixture.center(b))
        }
        journal.reach("burst")
    }

    /**
     * The converging checks of a checkpoint: a click on Compose ground is
     * still counted, and the field still takes focus. The keyboard checks are
     * kept for the end — they type, which the monkey does on its own.
     */
    private suspend fun checkpoint() {
        // A resize may have shrunk the window past where the layout has a
        // useful slot; put the size back before aiming. And undo an injected
        // scale: it moves Compose's density without the platform's, so the
        // slot and the embed's frame are measured in different pixels.
        restoreScale()
        scope.window.setInnerSize(WINDOW_W_DP.toDouble(), WINDOW_H_DP.toDouble())
        scope.settle(STEP_SETTLE_MILLIS)
        fixture.note("> checkpoint ${journal.step}")
        if (fixture.nativeMounted) geometry.expectOnSlot("checkpoint at step ${journal.step}")
        probe.expectResponsive("checkpoint at step ${journal.step}")
    }

    /** Puts the platform's real scale back after a [NativeViewAction.ChangeDpi]. */
    private fun restoreScale() {
        if (driver !is SyntheticPointerDriver) return
        scope.window.dispatch(
            TaoEventCode.SCALE_FACTOR_CHANGED,
            (scope.window.scaleFactor * SCALE_MILLI).roundToInt(),
            0,
        )
    }

    private fun randomRegion(): Region {
        val regions = if (fixture.nativeMounted) Region.entries else Region.entries - Region.OverlayButton
        return regions[random.nextInt(regions.size)]
    }

    private fun randomPointIn(region: Region): Offset {
        val rect = fixture.rect(region) ?: return Offset.Zero
        return Offset(
            rect.left + INSET_PX + random.nextFloat() * (rect.width - 2 * INSET_PX).coerceAtLeast(1f),
            rect.top + INSET_PX + random.nextFloat() * (rect.height - 2 * INSET_PX).coerceAtLeast(1f),
        )
    }

    private fun nextLetter(): Char {
        val current = letter
        letter = if (letter == 'z') 'a' else letter + 1
        return current
    }
}

private const val MONKEY_ACTIONS = 150
private const val CHECKPOINT_EVERY = 15
private const val STORM_ROUNDS = 30

/** Resize storm: discrete steps, the burst, and the animated pass. */
private const val RESIZE_ROUNDS = 12
private const val RESIZE_SPAN = 4
private const val RESIZE_STEP_DP = 60
private const val RESIZE_BURST = 6
private const val ANIMATION_STEPS = 24
private const val ANIMATION_FRAME_MILLIS = 16L

/**
 * How many animation steps the embed may trail the layout by before it is
 * "peeling away". One step is the pipeline (Compose places, the platform
 * allocates a frame later) and a software-rendered X server adds a couple
 * more; a widget visibly detached from the window edge is tens of steps.
 */
private const val ANIMATION_LAG_FRAMES = 8

/** The interactive phase drags the bottom-right corner by this much, in steps of this size. */
private const val EDGE_DRAG_STEPS = 20
private const val EDGE_DRAG_STEP_PX = 10
private const val EDGE_DRAG_STEP_MILLIS = 20L

/** Where inside the outer frame the resize band is pressed (`FrameDecoration.DEFAULT_RESIZE_EDGE_THICKNESS = 5`). */
private const val EDGE_PRESS_INSET_PX = 2

/** Embed frame vs Compose slot: a pixel of rounding each side, more at scale. */
private const val GEOMETRY_TOLERANCE_PX = 2

/** How long an embed may trail its slot after a resize before it is "struggling to follow". */
private const val EMBED_LAG_BUDGET_MILLIS = 500L
private const val NANOS_PER_MILLI = 1_000_000L
private const val BURST_CLICKS = 6
private const val DRAG_STEPS = 4

private const val STORM_CASE_TIMEOUT_MILLIS = 120_000L
private const val MONKEY_CASE_TIMEOUT_MILLIS = 300_000L
private const val CONVERGE_MILLIS = 5_000L

/** Long enough for the loop to deliver a frame, short enough to stay a storm. */
private const val STEP_SETTLE_MILLIS = 25L

/** Samples of the cursor under a still pointer, and their spacing. */
private const val CURSOR_STILL_SAMPLES = 6
private const val CURSOR_STILL_SAMPLE_MILLIS = 50L

/** A one-pixel move off the centre, so the hover is a real move even after a click there. */
private const val CURSOR_NUDGE_PX = 1f

/** Random hover points stay this far inside a region: a pixel on the edge is anyone's. */
private const val INSET_PX = 6f

private const val POINTER_LOG_DEPTH = 48
private const val KEY_SETTLE_MILLIS = 60L

private const val WINDOW_X_DP = 120
private const val WINDOW_Y_DP = 80
private const val WINDOW_W_DP = 760
private const val WINDOW_H_DP = 520
private const val HEADER_H_DP = 64
private const val PAD_DP = 8
private const val BUTTON_W_DP = 160
private const val OVERLAY_H_DP = 56
private const val FONT_SP = 16

private const val MIN_INNER_W_DP = 480.0
private const val INNER_W_SPAN_DP = 400.0
private const val MIN_INNER_H_DP = 320.0
private const val INNER_H_SPAN_DP = 300.0

private val SCALE_HOPS = floatArrayOf(1f, 1.25f, 1.5f, 2f)
private const val SCALE_MILLI = 1000

private const val BACKDROP_ARGB = 0xFF2B2B2B
private const val HEADER_BUTTON_ARGB = 0xFF2D6CDF
private const val OVERLAY_BUTTON_ARGB = 0xFF3AA655
private const val EMPTY_SLOT_ARGB = 0xFF555555

private const val HEX = 16
private const val OUTER_W = 2
private const val OUTER_H = 3

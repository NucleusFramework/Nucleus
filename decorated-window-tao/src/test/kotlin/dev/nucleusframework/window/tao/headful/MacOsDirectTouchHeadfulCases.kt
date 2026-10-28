package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoTouchEvent
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsTouchBridge
import dev.nucleusframework.window.windowDragArea
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * #892: direct touch (touchscreen, Sidecar) on macOS 27+. AppKit hands a
 * finger only to gesture recognizers; the tao content view's forwarder
 * (`macos/direct_touch.m`) must turn each contact into a Compose
 * [PointerType.Touch] pointer at the finger, and its presence must switch
 * AppKit's mouse emulation (tap → click, pan → scroll) off for the view.
 *
 * The contacts are real `NSEventTypeDirectTouch` events queued on `NSApp`
 * ([NativeMetalBridge.nativeDiagInjectDirectTouch]): AppKit's own touch
 * routing — hit testing, recognizer gathering, the emulation decision — runs
 * as for a finger on the screen, then tao's JNI wire and the scene host.
 */
internal object MacOsDirectTouchHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        if (!directTouch) {
            listOf(emulationWithoutTheForwarder())
        } else {
            nativeCases()
        }

    /** `-Dnucleus.tao.directTouch=false`: the host attaches no forwarder. */
    private val directTouch = System.getProperty("nucleus.tao.directTouch", "true").toBoolean()

    private fun nativeCases(): List<TaoWindowTestCase> =
        listOf(
            tapIsATouchPressAtTheFinger(),
            fingerDragIsADragNotAScroll(),
            twoFingersPinch(),
            twoFingersRotate(),
            cancelledTouchDoesNotClick(),
            fingerOnTheTitleBarMovesTheWindow(),
            fingerOutsideAPopupDismissesIt(),
            wheelWhileAFingerIsDownCancelsTheTouch(),
            twoWindowsTrackFingersAtOnce(),
        )

    /** One recorded pointer change: the event type, pointer id, tool, position and contact state. */
    private data class Change(
        val type: PointerEventType,
        val id: Long,
        val tool: PointerType,
        val position: Offset,
        val pressed: Boolean,
    )

    private val recorded = Collections.synchronizedList(mutableListOf<Change>())
    private val clicks = AtomicInteger()

    // Most contacts pressed together in one event.
    private val maxPressed = AtomicInteger()

    private fun snapshot(): List<Change> = synchronized(recorded) { recorded.toList() }

    private fun recorder(): Modifier =
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    maxPressed.accumulateAndGet(event.changes.count { it.pressed }, ::maxOf)
                    event.changes.forEach {
                        recorded += Change(event.type, it.id.value, it.type, it.position, it.pressed)
                    }
                }
            }
        }

    private fun touchCase(
        name: String,
        content: @androidx.compose.runtime.Composable () -> Unit = {
            Box(Modifier.fillMaxSize().then(recorder()).clickable { clicks.incrementAndGet() })
        },
        driver: suspend TaoWindowTestScope.() -> Unit,
    ): TaoWindowTestCase =
        TaoWindowTestCase(
            name = name,
            skip = {
                when {
                    Platform.Current != Platform.MacOS -> "macOS only — AppKit direct touch"
                    !NativeMetalBridge.isLoaded -> "nucleus_tao_metal not loaded"
                    else -> null
                }
            },
            paintDefaultBackground = false,
            nativePopupLayers = true,
            content = { content() },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            window.focus()
            settle()
            check(NativeTaoMacOsTouchBridge.nativeHasDirectTouch(window.handle)) {
                "the scene host must attach the direct touch forwarder to the content view"
            }
            recorded.clear()
            clicks.set(0)
            maxPressed.set(0)
            driver()
        }

    private fun tapIsATouchPressAtTheFinger(): TaoWindowTestCase =
        touchCase("#892 a tap reaches Compose as a Touch press and release at the finger, and clicks") {
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(1, X, Y, TaoTouchEvent.RELEASE))
            awaitUntil("the tap clicked", detail = { "recorded=${snapshot()}" }) { clicks.get() == 1 }
            settle()
            val events = snapshot()
            val press = events.single { it.type == PointerEventType.Press }
            val release = events.single { it.type == PointerEventType.Release }
            check(press.tool == PointerType.Touch && release.tool == PointerType.Touch) {
                "a finger must be a Touch pointer; recorded=$events"
            }
            check(press.id == release.id && !release.pressed) { "press and release of one contact; recorded=$events" }
            val scale = window.scaleFactor
            check(abs(press.position.x - X * scale) <= POSITION_TOLERANCE_PX) {
                "press x ${press.position.x} != finger ${X * scale}; recorded=$events"
            }
            check(abs(press.position.y - Y * scale) <= POSITION_TOLERANCE_PX) {
                "press y ${press.position.y} != finger ${Y * scale}; recorded=$events"
            }
            check(events.none { it.tool == PointerType.Mouse && it.type != PointerEventType.Move }) {
                "AppKit's mouse emulation must be off for the view (no emulated click); recorded=$events"
            }
            check(clicks.get() == 1) { "exactly one click for one tap, got ${clicks.get()}" }
        }

    private val dragTotal = mutableStateOf(Offset.Zero)
    private val dragEnded = AtomicInteger()

    private fun fingerDragIsADragNotAScroll(): TaoWindowTestCase =
        touchCase(
            "#892 a finger drag reaches detectDragGestures as a drag, not as scroll or pan",
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(recorder())
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { dragEnded.incrementAndGet() }) { change, amount ->
                                change.consume()
                                dragTotal.value += amount
                            }
                        },
                )
            },
        ) {
            dragTotal.value = Offset.Zero
            dragEnded.set(0)
            val steps = 10
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            for (i in 1..steps) {
                settle(MOVE_MILLIS)
                frame(Contact(1, X + DRAG_PT * i / steps, Y, TaoTouchEvent.MOVE))
            }
            settle(MOVE_MILLIS)
            frame(Contact(1, X + DRAG_PT, Y, TaoTouchEvent.RELEASE))
            awaitUntil("the drag ended", detail = { "drag=${dragTotal.value} recorded=${snapshot()}" }) {
                dragEnded.get() == 1
            }
            val events = snapshot()
            val scale = window.scaleFactor
            val last = events.last { it.type == PointerEventType.Release }
            check(abs(last.position.x - (X + DRAG_PT) * scale) <= POSITION_TOLERANCE_PX) {
                "the finger ended at ${last.position.x}, expected ${(X + DRAG_PT) * scale}"
            }
            // detectDragGestures keeps the touch slop out of the reported total.
            check(dragTotal.value.x > (DRAG_PT * scale) / 2 && abs(dragTotal.value.y) < 2f) {
                "the drag must follow the finger; total=${dragTotal.value}"
            }
            val scrolls = events.filter { it.type == PointerEventType.Scroll || it.type.toString().startsWith("Pan") }
            check(scrolls.isEmpty()) { "a finger drag must not become scroll / pan events; got $scrolls" }
        }

    private val zoom = mutableStateOf(1f)
    private val rotation = mutableStateOf(0f)

    private fun transformContent(): @androidx.compose.runtime.Composable () -> Unit =
        {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(recorder())
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, gestureRotation ->
                            zoom.value *= gestureZoom
                            rotation.value += gestureRotation
                        }
                    },
            )
        }

    /** Two fingers around (X, Y), [radius] apart from the centre, at [angleDeg]. */
    private suspend fun TaoWindowTestScope.twoFingers(
        radius: Float,
        angleDeg: Float,
        phase: Int,
    ) {
        val a = angleDeg * PI.toFloat() / 180f
        val dx = radius * cos(a)
        val dy = radius * sin(a)
        frame(Contact(1, X - dx, Y - dy, phase), Contact(2, X + dx, Y + dy, phase))
    }

    private fun twoFingersPinch(): TaoWindowTestCase =
        touchCase("#892 two fingers spreading zoom detectTransformGestures", content = transformContent()) {
            zoom.value = 1f
            twoFingers(PINCH_START_PT, 0f, TaoTouchEvent.PRESS)
            val steps = 12
            for (i in 1..steps) {
                settle(MOVE_MILLIS)
                twoFingers(PINCH_START_PT * (1f + i.toFloat() / steps), 0f, TaoTouchEvent.MOVE)
            }
            settle(MOVE_MILLIS)
            twoFingers(PINCH_START_PT * 2f, 0f, TaoTouchEvent.RELEASE)
            settle()
            val events = snapshot()
            check(maxPressed.get() == 2) { "both contacts must be down together; recorded=$events" }
            check(events.map { it.id }.distinct().size == 2) { "two distinct pointer ids; recorded=$events" }
            // Touch slop eats the start of the spread, so the zoom lands under 2×
            // (1.6× here: the first steps go into the slop).
            check(zoom.value in 1.5f..2.05f) { "doubling the finger spacing must zoom ~2×, got ${zoom.value}" }
        }

    private fun twoFingersRotate(): TaoWindowTestCase =
        touchCase("#892 two fingers turning rotate detectTransformGestures", content = transformContent()) {
            rotation.value = 0f
            twoFingers(ROTATE_RADIUS_PT, 0f, TaoTouchEvent.PRESS)
            val steps = 12
            for (i in 1..steps) {
                settle(MOVE_MILLIS)
                twoFingers(ROTATE_RADIUS_PT, ROTATE_DEG * i / steps, TaoTouchEvent.MOVE)
            }
            settle(MOVE_MILLIS)
            twoFingers(ROTATE_RADIUS_PT, ROTATE_DEG, TaoTouchEvent.RELEASE)
            settle()
            // Clockwise on screen (y down) is a positive Compose rotation. The
            // rotation slop (an arc of touch slop at the fingers' spread, ~10°
            // here) is not reported.
            check(rotation.value in (ROTATE_DEG - 15f)..(ROTATE_DEG + 1f)) {
                "turning the fingers $ROTATE_DEG° must rotate ~$ROTATE_DEG°, got ${rotation.value}"
            }
        }

    private fun cancelledTouchDoesNotClick(): TaoWindowTestCase =
        touchCase("#892 a cancelled touch releases its pointer and never clicks") {
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(1, X, Y, TaoTouchEvent.CANCEL))
            awaitUntil("the contact was released", detail = { "recorded=${snapshot()}" }) {
                snapshot().any { it.type == PointerEventType.Release }
            }
            settle()
            check(
                clicks.get() == 0,
            ) { "a cancelled touch must not click; clicks=${clicks.get()} recorded=${snapshot()}" }
            // The next tap still works: the recognizer reached a final state.
            frame(Contact(2, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(2, X, Y, TaoTouchEvent.RELEASE))
            awaitUntil("the next tap clicked", detail = { "recorded=${snapshot()}" }) { clicks.get() == 1 }
        }

    private fun fingerOnTheTitleBarMovesTheWindow(): TaoWindowTestCase =
        touchCase(
            "#892 a finger dragging the title bar moves the window with it",
            content = {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(DRAG_AREA_DP.dp)
                            .background(Color.Gray)
                            .windowDragArea(),
                    )
                    Box(Modifier.fillMaxSize().then(recorder()))
                }
            },
        ) {
            val before = requireNotNull(bounds())
            val scale = window.scaleFactor

            // A finger stays put on the screen while the window moves under it,
            // but the injector takes view-local points: take the window's own
            // move out of each frame, as the display would.
            fun local(
                screenDx: Float,
                screenDy: Float,
            ): Pair<Float, Float> {
                val now = requireNotNull(bounds())
                return Pair(
                    X + screenDx - (now[0] - before[0]) / scale,
                    DRAG_AREA_Y_PT + screenDy - (now[1] - before[1]) / scale,
                )
            }
            // Below AppKit's own title bar strip (traffic lights), inside the drag area.
            frame(Contact(1, X, DRAG_AREA_Y_PT, TaoTouchEvent.PRESS))
            val steps = 8
            for (i in 1..steps) {
                settle(MOVE_MILLIS)
                val (x, y) = local(WINDOW_MOVE_PT * i / steps, WINDOW_MOVE_PT * i / steps)
                frame(Contact(1, x, y, TaoTouchEvent.MOVE))
            }
            settle(MOVE_MILLIS)
            val (x, y) = local(WINDOW_MOVE_PT, WINDOW_MOVE_PT)
            frame(Contact(1, x, y, TaoTouchEvent.RELEASE))
            // The first move only arms the drag, so the window trails the
            // finger by that first step.
            val expected = (WINDOW_MOVE_PT * (steps - 1) / steps) * scale
            awaitUntil(
                "the window followed the finger",
                detail = { "before=${before.toList()} now=${bounds()?.toList()} expected≈+$expected px" },
            ) {
                val now = bounds() ?: return@awaitUntil false
                abs((now[0] - before[0]) - expected) <= WINDOW_MOVE_TOLERANCE_PX &&
                    abs((now[1] - before[1]) - expected) <= WINDOW_MOVE_TOLERANCE_PX
            }
        }

    private val popupShown = mutableStateOf(false)
    private val popupDismissed = AtomicInteger()

    private fun fingerOutsideAPopupDismissesIt(): TaoWindowTestCase =
        touchCase(
            "#892 a finger outside a native popup dismisses it",
            content = {
                Box(Modifier.fillMaxSize().then(recorder())) {
                    if (popupShown.value) {
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset(POPUP_OFFSET_PX, POPUP_OFFSET_PX),
                            onDismissRequest = {
                                popupDismissed.incrementAndGet()
                                popupShown.value = false
                            },
                            properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
                        ) {
                            Box(Modifier.size(POPUP_DP.dp).background(Color.Red))
                        }
                    }
                }
            },
        ) {
            popupDismissed.set(0)
            popupShown.value = true
            settle(POPUP_SETTLE_MILLIS)
            // Far from the popup, which sits at the top-left corner.
            frame(Contact(1, X + 200f, Y + 100f, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(1, X + 200f, Y + 100f, TaoTouchEvent.RELEASE))
            awaitUntil("the popup was dismissed", detail = { "shown=${popupShown.value}" }) {
                popupDismissed.get() == 1
            }
        }

    private fun wheelWhileAFingerIsDownCancelsTheTouch(): TaoWindowTestCase =
        touchCase("#892 a mouse wheel while a finger is down cancels the touch instead of tapping") {
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            // A mouse-only event lists only the mouse: without the cancel it would
            // read as the finger's release, i.e. a tap.
            check(MacScrollWheelProbe.inject(window, X, Y, dx = 0f, dy = -3f, precise = false)) {
                "scroll wheel not injected"
            }
            settle(STEP_MILLIS)
            frame(Contact(1, X, Y, TaoTouchEvent.RELEASE))
            settle()
            check(clicks.get() == 0) { "the interrupted touch must not click; recorded=${snapshot()}" }
            // The finger's own lift, after the cancel, starts nothing either.
            frame(Contact(2, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(2, X, Y, TaoTouchEvent.RELEASE))
            awaitUntil("a later tap still clicks", detail = { "recorded=${snapshot()}" }) { clicks.get() == 1 }
        }

    private val secondPresses = AtomicInteger()
    private val secondWindow = mutableStateOf<dev.nucleusframework.window.tao.TaoWindow?>(null)

    /**
     * macOS 27 keeps gestures to one view hierarchy at a time unless a view
     * opts out (`exclusiveGestureBehavior`); the forwarder does, so a finger
     * on each of two windows is two live contacts.
     */
    private fun twoWindowsTrackFingersAtOnce(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#892 a finger on each of two windows reaches both at once",
            skip = {
                when {
                    Platform.Current != Platform.MacOS -> "macOS only — AppKit direct touch"
                    !NativeMetalBridge.isLoaded -> "nucleus_tao_metal not loaded"
                    else -> null
                }
            },
            paintDefaultBackground = false,
            applicationContent = {
                DecoratedWindow(
                    onCloseRequest = {},
                    state =
                        rememberWindowState(
                            position = WindowPosition.Absolute(SECOND_WINDOW_DP.dp, SECOND_WINDOW_DP.dp),
                            size = DpSize(SECOND_WINDOW_DP.dp, SECOND_WINDOW_DP.dp),
                        ),
                    title = "tao-headful #892 second window",
                ) {
                    val host = window
                    DisposableEffect(host) {
                        secondWindow.value = host
                        onDispose { secondWindow.value = null }
                    }
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press &&
                                        event.changes.all { it.type == PointerType.Touch }
                                    ) {
                                        secondPresses.incrementAndGet()
                                    }
                                }
                            }
                        },
                    )
                }
            },
            content = { Box(Modifier.fillMaxSize().then(recorder())) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("second window published") { secondWindow.value?.outerBoundsPx() != null }
            val second = requireNotNull(secondWindow.value)
            settle()
            recorded.clear()
            secondPresses.set(0)
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frameIn(second, Contact(2, SECOND_X, SECOND_Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            // Both still down: each window moves its own finger.
            frame(Contact(1, X + 10f, Y, TaoTouchEvent.MOVE))
            frameIn(second, Contact(2, SECOND_X + 10f, SECOND_Y, TaoTouchEvent.MOVE))
            settle(STEP_MILLIS)
            frame(Contact(1, X + 10f, Y, TaoTouchEvent.RELEASE))
            frameIn(second, Contact(2, SECOND_X + 10f, SECOND_Y, TaoTouchEvent.RELEASE))
            awaitUntil("the second window's finger landed", detail = { "main=${snapshot()}" }) {
                secondPresses.get() == 1
            }
            settle()
            val main = snapshot()
            check(main.count { it.type == PointerEventType.Press } == 1) { "one press in the main window; $main" }
            check(main.any { it.type == PointerEventType.Move && it.pressed }) {
                "the main window's finger must keep moving while the other window is touched; $main"
            }
            check(main.any { it.type == PointerEventType.Release && !it.pressed }) {
                "the main window's finger must lift as a release; $main"
            }
        }

    /**
     * The fallback (`-Dnucleus.tao.directTouch=false`), and what every Nucleus
     * app got before #892: no recognizer on the view, so AppKit's emulation
     * turns a tap into a mouse click. A one-finger pan is only logged: under
     * this injection the emulation sends one mouseDown and nothing after it
     * (no drag, no mouseUp, no scroll) — on a bare AppKit view too — which
     * only hardware can tell apart from an injection artefact.
     */
    private fun emulationWithoutTheForwarder(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#892 without the forwarder AppKit emulates a mouse: a tap clicks",
            skip = {
                when {
                    Platform.Current != Platform.MacOS -> "macOS only — AppKit direct touch"
                    !NativeMetalBridge.isLoaded -> "nucleus_tao_metal not loaded"
                    else -> null
                }
            },
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().then(recorder()).clickable { clicks.incrementAndGet() }) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            window.focus()
            settle()
            check(!NativeTaoMacOsTouchBridge.nativeHasDirectTouch(window.handle)) { "no forwarder when disabled" }
            recorded.clear()
            clicks.set(0)
            frame(Contact(1, X, Y, TaoTouchEvent.PRESS))
            settle(STEP_MILLIS)
            frame(Contact(1, X, Y, TaoTouchEvent.RELEASE))
            awaitUntil("the emulated click landed", detail = { "recorded=${snapshot()}" }) { clicks.get() == 1 }
            val tap = snapshot()
            check(tap.filter { it.type == PointerEventType.Press }.all { it.tool == PointerType.Mouse }) {
                "an emulated tap is a mouse press; recorded=$tap"
            }
            recorded.clear()
            frame(Contact(2, X, Y, TaoTouchEvent.PRESS))
            for (i in 1..10) {
                settle(MOVE_MILLIS)
                frame(Contact(2, X, Y - DRAG_PT * i / 10, TaoTouchEvent.MOVE))
            }
            settle(MOVE_MILLIS)
            frame(Contact(2, X, Y - DRAG_PT, TaoTouchEvent.RELEASE))
            settle(POPUP_SETTLE_MILLIS)
            val pan = snapshot()
            println("[#892 emulation] finger pan → ${pan.map { "${it.type}/${it.tool}/${it.pressed}" }.distinct()}")
            check(
                pan.none { it.tool == PointerType.Touch },
            ) { "the emulation never yields a Touch pointer; recorded=$pan" }
        }

    // ── Driver ─────────────────────────────────────────────────────────────

    private class Contact(
        val finger: Int,
        val x: Float,
        val y: Float,
        val phase: Int,
    )

    private fun TaoWindowTestScope.frame(vararg contacts: Contact) = frameIn(window, *contacts)

    private fun frameIn(
        target: dev.nucleusframework.window.tao.TaoWindow,
        vararg contacts: Contact,
    ) {
        val delivered =
            NativeMetalBridge.nativeDiagInjectDirectTouch(
                target.nativeHandle,
                IntArray(contacts.size) { contacts[it].finger },
                FloatArray(contacts.size) { contacts[it].x },
                FloatArray(contacts.size) { contacts[it].y },
                IntArray(contacts.size) { contacts[it].phase },
            )
        check(delivered) { "direct touch frame not queued — NUCLEUS_TAO_INPUT_INJECTION=1? private symbols gone?" }
    }

    /** Content-local points, clear of the title bar and the resize band. */
    private const val X = 300f
    private const val Y = 300f
    private const val DRAG_PT = 160f
    private const val PINCH_START_PT = 50f
    private const val ROTATE_DEG = 60f
    private const val ROTATE_RADIUS_PT = 120f
    private const val SECOND_WINDOW_DP = 360
    private const val SECOND_X = 180f
    private const val SECOND_Y = 180f
    private const val WINDOW_MOVE_PT = 80f
    private const val DRAG_AREA_DP = 90f
    private const val DRAG_AREA_Y_PT = 60f
    private const val POPUP_DP = 120
    private const val POPUP_OFFSET_PX = 20
    private const val STEP_MILLIS = 60L
    private const val MOVE_MILLIS = 16L
    private const val POPUP_SETTLE_MILLIS = 600L
    private const val POSITION_TOLERANCE_PX = 1.5f
    private const val WINDOW_MOVE_TOLERANCE_PX = 4L
}

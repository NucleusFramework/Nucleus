package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.nucleusNsPlatformView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * #920 — `NativeView(placedAsOverlay = true)` under real OS input: the AWT
 * Robot goes through the WindowServer and AppKit's own hit-testing, which is
 * the whole point of an overlay. A native button takes the click itself, a
 * native field the keyboard and gives it back, the wheel still scrolls the
 * Compose list under a native view, the clipped-away part of a view is
 * Compose's, and Compose does not see the pointer while it is on the view.
 */
internal object NativeViewOverlayInputHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            buttonTakesARealClick(),
            textFieldTakesFocusAndKeysThenGivesThemBack(),
            wheelOverComposeScrolls(),
            wheelOverAnOverlayButtonScrolls(),
            composeSeesNoMovesUnderAnOverlay(),
            clickOnTheClippedPartGoesToCompose(),
        )

    private fun buttonTakesARealClick() =
        case("overlay NSButton takes a real click, Compose under it gets none") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            val composeBefore = f.composePresses.get()
            driver.click(f.center(OverlaySlot.Button))
            awaitUntil("the button's action ran", detail = f::describe) { f.buttonClicks() == 1 }
            settle()
            check(f.composePresses.get() == composeBefore) {
                "Compose also got the press on the button: ${f.describe()}"
            }
        }

    private fun textFieldTakesFocusAndKeysThenGivesThemBack() =
        case("overlay NSTextField takes focus and keys, a Compose field takes them back") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            driver.click(f.center(OverlaySlot.NativeField))
            awaitUntil("the native field is editing", detail = f::describe) { f.nativeFieldEditing() }
            "abc".forEach { driver.type(it) }
            awaitUntil("the native field holds abc", detail = f::describe) { f.nativeFieldText() == "abc" }
            check(f.composeText.isEmpty()) { "keys leaked to Compose: ${f.describe()}" }

            driver.click(f.center(OverlaySlot.ComposeField))
            awaitUntil("Compose holds the keyboard", detail = f::describe) {
                f.composeFocused && !f.nativeFieldEditing()
            }
            "xy".forEach { driver.type(it) }
            awaitUntil("the Compose field holds xy", detail = f::describe) { f.composeText == "xy" }
            check(f.nativeFieldText() == "abc") { "keys also reached the native field: ${f.describe()}" }
        }

    private fun wheelOverComposeScrolls() =
        case("wheel over plain Compose scrolls the list (control)") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            driver.moveTo(f.groundPoint())
            settle()
            f.wheelUntilScrolled(this, WHEEL_NOTCHES)
        }

    private fun wheelOverAnOverlayButtonScrolls() =
        case("wheel over an overlay NSButton scrolls the list") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            driver.moveTo(f.center(OverlaySlot.Button))
            settle()
            f.wheelUntilScrolled(this, WHEEL_NOTCHES)
        }

    private fun composeSeesNoMovesUnderAnOverlay() =
        case("Compose does not see the pointer while it is on an overlay") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            val button = f.rect(OverlaySlot.Button)
            f.moves.clear()
            f.log.clear()
            // A slow crossing over the button, from the left to the right.
            for (i in 0..CROSSING_STEPS) {
                val x = button.left + button.width * i / CROSSING_STEPS
                driver.moveTo(Offset(x, button.center.y))
                settle(CROSSING_STEP_MILLIS)
            }
            settle()
            val inside = f.moves.filter { button.deflate(EDGE_PX).contains(it) }
            check(inside.isEmpty()) {
                "Compose received ${inside.size} moves under the overlay button, e.g. ${inside.take(3)}"
            }
            check(f.log.any { it.startsWith("Exit") }) { "Compose was never told the pointer left: ${f.describe()}" }

            // Back on Compose ground, Compose follows the pointer again.
            val ground = f.groundPoint()
            f.moves.clear()
            driver.moveTo(ground)
            awaitUntil("Compose sees the pointer back on its ground", detail = f::describe) {
                f.moves.any { (it - ground).getDistance() < EDGE_PX }
            }
        }

    private fun clickOnTheClippedPartGoesToCompose() =
        case("a click on the clipped-away part of an overlay goes to Compose") { f, driver ->
            driver.click(f.groundPoint())
            settle()
            val button = f.rect(OverlaySlot.Button)
            val viewport = f.rect(OverlaySlot.Viewport)
            // Scroll until the top half of the button is above the viewport.
            val cut = (button.top - viewport.top + button.height / 2).toInt()
            f.scroll.scrollTo(cut)
            awaitUntil("the overlay is masked by the viewport", detail = f::describe) { f.overlayMasks() }
            val hidden = Offset(button.center.x, viewport.top - button.height / 4)
            val composeBefore = f.composePresses.get()
            driver.click(hidden)
            awaitUntil("Compose got the press", detail = f::describe) { f.composePresses.get() > composeBefore }
            settle()
            check(f.buttonClicks() == 0) { "the hidden half of the button took the click: ${f.describe()}" }
        }

    private fun case(
        name: String,
        body: suspend TaoWindowTestScope.(OverlayInputFixture, PointerDriver) -> Unit,
    ): TaoWindowTestCase {
        val fixture = OverlayInputFixture()
        return TaoWindowTestCase(
            name = "#924 $name",
            skip = { if (Platform.Current != Platform.MacOS) "macOS only" else robotDriverSkipReason() },
            windowState =
                WindowState(
                    position = WindowPosition.Absolute(WINDOW_X_DP.dp, WINDOW_Y_DP.dp),
                    size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
                ),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
        ) {
            fixture.content = NativeTaoBridge.nativeNsViewHandle(window.handle)
            awaitUntil("the fixture is laid out and mounted", detail = fixture::describe) { fixture.ready() }
            window.focus()
            settle(600)
            val driver = RobotPointerDriver(window) { fixture.sceneSize }
            try {
                body(fixture, driver)
            } finally {
                driver.exit()
            }
        }
    }

    private const val WINDOW_X_DP = 140
    private const val WINDOW_Y_DP = 140
    private const val WINDOW_W_DP = 760
    private const val WINDOW_H_DP = 520
    private const val WHEEL_NOTCHES = 3
    private const val CROSSING_STEPS = 12
    private const val CROSSING_STEP_MILLIS = 30L
    private const val EDGE_PX = 4f
}

private enum class OverlaySlot { ComposeField, Viewport, Button, NativeField, Ground }

private class OverlayInputFixture {
    var content: Long = 0L
    var sceneSize: IntSize = IntSize.Zero
        private set
    val scroll = ScrollState(0)
    var composeText by mutableStateOf("")
    var composeFocused by mutableStateOf(false)
    val composePresses = AtomicInteger()
    val moves = ConcurrentLinkedQueue<Offset>()
    val log = ConcurrentLinkedQueue<String>()

    private val rects = ConcurrentHashMap<OverlaySlot, Rect>()

    @Volatile
    private var button = 0L

    @Volatile
    private var field = 0L

    fun ready(): Boolean =
        rects.size == OverlaySlot.entries.size &&
            button != 0L &&
            field != 0L &&
            NativeTaoMacOsNativeViewBridge.nativeDiagOverlayClip(content, button)?.get(HIDDEN) == 0

    fun rect(slot: OverlaySlot): Rect = requireNotNull(rects[slot]) { "$slot has no rect yet" }

    fun center(slot: OverlaySlot): Offset = rect(slot).center

    fun groundPoint(): Offset = rect(OverlaySlot.Ground).let { Offset(it.left + 40f, it.top + 40f) }

    fun buttonClicks(): Int = NativeTaoMacOsNativeViewBridge.nativeDiagButtonClicks(button)

    fun nativeFieldEditing(): Boolean = NativeTaoMacOsNativeViewBridge.nativeDiagViewIsEditing(field)

    fun nativeFieldText(): String = NativeTaoMacOsNativeViewBridge.nativeDiagTextFieldString(field).orEmpty()

    fun overlayMasks(): Boolean = overlayClip()?.get(MASKS) == 1

    private fun overlayClip(): IntArray? = NativeTaoMacOsNativeViewBridge.nativeDiagOverlayClip(content, button)

    /**
     * Wheels down and expects the list to move. The list starts at the top, and
     * natural scrolling flips the wheel's sign, so a burst that moves nothing is
     * repeated the other way round.
     */
    suspend fun wheelUntilScrolled(
        scope: TaoWindowTestScope,
        notches: Int,
    ) {
        wheel(notches)
        if (scope.awaitUntilOrTimeout(WHEEL_WAIT_MILLIS) { scroll.value != 0 }) return
        wheel(-notches)
        scope.awaitUntil("the list scrolled", detail = ::describe) { scroll.value != 0 }
    }

    private suspend fun wheel(notches: Int) {
        checkNotNull(
            HeadfulRobot.inject { robot ->
                repeat(WHEEL_TICKS) {
                    robot.mouseWheel(notches)
                    Thread.sleep(WHEEL_TICK_MILLIS)
                }
            },
        ) { "the AWT Robot is unavailable: ${HeadfulRobot.unavailableReason}" }
    }

    fun describe(): String =
        "button clicks=${buttonClicks()}, compose presses=${composePresses.get()}, scroll=${scroll.value}, " +
            "native field='${nativeFieldText()}' editing=${nativeFieldEditing()}, " +
            "compose field='$composeText' focused=$composeFocused, " +
            "button clip=${overlayClip()?.contentToString()}, " +
            "aim=${HeadfulRobot.lastAimReport}, events=$log"

    private fun Modifier.record(slot: OverlaySlot) = onGloballyPositioned { rects[slot] = it.boundsInRoot() }

    @Composable
    fun Content() {
        val buttonHandle = remember { NativeTaoMacOsNativeViewBridge.nativeDiagCreateButton().also { button = it } }
        val fieldHandle = remember { NativeTaoMacOsNativeViewBridge.nativeDiagCreateTextField().also { field = it } }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF2B2B2B))
                .onGloballyPositioned { sceneSize = it.size }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val position = event.changes.firstOrNull()?.position ?: continue
                            if (event.type != PointerEventType.Move) {
                                if (log.size > LOG_DEPTH) log.poll()
                                val delta = event.changes.first().scrollDelta
                                val suffix = if (event.type == PointerEventType.Scroll) " d=$delta" else ""
                                log += "${event.type}@${position.x.toInt()},${position.y.toInt()}$suffix"
                            }
                            when (event.type) {
                                PointerEventType.Press -> composePresses.incrementAndGet()
                                PointerEventType.Move, PointerEventType.Enter -> moves += position
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(8.dp)) {
                    BasicTextField(
                        value = composeText,
                        onValueChange = { composeText = it },
                        modifier =
                            Modifier
                                .width(300.dp)
                                .height(44.dp)
                                .background(Color.White)
                                .padding(8.dp)
                                .onFocusChanged { composeFocused = it.isFocused }
                                .record(OverlaySlot.ComposeField),
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(380.dp).record(OverlaySlot.Viewport)) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 360.dp)) {
                        Spacer(Modifier.height(80.dp))
                        NativeView(
                            factory = {
                                nucleusNsPlatformView(
                                    handle = { buttonHandle },
                                    onDispose = { NativeTaoMacOsNativeViewBridge.nativeDiagReleaseView(buttonHandle) },
                                )
                            },
                            modifier = Modifier.size(200.dp, 40.dp).record(OverlaySlot.Button),
                            placedAsOverlay = true,
                        )
                        Spacer(Modifier.height(30.dp))
                        NativeView(
                            factory = {
                                nucleusNsPlatformView(
                                    handle = { fieldHandle },
                                    onDispose = { NativeTaoMacOsNativeViewBridge.nativeDiagReleaseView(fieldHandle) },
                                )
                            },
                            modifier = Modifier.size(240.dp, 30.dp).record(OverlaySlot.NativeField),
                            placedAsOverlay = true,
                        )
                        Box(Modifier.fillMaxWidth().height(1600.dp).record(OverlaySlot.Ground))
                    }
                }
            }
        }
    }

    private companion object {
        const val HIDDEN = 4
        const val MASKS = 5
        const val WHEEL_TICKS = 8
        const val WHEEL_TICK_MILLIS = 40L
        const val WHEEL_WAIT_MILLIS = 1_500L
        const val LOG_DEPTH = 20
    }
}

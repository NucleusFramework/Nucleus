package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.nucleusframework.core.runtime.Platform
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #736 — on macOS a Control-click is the secondary click. It must reach
 * Compose as `PointerButton.Secondary` with `isSecondaryPressed`, the release
 * included even once Control is let go first, in the window and in a native
 * popup panel alike; and it must open a `ContextMenuArea`. Real OS input
 * through the AWT Robot.
 */
internal object MacOsControlClickHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(controlClickInWindow(), controlClickOpensContextMenuArea(), controlClickInNativePopup())

    private fun controlClickInWindow(): TaoWindowTestCase {
        val recorder = ButtonRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#736 Control-click is a secondary click in the window",
            skip = ::macOnly,
            paintDefaultBackground = false,
            content = {
                Recording(recorder, Modifier.fillMaxSize().onGloballyPositioned { scene.value = it.size })
            },
        ) {
            awaitUntil("scene measured") { scene.value.width > 0 }
            val driver = RobotPointerDriver(window) { scene.value }
            val center = Offset(scene.value.width / 2f, scene.value.height / 2f)
            window.focus()
            driver.click(center)
            awaitUntil("plain click recorded") { recorder.count(PointerEventType.Release) >= 1 }
            recorder.assertClick(PointerButton.Primary, "a plain left click")
            recorder.reset()

            controlClick(driver, center)
            awaitUntil("Control-click recorded", detail = recorder::describe) {
                recorder.count(PointerEventType.Release) >= 1
            }
            recorder.assertClick(PointerButton.Secondary, "a Control-click")
        }
    }

    private fun controlClickOpensContextMenuArea(): TaoWindowTestCase {
        val scene = SceneSize()
        val menuShown = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "#736 Control-click opens a ContextMenuArea",
            skip = ::macOnly,
            paintDefaultBackground = false,
            content = {
                ContextMenuArea(items = {
                    menuShown.set(true)
                    listOf(ContextMenuItem("Item") {})
                }) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { scene.value = it.size })
                }
            },
        ) {
            awaitUntil("scene measured") { scene.value.width > 0 }
            val driver = RobotPointerDriver(window) { scene.value }
            val center = Offset(scene.value.width / 2f, scene.value.height / 2f)
            window.focus()
            driver.click(center)
            settle()
            check(!menuShown.get()) { "a plain left click must not open the context menu" }
            controlClick(driver, center)
            awaitUntil("ContextMenuArea opened on Control-click") { menuShown.get() }
        }
    }

    private fun controlClickInNativePopup(): TaoWindowTestCase {
        val recorder = ButtonRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#736 Control-click is a secondary click in a native popup panel",
            skip = ::macOnly,
            nativePopupLayers = true,
            paintDefaultBackground = false,
            content = {
                Box(Modifier.fillMaxSize().onGloballyPositioned { scene.value = it.size })
                Popup(alignment = Alignment.Center) { Recording(recorder, Modifier.size(POPUP_DP.dp)) }
            },
        ) {
            awaitUntil("scene measured") { scene.value.width > 0 }
            settle()
            val driver = RobotPointerDriver(window) { scene.value }
            val center = Offset(scene.value.width / 2f, scene.value.height / 2f)
            window.focus()
            driver.click(center)
            awaitUntil("plain click recorded in the popup") { recorder.count(PointerEventType.Release) >= 1 }
            recorder.assertClick(PointerButton.Primary, "a plain left click in the popup")
            recorder.reset()

            controlClick(driver, center)
            awaitUntil("Control-click recorded in the popup", detail = recorder::describe) {
                recorder.count(PointerEventType.Release) >= 1
            }
            recorder.assertClick(PointerButton.Secondary, "a Control-click in the popup")
        }
    }

    /** Control down, left press, Control up, left release — the release must still be secondary. */
    private suspend fun controlClick(
        driver: RobotPointerDriver,
        at: Offset,
    ) {
        driver.moveTo(at)
        val ok =
            HeadfulRobot.inject { robot ->
                HeadfulRobot.notePress()
                robot.keyPress(KeyEvent.VK_CONTROL)
                try {
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                } finally {
                    robot.keyRelease(KeyEvent.VK_CONTROL)
                }
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                HeadfulRobot.noteRelease()
                true
            }
        checkNotNull(ok) { "the AWT Robot became unavailable: ${HeadfulRobot.unavailableReason}" }
    }

    private fun macOnly(): String? =
        if (Platform.Current != Platform.MacOS) "Control-click is macOS's secondary click" else robotDriverSkipReason()

    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    private data class Recorded(
        val type: PointerEventType,
        val button: PointerButton?,
        val primary: Boolean,
        val secondary: Boolean,
    )

    private class ButtonRecorder {
        private val events = Collections.synchronizedList(mutableListOf<Recorded>())

        fun add(r: Recorded) {
            events += r
        }

        fun snapshot(): List<Recorded> = synchronized(events) { events.toList() }

        fun reset() = events.clear()

        fun count(type: PointerEventType): Int = snapshot().count { it.type == type }

        fun describe(): String = snapshot().joinToString(prefix = "[", postfix = "]")

        fun assertClick(
            expected: PointerButton,
            what: String,
        ) {
            val press = snapshot().firstOrNull { it.type == PointerEventType.Press }
            val release = snapshot().firstOrNull { it.type == PointerEventType.Release }
            val secondary = expected == PointerButton.Secondary
            check(press?.button == expected && press.secondary == secondary && press.primary == !secondary) {
                "$what must press $expected; recorded=${describe()}"
            }
            check(release?.button == expected) { "$what must release $expected; recorded=${describe()}" }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Recording(
        recorder: ButtonRecorder,
        modifier: Modifier,
    ) {
        Box(
            modifier.pointerInput(recorder) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                            recorder.add(
                                Recorded(
                                    event.type,
                                    event.button,
                                    event.buttons.isPrimaryPressed,
                                    event.buttons.isSecondaryPressed,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    private const val POPUP_DP = 160
}

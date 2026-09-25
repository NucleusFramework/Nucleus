package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoKeyLocation
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.workspace.clientOriginPx
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.math.roundToInt

/**
 * The two ways a headful case can put a pointer and a keyboard on the case
 * window, behind one interface so a storm or a monkey runs unchanged on both.
 *
 * Positions are **content** pixels (physical, top-left of the Compose scene),
 * the space every fixture measures its rects in.
 *
 *  - [SyntheticPointerDriver] posts the very events the native loop posts,
 *    straight into the window. Deterministic, runs everywhere including
 *    native Wayland, and reaches everything Compose owns — but it enters
 *    *after* the platform's own routing, so on Linux a click on an embed
 *    never becomes a GDK event and the widget never sees it ([reachesNative]).
 *  - [RobotPointerDriver] moves the real OS pointer and presses the real
 *    buttons. It is the only way to exercise the platform half of a native
 *    view — the GtkEventBox capture, AppKit's responder chain, Win32 focus —
 *    which is where the focus races live. Needs an X server (or a real
 *    macOS / Windows session); see [HeadfulRobot].
 */
internal interface PointerDriver {
    val name: String

    /** Whether a press on an embedded native widget reaches the widget itself. */
    val reachesNative: Boolean

    /** Whether a key typed while the embed holds the keyboard reaches the widget itself. */
    val typesIntoNative: Boolean

    suspend fun moveTo(contentPx: Offset)

    suspend fun press(button: Int = TaoMouseButton.LEFT)

    suspend fun release(button: Int = TaoMouseButton.LEFT)

    /** Takes the pointer out of the window. */
    suspend fun exit()

    /** Types one lower-case ASCII letter into whatever holds the keyboard. */
    suspend fun type(letter: Char)

    /** Presses and releases the left arrow — a caret move, which only a `KeyDown` can carry. */
    suspend fun arrowLeft()

    suspend fun click(
        contentPx: Offset,
        button: Int = TaoMouseButton.LEFT,
    ) {
        moveTo(contentPx)
        press(button)
        release(button)
    }
}

/** In-process injection through `TaoWindow.dispatch` — see [PointerDriver]. */
internal class SyntheticPointerDriver(
    private val window: TaoWindow,
) : PointerDriver {
    override val name: String = "synthetic"

    // GTK only forwards a *live* GDK event onto an embed; a dispatched press
    // has none. AppKit and Win32 synthesise a real event from the position.
    override val reachesNative: Boolean = Platform.Current != Platform.Linux

    // Win32 delivers keys to the focused HWND itself, so a key dispatched
    // into the Tao window enters above the child and never reaches it. The
    // AppKit host forwards to the first responder either way.
    override val typesIntoNative: Boolean = reachesNative && Platform.Current != Platform.Windows

    override suspend fun moveTo(contentPx: Offset) = window.pointerMove(contentPx)

    override suspend fun press(button: Int) = window.pointerPress(button)

    override suspend fun release(button: Int) = window.pointerRelease(button)

    override suspend fun exit() = window.pointerExit()

    override suspend fun type(letter: Char) {
        window.dispatchKey(TaoEventCode.KEY_TYPED, 0, TaoKeyLocation.STANDARD, 0, letter.code)
    }

    override suspend fun arrowLeft() {
        window.dispatchKey(TaoEventCode.KEY_DOWN, KeyEvent.VK_LEFT, TaoKeyLocation.STANDARD, 0, 0)
        window.dispatchKey(TaoEventCode.KEY_UP, KeyEvent.VK_LEFT, TaoKeyLocation.STANDARD, 0, 0)
    }
}

/**
 * Real OS input through the AWT Robot — see [PointerDriver]. [sceneSize]
 * reads the scene's current size in physical px, which together with the
 * window's outer frame locates the content on screen (`clientOriginPx`);
 * the Robot itself speaks logical screen points.
 */
internal class RobotPointerDriver(
    private val window: TaoWindow,
    private val sceneSize: () -> IntSize,
) : PointerDriver {
    override val name: String = "robot"
    override val reachesNative: Boolean = true
    override val typesIntoNative: Boolean = true

    override suspend fun moveTo(contentPx: Offset) {
        val (x, y) = screenPoint(contentPx)
        inject { robot ->
            robot.mouseMove(x, y)
            HeadfulRobot.noteAim(x, y)
        }
    }

    override suspend fun press(button: Int) {
        val mask = mask(button)
        inject { robot ->
            HeadfulRobot.notePress()
            robot.mousePress(mask)
        }
    }

    override suspend fun release(button: Int) {
        val mask = mask(button)
        inject { robot -> robot.mouseRelease(mask) }
    }

    override suspend fun exit() {
        val outer = window.outerBoundsPx() ?: return
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        // Just past the right edge, level with the middle: on screen for any
        // window the suite places, outside anything the window owns.
        val x = ((outer[0] + outer[OUTER_W] + EXIT_MARGIN_PX) / scale).roundToInt()
        val y = ((outer[1] + outer[OUTER_H] / 2) / scale).roundToInt()
        inject { robot -> robot.mouseMove(x, y) }
    }

    override suspend fun type(letter: Char) {
        require(letter in 'a'..'z') { "only lower-case ASCII letters are typed: '$letter'" }
        val code = KeyEvent.getExtendedKeyCodeForChar(letter.code)
        inject { robot ->
            robot.keyPress(code)
            robot.keyRelease(code)
        }
    }

    override suspend fun arrowLeft() {
        inject { robot ->
            robot.keyPress(KeyEvent.VK_LEFT)
            robot.keyRelease(KeyEvent.VK_LEFT)
        }
    }

    private fun screenPoint(contentPx: Offset): Pair<Int, Int> {
        val outer = requireNotNull(window.outerBoundsPx()) { "the case window is not mapped" }
        val origin = clientOriginPx(outer, sceneSize())
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        return ((origin.x + contentPx.x) / scale).roundToInt() to ((origin.y + contentPx.y) / scale).roundToInt()
    }

    private suspend fun inject(gesture: (java.awt.Robot) -> Unit) {
        val ok =
            HeadfulRobot.inject { robot ->
                gesture(robot)
                true
            }
        checkNotNull(ok) { "the AWT Robot became unavailable mid-run: ${HeadfulRobot.unavailableReason}" }
    }

    private fun mask(button: Int): Int =
        when (button) {
            TaoMouseButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
            TaoMouseButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }

    private companion object {
        const val OUTER_W = 2
        const val OUTER_H = 3
        const val EXIT_MARGIN_PX = 40
    }
}

/**
 * Why the [RobotPointerDriver] cannot run here, or null when it can: the
 * Robot's own latched failure, or a Wayland session — the JDK routes
 * injection through the RemoteDesktop portal there, which blocks until the
 * suite gives up on it and then silently skips every robot case.
 */
internal fun robotDriverSkipReason(): String? {
    robotSkipReason()?.let { return it }
    if (Platform.Current == Platform.Linux && System.getenv("WAYLAND_DISPLAY") != null) {
        return "the AWT Robot cannot inject into a Wayland compositor (WAYLAND_DISPLAY is set)"
    }
    return null
}

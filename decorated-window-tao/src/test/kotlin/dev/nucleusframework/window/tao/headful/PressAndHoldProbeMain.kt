package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.taoApplication
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Child process of `TaoPressAndHoldE2ETest` (#611/#612): opens a real Tao
 * window with a focused `BasicTextField` and replays macOS press-and-hold
 * keyboard traffic against it, exactly as recorded from AppKit driving a
 * document-backed reference `NSTextInputClient` (an `NSTextView`).
 *
 * Runs as a separate process because `ApplePressAndHoldEnabled` must be
 * controlled per scenario through `NSArgumentDomain` (JVM argv), and because
 * `taoApplication` ends in `exitProcess(0)`.
 *
 * Scenario is `args[0]`; the reference behavior (what Chrome, Notes and
 * TextEdit do) is asserted by the parent from the `[pah] text='…'` marker:
 *
 * - `disabled-repeat` — launched with `-ApplePressAndHoldEnabled NO`: hold a
 *   letter → every autorepeat types. Expected `eeeeee`.
 * - `enabled-hold` — defaults untouched: hold a letter (repeats are consumed
 *   while the picker engages — or type, where it cannot), then type `x`. The
 *   held letter must never go dead and `x` must never be eaten.
 *   Expected `^e+x$`.
 * - `picker-replay` — defaults untouched: commit `e`, replay the picker's
 *   protocol traffic — a `selectedRange` query while the key is down, then
 *   `insertText:"é" replacementRange:{0, 1}`. Expected `é`.
 * - `rollover` — defaults untouched: type `xcode` with the `o` still down
 *   when `d` goes down (fast-typist roll-over) and a `selectedRange` query
 *   in the overlap window. Expected `xcode`.
 */
object PressAndHoldProbeMain {
    private const val WATCHDOG_MS = 60_000L
    private const val WATCHDOG_EXIT_CODE = 42

    private const val KEY_E = 0x0E
    private const val KEY_X = 0x07
    private const val KEY_C = 0x08
    private const val KEY_O = 0x1F
    private const val KEY_D = 0x02
    private const val KEY_ESCAPE = 0x35

    private const val FOCUS_SETTLE_MS = 700L
    private const val KEY_GAP_MS = 80L
    private const val REPEAT_GAP_MS = 100L
    private const val REPEATS = 5
    private const val AWAIT_TIMEOUT_MS = 5_000L
    private const val AWAIT_POLL_MS = 25L
    private const val FINAL_SETTLE_MS = 600L

    private val text = AtomicReference("")
    private val focused = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val scenario = args.firstOrNull() ?: error("usage: PressAndHoldProbeMain <scenario>")
        thread(isDaemon = true, name = "pah-probe-watchdog") {
            Thread.sleep(WATCHDOG_MS)
            Runtime.getRuntime().halt(WATCHDOG_EXIT_CODE)
        }

        taoApplication {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(480.dp, 360.dp)),
                title = "press-and-hold probe",
            ) {
                val requester = remember { FocusRequester() }
                var field by remember { mutableStateOf(TextFieldValue("")) }
                LaunchedEffect(Unit) {
                    requester.requestFocus()
                    focused.set(true)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        text.set(it.text)
                    },
                    modifier = Modifier.fillMaxSize().focusRequester(requester),
                )
                val window = window
                LaunchedEffect(window) {
                    runCatching { drive(scenario, window) }
                        .onFailure { println("[pah] error=${it.message}") }
                    println("[pah] text='${text.get()}'")
                    exitApplication()
                }
            }
        }
        exitProcess(0)
    }

    private suspend fun drive(
        scenario: String,
        window: TaoWindow,
    ) {
        awaitUntil("text field focused") { focused.get() }
        delay(FOCUS_SETTLE_MS)
        val handle = window.handle
        when (scenario) {
            "disabled-repeat" -> driveHold(handle, tailKey = null)
            "enabled-hold" -> driveHold(handle, tailKey = KEY_X to "x")
            "picker-replay" -> drivePickerReplay(handle)
            "rollover" -> driveRollover(handle)
            else -> error("unknown scenario '$scenario'")
        }
        // Dismiss any accent picker this scenario left engaged — a lingering
        // bubble steals the next scenario's keystrokes (it is a system
        // window, so it outlives this child process).
        stroke(handle, KEY_ESCAPE, "")
        delay(FINAL_SETTLE_MS)
    }

    /**
     * Hold `e`: initial keyDown, [REPEATS] autorepeat keyDowns, keyUp — the
     * exact event stream a held key produces. Layout sanity: if the first
     * keystroke does not land as `e` (non-Latin layout), report a skip.
     */
    private suspend fun driveHold(
        handle: Long,
        tailKey: Pair<Int, String>?,
    ) {
        postKey(handle, KEY_E, "e", down = true)
        if (!awaitLayoutSanity()) {
            // The key state lives in the window server and outlives this
            // process — never leave a scenario with a key held down.
            postKey(handle, KEY_E, "e", down = false)
            return
        }
        repeat(REPEATS) {
            delay(REPEAT_GAP_MS)
            postKey(handle, KEY_E, "e", down = true, autorepeat = true)
        }
        delay(REPEAT_GAP_MS)
        postKey(handle, KEY_E, "e", down = false)
        if (tailKey != null) {
            // The hold may have engaged the real picker; dismiss it first —
            // that is what a user does — so the tail key lands in the field
            // instead of the picker bubble.
            delay(KEY_GAP_MS * 3)
            stroke(handle, KEY_ESCAPE, "")
            delay(KEY_GAP_MS * 3)
            stroke(handle, tailKey.first, tailKey.second)
        }
    }

    /**
     * The accent-pick protocol recorded from AppKit against a reference
     * document-backed client (macOS 26, `NSTextView` and a custom
     * `NSTextInputClient`):
     *
     * ```
     * keyDown 'e'            → insertText:"e" replacementRange:{NSNotFound, 0}
     * (picker engages)       → selectedRange queried while 'e' is still down
     * keyUp 'e'
     * pick                   → insertText:"é" replacementRange:{0, 1}
     * ```
     *
     * The replacement range is UTF-16 and document-absolute: `{caret-1, 1}`.
     */
    private suspend fun drivePickerReplay(handle: Long) {
        postKey(handle, KEY_E, "e", down = true)
        if (!awaitLayoutSanity()) {
            postKey(handle, KEY_E, "e", down = false)
            return
        }
        MacOsTextInputClientProbe.query(handle)
        delay(KEY_GAP_MS)
        postKey(handle, KEY_E, "e", down = false)
        delay(KEY_GAP_MS)
        check(MacOsTextInputClientProbe.insertText(handle, "é", 0L, 1L)) {
            "insertText(é, {0,1}) was not delivered"
        }
    }

    /**
     * Fast-typist roll-over from #611: `o` is still physically down when `d`
     * goes down. The `selectedRange` query in the overlap window is what a
     * live Compose relayout (or IMKit housekeeping) issues at that moment.
     */
    private suspend fun driveRollover(handle: Long) {
        stroke(handle, KEY_X, "x")
        if (!awaitLayoutSanity(expected = "x")) return
        // From here on `o` and `d` are held deliberately; both are released
        // before the scenario ends.
        stroke(handle, KEY_C, "c")
        postKey(handle, KEY_O, "o", down = true)
        delay(KEY_GAP_MS)
        MacOsTextInputClientProbe.query(handle)
        postKey(handle, KEY_D, "d", down = true)
        delay(KEY_GAP_MS)
        postKey(handle, KEY_O, "o", down = false)
        postKey(handle, KEY_D, "d", down = false)
        delay(KEY_GAP_MS)
        stroke(handle, KEY_E, "e")
    }

    private suspend fun awaitLayoutSanity(expected: String = "e"): Boolean {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = text.get()
            if (current == expected) return true
            if (current.isNotEmpty()) {
                println("[pah] skip=layout typed='$current'")
                return false
            }
            delay(AWAIT_POLL_MS)
        }
        println("[pah] skip=no-keystroke-arrived")
        return false
    }

    private suspend fun stroke(
        handle: Long,
        keyCode: Int,
        characters: String,
    ) {
        postKey(handle, keyCode, characters, down = true)
        postKey(handle, keyCode, characters, down = false)
        delay(KEY_GAP_MS)
    }

    private fun postKey(
        handle: Long,
        keyCode: Int,
        characters: String,
        down: Boolean,
        autorepeat: Boolean = false,
    ) {
        check(MacOsKotoeriProbe.postKey(handle, keyCode, characters, down, autorepeat)) {
            "postKey(keyCode=$keyCode, down=$down, autorepeat=$autorepeat) failed"
        }
    }

    private suspend fun awaitUntil(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $description" }
            delay(AWAIT_POLL_MS)
        }
    }
}

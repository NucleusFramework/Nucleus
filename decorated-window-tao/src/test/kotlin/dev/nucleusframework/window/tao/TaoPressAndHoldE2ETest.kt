package dev.nucleusframework.window.tao

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in e2e for #611/#612 (set `NUCLEUS_TAO_SMOKE=1`): macOS press-and-hold
 * must behave exactly like every document-backed AppKit client — Chrome,
 * Notes, TextEdit. The reference behavior was recorded from AppKit driving a
 * real `NSTextView` and a custom document-backed `NSTextInputClient`:
 *
 * - `defaults write -g ApplePressAndHoldEnabled -bool false` (or the
 *   `-ApplePressAndHoldEnabled NO` argument domain) → a held letter repeats
 *   (`eeeeee`), because every autorepeat keyDown reaches `insertText:`. A
 *   client that suppresses those repeats — or force-overrides the user
 *   default — leaves the key dead where the picker cannot engage (#612).
 * - Picker pick → `insertText:"é" replacementRange:{caret-1, 1}` — a UTF-16
 *   document-absolute range. No heuristics: the replacement range carries
 *   everything.
 * - Key roll-over during fast typing must never be misread as a picker
 *   session (#611: `xcode` landing as `xcde`).
 *
 * Each scenario runs in a child JVM ([headful.PressAndHoldProbeMain]) so
 * `ApplePressAndHoldEnabled` can be controlled per scenario through the
 * process argument domain, and posts real CGEvents at the session tap.
 *
 * Not run by default: opens a real window, so it needs a display.
 */
class TaoPressAndHoldE2ETest {
    @Test
    fun heldLetterRepeatsWhenUserDisabledPressAndHold() {
        // #612: the user's ApplePressAndHoldEnabled=false must be honored —
        // Chrome parity is 1 insert + 5 autorepeat inserts.
        runScenario("disabled-repeat", "-ApplePressAndHoldEnabled", "NO") { text ->
            assertTrue(
                text == "eeeeee",
                "held 'e' with press-and-hold disabled must repeat like Chrome/Notes " +
                    "(expected 'eeeeee', got '$text')",
            )
        }
    }

    @Test
    fun heldLetterNeverGoesDeadAndNextKeystrokeIsNotEaten() {
        // #612: with press-and-hold enabled the repeats are consumed while
        // the picker engages ('e') — or type where it cannot ('eeeeee') —
        // but the key must never go dead and the following 'x' must land.
        runScenario("enabled-hold") { text ->
            assertTrue(
                Regex("^e+x$").matches(text),
                "held 'e' then 'x' must match ^e+x$ (Chrome parity), got '$text'",
            )
        }
    }

    @Test
    fun pickerReplacementCommitReplacesTheBaseLetter() {
        // The exact protocol AppKit sends on an accent pick — the
        // replacement range does the work, no state machine required.
        runScenario("picker-replay") { text ->
            assertTrue(
                text == "é",
                "insertText:\"é\" replacementRange:{0, 1} must replace the base letter " +
                    "(expected 'é', got '$text')",
            )
        }
    }

    @Test
    fun fastTypingRollOverIsNotMisreadAsAnAccentPick() {
        // #611: 'o' still down when 'd' goes down + a selectedRange query in
        // the overlap window — 'xcode' must not land as 'xcde'.
        runScenario("rollover") { text ->
            assertTrue(
                text == "xcode",
                "roll-over typing must land intact (expected 'xcode', got '$text')",
            )
        }
    }

    private fun runScenario(
        scenario: String,
        vararg extraArgs: String,
        assertText: (String) -> Unit,
    ) {
        if (!System.getProperty("os.name", "").lowercase().contains("mac")) return
        if (System.getenv("NUCLEUS_TAO_SMOKE") == null) {
            println("SKIPPED: set NUCLEUS_TAO_SMOKE=1 to run the press-and-hold e2e")
            return
        }

        val java = File(File(System.getProperty("java.home"), "bin"), "java")
        assertTrue(java.isFile, "java launcher not found at $java")

        // Scenario first; the -ApplePressAndHoldEnabled pair lands in the
        // process argv, which is what AppKit parses into NSArgumentDomain.
        val pb = ProcessBuilder(java.absolutePath, PROBE_MAIN_CLASS, scenario, *extraArgs)
        // CLASSPATH env instead of -cp: the test classpath can exceed argv limits.
        pb.environment()["CLASSPATH"] = System.getProperty("java.class.path")

        val proc = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outPump =
            thread(name = "pah-probe-stdout") {
                proc.inputStream.bufferedReader().forEachLine { synchronized(stdout) { stdout.appendLine(it) } }
            }
        val errPump =
            thread(name = "pah-probe-stderr") {
                proc.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
            }
        val finished = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        outPump.join(PUMP_JOIN_MS)
        errPump.join(PUMP_JOIN_MS)
        val out = synchronized(stdout) { stdout.toString() }
        val err = synchronized(stderr) { stderr.toString() }

        assertTrue(finished, "probe timed out after ${PROBE_TIMEOUT_SECONDS}s\n${tail(err)}")
        assertTrue(
            proc.exitValue() == 0,
            "probe exited abnormally (${proc.exitValue()})\n${tail(out)}\n${tail(err)}",
        )

        val skip = Regex("""\[pah] skip=(\S+)""").find(out)?.groupValues?.get(1)
        if (skip != null) {
            println("SKIPPED ($scenario): $skip — non-Latin layout or no key delivery\n${tail(out)}")
            return
        }
        val text =
            Regex("""\[pah] text='(.*)'""").find(out)?.groupValues?.get(1)
                ?: error("probe never reported its text\n${tail(out)}\n${tail(err)}")
        assertText(text)
    }

    private fun tail(s: CharSequence): String = s.takeLast(MAX_REPORT_CHARS).toString()

    private companion object {
        const val PROBE_MAIN_CLASS = "dev.nucleusframework.window.tao.headful.PressAndHoldProbeMain"
        const val PROBE_TIMEOUT_SECONDS = 120L
        const val PUMP_JOIN_MS = 5_000L
        const val MAX_REPORT_CHARS = 4_000
    }
}

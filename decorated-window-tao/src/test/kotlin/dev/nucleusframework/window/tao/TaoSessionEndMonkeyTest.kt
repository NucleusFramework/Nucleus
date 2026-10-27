package dev.nucleusframework.window.tao

import kotlin.random.Random
import kotlin.test.Test

/**
 * Seeded monkey over the Windows session-end state machine (#751): random apps
 * (windows that veto, consent, close through recomposition, or query again from
 * their close request), random interleavings of queries (every flag
 * combination), cancels, settles, await timeouts, plain window closes and a
 * final session end. The native side is modelled as far as Kotlin sees it — the
 * shutdown block reason is set by HOLD, dropped by AGREE / REFUSE, a session end
 * or [TaoApplication.releaseSessionEndHold].
 *
 * A failure names its seed; rerun one with `-Dnucleus.tao.sessionEndMonkeySeed=<n>`,
 * sweep more with `-Dnucleus.tao.sessionEndMonkeySeeds=<count>`.
 */
class TaoSessionEndMonkeyTest {
    private enum class Kind { VETO, CONSENT, STATE, NESTED_VETO, NESTED_CONSENT }

    @Test
    fun `random session-end interleavings keep the quit state consistent`() {
        val only = System.getProperty("nucleus.tao.sessionEndMonkeySeed")?.toLong()
        val count = System.getProperty("nucleus.tao.sessionEndMonkeySeeds")?.toLong() ?: SEEDS
        val seeds = only?.let { listOf(it) } ?: (0L until count)
        for (seed in seeds) runSeed(seed)
    }

    private class Run(
        val seed: Long,
    ) {
        val trace = mutableListOf<String>()

        fun fail(message: String): Nothing = kotlin.test.fail("seed $seed: $message\n  " + trace.joinToString("\n  "))
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private fun runSeed(seed: Long) {
        val rnd = Random(seed)
        val run = Run(seed)
        val continuations = mutableListOf<() -> Unit>()
        val recompositions = mutableListOf<() -> Unit>()
        val awaitTimeouts = mutableListOf<Runnable>()
        var exits = 0
        var teardowns = 0
        var processExits = 0
        var holdActive = false
        val innerAnswers = mutableListOf<Int>()

        // One recomposition pass, then the quits' continuations — the order the
        // Compose loop runs them in.
        fun settle() {
            recompositions.toList().also { recompositions.clear() }.forEach { it() }
            continuations.toList().also { continuations.clear() }.forEach { it() }
        }

        TaoApplication.resetQuit()
        TaoApplication.quitExit = { exits++ }
        TaoApplication.afterQuitRequests = { continuations += it }
        TaoApplication.releaseSessionEndHold = { holdActive = false }
        TaoApplication.scheduleSessionAwaitTimeout = { awaitTimeouts += it }
        TaoApplication.sessionEndTeardown = { teardowns++ }
        TaoApplication.sessionEndExit = { processExits++ }
        // The drain of a Restart Manager close: what the loop would run meanwhile.
        TaoApplication.settleQuitNow = { settled ->
            var passes = 0
            while (!settled() && passes++ < SETTLE_PASSES) settle()
        }

        val kinds = List(1 + rnd.nextInt(MAX_WINDOWS)) { Kind.entries[rnd.nextInt(Kind.entries.size)] }
        val windows = mutableListOf<TaoWindow>()
        kinds.forEachIndexed { i, kind ->
            val w = TaoWindow(i + 1L)
            w.onCloseRequested {
                run.trace += "    close request w${w.handle}($kind)"
                when (kind) {
                    Kind.VETO -> Unit
                    Kind.CONSENT -> TaoApplication.consentToQuit()
                    Kind.STATE ->
                        recompositions += {
                            if (!w.isClosing) {
                                w.isClosing = true
                                TaoApplication.remove(w.handle)
                            }
                        }
                    Kind.NESTED_VETO, Kind.NESTED_CONSENT -> {
                        // A close request that pumps (a native dialog): another
                        // window's query arrives inside it.
                        innerAnswers += TaoApplication.queryEndSession(windows, randomFlags(rnd))
                        if (kind == Kind.NESTED_CONSENT) TaoApplication.consentToQuit()
                    }
                }
            }
            windows += w
        }
        val vetoes = kinds.any { it == Kind.VETO || it == Kind.NESTED_VETO }
        run.trace += "windows=$kinds"

        fun check() {
            if (exits > 1) run.fail("quit exit ran $exits times")
            if (vetoes && exits > 0) run.fail("exited although a window keeps refusing")
            if (innerAnswers.any { it != TaoApplication.END_SESSION_HOLD }) {
                run.fail("a re-entrant query answered ${innerAnswers.toList()}, not HOLD")
            }
            if (teardowns > 1 || processExits > 1) run.fail("teardown=$teardowns exit=$processExits")
        }

        try {
            var ended = false
            repeat(OPS) {
                // exitApplication tears the app down: nothing reaches it any more.
                if (ended || exits > 0) return@repeat
                when (rnd.nextInt(100)) {
                    in 0..29 -> {
                        val flags = randomFlags(rnd)
                        val answer = TaoApplication.queryEndSession(windows, flags)
                        run.trace += "query flags=$flags -> $answer quitting=${TaoApplication.isQuitting}"
                        if (answer == TaoApplication.END_SESSION_AGREE && vetoes) {
                            run.fail("agreed although a window keeps refusing")
                        }
                        holdActive = answer == TaoApplication.END_SESSION_HOLD
                    }
                    in 30..44 -> {
                        run.trace += "cancel (WM_ENDSESSION FALSE)"
                        holdActive = false
                        TaoApplication.endSession(ending = false)
                    }
                    in 45..74 -> {
                        run.trace += "settle"
                        settle()
                    }
                    in 75..84 ->
                        if (awaitTimeouts.isNotEmpty()) {
                            run.trace += "await timeout"
                            awaitTimeouts.removeAt(rnd.nextInt(awaitTimeouts.size)).run()
                        }
                    in 85..94 -> {
                        // The user closes a window that closes through recomposition.
                        val w =
                            windows
                                .filterIndexed { i, window -> kinds[i] == Kind.STATE && !window.isClosing }
                                .randomOrNull(rnd)
                        if (w != null) {
                            run.trace += "user closes w${w.handle}"
                            w.isClosing = true
                            TaoApplication.remove(w.handle)
                        }
                    }
                    in 95..96 -> {
                        val nested = rnd.nextBoolean()
                        run.trace += "session ends (WM_ENDSESSION TRUE) nested=$nested"
                        holdActive = false
                        TaoApplication.endSession(ending = true, nested = nested)
                        TaoApplication.endSession(ending = true)
                        val expected = if (nested) 0 else 1
                        if (teardowns != expected || processExits != 1) {
                            run.fail("teardown=$teardowns exit=$processExits")
                        }
                        ended = true
                    }
                    else -> Unit
                }
                check()
            }
            if (ended || exits > 0) return

            // Quiescence: Windows called the session end off, everything pending ran.
            run.trace += "-- quiesce"
            holdActive = false
            TaoApplication.endSession(ending = false)
            repeat(QUIESCE_ROUNDS) {
                settle()
                awaitTimeouts.toList().also { awaitTimeouts.clear() }.forEach { it.run() }
            }
            check()
            if (exits == 0) {
                if (TaoApplication.isQuitting) run.fail("isQuitting stuck after the session end was called off")
                if (holdActive) run.fail("shutdown block reason never released")
            }
            if (vetoes) {
                // A fresh session end still asks the windows and is refused.
                val before = run.trace.count { it.startsWith("    close request") }
                val answer = TaoApplication.queryEndSession(windows, 0)
                run.trace += "fresh query -> $answer"
                if (run.trace.count { it.startsWith("    close request") } == before) {
                    run.fail("a fresh query asked nobody")
                }
                if (answer == TaoApplication.END_SESSION_AGREE) run.fail("a fresh query agreed")
                holdActive = answer == TaoApplication.END_SESSION_HOLD
                repeat(QUIESCE_ROUNDS) { settle() }
                if (TaoApplication.isQuitting) run.fail("isQuitting stuck after the fresh query")
                if (holdActive) run.fail("the fresh query's hold was never released")
                check()
            }
        } catch (e: AssertionError) {
            throw e
        } catch (t: Throwable) {
            run.fail("threw $t")
        } finally {
            TaoApplication.resetQuit()
        }
    }

    private fun randomFlags(rnd: Random): Int {
        var flags = 0
        if (rnd.nextBoolean()) flags = flags or TaoApplication.END_SESSION_FLAG_CLOSE_APP
        if (rnd.nextInt(4) == 0) flags = flags or TaoApplication.END_SESSION_FLAG_REFUSED
        return flags
    }

    private companion object {
        const val SEEDS = 20_000L
        const val OPS = 60
        const val MAX_WINDOWS = 4
        const val SETTLE_PASSES = 3
        const val QUIESCE_ROUNDS = 4
    }
}

package dev.nucleusframework.lab.probes.input.keyboard

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

/** A key event reduced to plain data. */
@Immutable
data class KeyRecord(
    val down: Boolean,
    val key: String,
    val nativeCode: Long,
    val codePoint: Int,
    val modifiers: String,
)

/** What the text field reports after each edit: the IME's view of the text. */
@Immutable
data class TextSnapshot(
    val text: String,
    val selection: IntRange,
    /** Marked text as `start until end`; `null` when nothing is being composed. */
    val composition: IntRange?,
)

@Immutable
data class KeyboardState(
    val downs: Int = 0,
    val ups: Int = 0,
    val repeats: Int = 0,
    /** Keys down without their KeyUp yet (by native code): stuck keys show up here. */
    val held: Map<Long, String> = emptyMap(),
    val keyLines: List<String> = emptyList(),
    val text: TextSnapshot = TextSnapshot("", 0..0, null),
    val compositionUpdates: Int = 0,
    val commits: List<String> = emptyList(),
)

sealed interface KeyboardIntent {
    data object Reset : KeyboardIntent
}

sealed interface KeyboardEvent {
    data class Key(
        val record: KeyRecord,
    ) : KeyboardEvent

    data class Text(
        val snapshot: TextSnapshot,
    ) : KeyboardEvent

    data object Cleared : KeyboardEvent
}

object KeyboardReducer : Reducer<KeyboardState, KeyboardEvent> {
    private const val LINES = 40
    private const val COMMITS = 20

    override fun reduce(
        state: KeyboardState,
        event: KeyboardEvent,
    ): KeyboardState =
        when (event) {
            is KeyboardEvent.Key -> key(state, event.record)
            is KeyboardEvent.Text -> text(state, event.snapshot)
            KeyboardEvent.Cleared -> KeyboardState(text = state.text)
        }

    private fun key(
        state: KeyboardState,
        r: KeyRecord,
    ): KeyboardState {
        val char =
            if (r.codePoint >
                0
            ) {
                "'${String(Character.toChars(r.codePoint))}' U+%04X".format(r.codePoint)
            } else {
                "—"
            }
        return if (r.down) {
            val repeat = r.nativeCode in state.held
            state.copy(
                downs = state.downs + 1,
                repeats = state.repeats + if (repeat) 1 else 0,
                held = state.held + (r.nativeCode to r.key),
                keyLines =
                    state.keyLines.pushFront(
                        "${if (repeat) "Repeat" else "Down  "}  ${r.key.padEnd(
                            16,
                        )} code=${r.nativeCode} $char mods=${r.modifiers}",
                        LINES,
                    ),
            )
        } else {
            val unmatched = r.nativeCode !in state.held
            state.copy(
                ups = state.ups + 1,
                held = state.held - r.nativeCode,
                keyLines =
                    state.keyLines.pushFront(
                        "Up${if (unmatched) " ⚠" else "  "}    ${r.key.padEnd(
                            16,
                        )} code=${r.nativeCode} mods=${r.modifiers}",
                        LINES,
                    ),
            )
        }
    }

    private fun text(
        state: KeyboardState,
        s: TextSnapshot,
    ): KeyboardState {
        val previous = state.text
        val composing = s.composition != null
        // Marked text going away: the IME committed it.
        val range = previous.composition
        val commits =
            if (range != null && !composing) {
                val start = range.first.coerceAtMost(s.text.length)
                // The marked range is replaced by the committed text: what is left of the
                // length change, once the marked text is taken out, is the commit.
                val length = s.text.length - (previous.text.length - range.count())
                val inserted = s.text.substring(start, (start + length).coerceIn(start, s.text.length))
                state.commits.pushFront("committed '$inserted'", COMMITS)
            } else {
                state.commits
            }
        return state.copy(
            text = s,
            compositionUpdates = state.compositionUpdates + if (composing && s != previous) 1 else 0,
            commits = commits,
        )
    }
}

/** `é U+00E9 · ́ U+0301`: tells a precomposed character from a decomposed one. */
fun codePoints(text: String): String =
    text.codePoints().toArray().joinToString(" · ") { cp -> "${String(Character.toChars(cp))} U+%04X".format(cp) }

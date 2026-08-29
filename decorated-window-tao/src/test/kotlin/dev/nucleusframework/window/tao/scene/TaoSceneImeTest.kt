@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Reproduces issue #595 — Tao/macOS: IME marked text (preedit) never reaches
 * Compose.
 *
 * The tests replay the native→JVM IME protocol (not raw keys: those are
 * filtered in tao's macOS `keyDown`/`keyUp`, which this offscreen harness
 * does not run): type `nihongo` (preedit updates), convert, commit. Cancel
 * is `unmarkText` → empty preedit.
 */
class TaoSceneImeTest {
    /** Focused multi-line BasicTextField backed by [value]. */
    private class FieldHolder {
        var value: String by mutableStateOf("")
    }

    private fun TaoSceneTestScope.focusedField(): FieldHolder {
        val holder = FieldHolder()
        setContent {
            Box(Modifier.fillMaxSize()) {
                BasicTextField(
                    value = holder.value,
                    onValueChange = { holder.value = it },
                    modifier = Modifier.size(200.dp, 60.dp),
                )
            }
        }
        click(100f, 30f) // focus the field, starting the text-input session
        frameUntilIdle()
        assertNotNull(inputMethodRequest, "focusing the field must start a text-input session")
        return holder
    }

    @Test
    fun `IME preedit is shown in the field while composing`() =
        runTaoSceneTest {
            val field = focusedField()
            // Kotoeri while typing "nihongo": one setMarkedText: per keystroke.
            imePreedit("に")
            imePreedit("にほ")
            imePreedit("にほん")
            imePreedit("にほんご")
            assertEquals("にほんご", field.value, "the marked text must be visible while composing")
        }

    @Test
    fun `IME preedit is an active composition, not committed text`() =
        runTaoSceneTest {
            focusedField()
            imePreedit("にほんご")
            val request = assertNotNull(inputMethodRequest)
            assertNotNull(
                request.value().composition,
                "the preedit must be marked as a composing region",
            )
        }

    @Test
    fun `IME commit replaces the preedit without inserting a newline`() =
        runTaoSceneTest {
            val field = focusedField()
            imePreedit("にほんご")
            imePreedit("日本語")
            imeCommit("日本語")
            assertFalse(field.value.contains("\n"), "a composition commit must not insert a newline")
            assertEquals("日本語", field.value)
        }

    @Test
    fun `shortening the preedit does not delete committed text`() =
        runTaoSceneTest {
            val field = focusedField()
            typeText("abc")
            imePreedit("に")
            imePreedit("にほ")
            // IME consumed Backspace and re-marked a shorter string.
            imePreedit("に")
            imeCommit("に")
            assertEquals("abcに", field.value, "preedit edits must not touch already-committed text")
        }

    @Test
    fun `committed text replaces the preedit`() =
        runTaoSceneTest {
            val field = focusedField()
            imePreedit("にほんご")
            imeCommit("日本語")
            assertEquals("日本語", field.value)
        }

    @Test
    fun `cancelled composition removes the preedit`() =
        runTaoSceneTest {
            val field = focusedField()
            typeText("x")
            imePreedit("に")
            imePreedit("にほ")
            imePreedit("") // Escape — unmarkText
            assertEquals("x", field.value)
        }

    @Test
    fun `typing after a commit works normally`() =
        runTaoSceneTest {
            val field = focusedField()
            imePreedit("にほんご")
            imeCommit("日本語")
            typeText("ok")
            assertEquals("日本語ok", field.value)
        }

    @Test
    fun `replacement commit replaces the range the picker names`() =
        runTaoSceneTest {
            val field = focusedField()
            typeText("Xe")
            // The accent pick as AppKit sends it to a document-backed client
            // (#611/#612): insertText:"é" replacementRange:{caret-1, 1}.
            imeReplaceCommit("é", start = 1L, length = 1L)
            assertEquals("Xé", field.value)
        }

    @Test
    fun `replacement commit leaves surrounding text intact and typing continues`() =
        runTaoSceneTest {
            val field = focusedField()
            typeText("abe")
            imeReplaceCommit("è", start = 2L, length = 1L)
            typeText("cd")
            assertEquals("abècd", field.value, "the caret must land after the replacement")
        }

    @Test
    fun `replacement commit with an out-of-bounds range is clamped`() =
        runTaoSceneTest {
            val field = focusedField()
            typeText("e")
            // A stale range (field changed under the pick) must not throw.
            imeReplaceCommit("é", start = 5L, length = 3L)
            assertEquals("eé", field.value)
        }

    @Test
    fun `empty IME commit while composing does not wipe the preedit`() =
        runTaoSceneTest {
            val field = focusedField()
            imePreedit("にほんご")
            // insertText: of a U+F7xx corporate/function-key char, filtered to
            // "". Must not run commitText("") — that deletes the composition
            // and desyncs IMKit (reporter follow-up on #595).
            imeCommit("")
            assertEquals("にほんご", field.value)
            val request = assertNotNull(inputMethodRequest)
            assertNotNull(
                request.value().composition,
                "an empty filtered commit must leave the composing region in place",
            )
            imeCommit("日本語")
            assertEquals("日本語", field.value)
        }
}

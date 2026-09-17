package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * #595 — a real Kotoeri session on a live Tao window. Types romaji `nihongo`
 * through AppKit (not the scene harness) and checks the two gaps from the
 * issue: preedit reaches Compose (`TextFieldValue.composition`), and the
 * committing Enter does not insert a newline.
 *
 * Skips when Japanese IME is not installed (rare on a stock macOS). The probe
 * enables Kotoeri for the duration of the case if it is installed but hidden
 * from the input-source menu, then restores the previous source.
 */
internal object ImeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            kotoeriNihongoCommitsWithoutNewline(),
            textInputClientAnswersAndEmptyCorporateCommit(),
        )

    private fun kotoeriNihongoCommitsWithoutNewline(): TaoWindowTestCase {
        val value = AtomicReference("")
        val composition = AtomicReference<TextRange?>(null)
        val sawComposition = AtomicBoolean(false)
        val focused = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "#595 Kotoeri romaji session produces 日本語 without a newline",
            timeoutMillis = CASE_TIMEOUT_MILLIS,
            skip = { macOsOnly() ?: kotoeriInstalled() },
            paintDefaultBackground = false,
            size = DpSize(480.dp, 360.dp),
            content = {
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
                        value.set(it.text)
                        composition.set(it.composition)
                        if (it.composition != null) sawComposition.set(true)
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(requester),
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("text field focused") { focused.get() }
            settle(FOCUS_SETTLE_MILLIS)
            check(MacOsKotoeriProbe.select(window.handle)) {
                "failed to select Kotoeri Hiragana for handle=${window.handle}"
            }
            try {
                settle(IME_SWITCH_SETTLE_MILLIS)
                println(
                    "macOS Kotoeri e2e selected source=${MacOsKotoeriProbe.currentInputSource()}",
                )
                kotoeriStroke(MacOsKotoeriProbe.KEY_N, "n")
                kotoeriStroke(MacOsKotoeriProbe.KEY_I, "i")
                kotoeriStroke(MacOsKotoeriProbe.KEY_H, "h")
                kotoeriStroke(MacOsKotoeriProbe.KEY_O, "o")
                kotoeriStroke(MacOsKotoeriProbe.KEY_N, "n")
                kotoeriStroke(MacOsKotoeriProbe.KEY_G, "g")
                kotoeriStroke(MacOsKotoeriProbe.KEY_O, "o")
                settle(POST_TYPE_SETTLE_MILLIS)
                println(
                    "macOS Kotoeri e2e after romaji: value=${value.get().debug()} " +
                        "composition=${composition.get()} " +
                        "source=${MacOsKotoeriProbe.currentInputSource()}",
                )
                awaitUntil("Kotoeri produced Japanese text") { value.get().hasJapanese() }
                check(sawComposition.get()) {
                    "issue #595: preedit never reached Compose " +
                        "(TextFieldValue.composition stayed null). value=${value.get().debug()}"
                }
                // Issue repro: Space converts, Enter commits. Enter must not
                // leak as a raw KeyDown (newline before 日本語). Live conversion
                // may already show 日本語 as the composing region — still press
                // Enter while composition is active.
                if (value.get().hasKana()) {
                    kotoeriStroke(MacOsKotoeriProbe.KEY_SPACE, " ")
                    settle(POST_TYPE_SETTLE_MILLIS)
                }
                if (composition.get() != null) {
                    kotoeriStroke(MacOsKotoeriProbe.KEY_RETURN, "\r")
                    awaitUntil("composition finished after Enter") { composition.get() == null }
                }
                val committed = value.get()
                check('\n' !in committed && '\r' !in committed) {
                    "issue #595: committing Enter leaked a newline: ${committed.debug()}"
                }
                check(committed.none { it in 'a'..'z' || it in 'A'..'Z' }) {
                    "romaji leaked past Kotoeri: ${committed.debug()}"
                }
                check(committed.hasJapanese()) {
                    "expected CJK in the field, got ${committed.debug()}"
                }
                println(
                    "macOS Kotoeri e2e: committed=${committed.debug()} " +
                        "sawComposition=${sawComposition.get()}",
                )
            } finally {
                MacOsKotoeriProbe.restore()
            }
        }
    }

    /**
     * Reporter follow-up on #595: IMKit desyncs when NSTextInputClient lies
     * (`markedRange` off-by-one, `selectedRange` = NSNotFound, substring nil,
     * `characterIndexForPoint` = 0) and when a U+F7xx `insertText:` is
     * forwarded as `ImeCommit("")`, wiping the preedit.
     *
     * Drives TaoView's NSTextInputClient directly (no Kotoeri required).
     */
    private fun textInputClientAnswersAndEmptyCorporateCommit(): TaoWindowTestCase {
        val value = AtomicReference("")
        val composition = AtomicReference<TextRange?>(null)
        val focused = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "#595 NSTextInputClient answers and empty corporate commit",
            timeoutMillis = CASE_TIMEOUT_MILLIS,
            skip = { macOsOnly() },
            paintDefaultBackground = false,
            size = DpSize(480.dp, 360.dp),
            content = { focusedImeField(value, composition, focused) },
        ) {
            driveTextInputClientCase(value, composition, focused)
        }
    }

    @Composable
    private fun focusedImeField(
        value: AtomicReference<String>,
        composition: AtomicReference<TextRange?>,
        focused: AtomicBoolean,
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
                value.set(it.text)
                composition.set(it.composition)
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(requester),
        )
    }

    private suspend fun TaoWindowTestScope.driveTextInputClientCase(
        value: AtomicReference<String>,
        composition: AtomicReference<TextRange?>,
        focused: AtomicBoolean,
    ) {
        val marked = "hello"
        awaitUntil("window mapped") { bounds() != null }
        awaitUntil("text field focused") { focused.get() }
        settle(FOCUS_SETTLE_MILLIS)
        val handle = window.handle
        injectMarked(handle, marked)
        awaitUntil("preedit reached Compose") { value.get() == marked }
        assertClientSnapshot(handle, marked)
        check(composition.get() != null) {
            "setMarkedText must leave an active composing region"
        }
        injectMarked(handle, marked + "\uF700")
        awaitUntil("corporate chars stripped from preedit") {
            value.get() == marked && '\uF700' !in value.get()
        }
        check(composition.get() != null) {
            "corporate-only extra chars must not cancel the composing region"
        }
        check(MacOsTextInputClientProbe.insertText(handle, "\uF700")) {
            "insertText(U+F700) was not delivered"
        }
        settle(POST_TYPE_SETTLE_MILLIS)
        check(value.get() == marked && composition.get() != null) {
            "empty filtered commit must not wipe the preedit, got ${value.get().debug()}"
        }
        check('\uF700' !in value.get()) {
            "U+F7xx tofu must not enter the field: ${value.get().debug()}"
        }
        check(MacOsTextInputClientProbe.insertText(handle, marked)) {
            "insertText(\"$marked\") was not delivered"
        }
        awaitUntil("composition committed") {
            composition.get() == null && value.get() == marked
        }
        val afterCommit = MacOsTextInputClientProbe.query(handle)
        check(
            afterCommit.markedLocation == MacOsTextInputClientProbe.NS_NOT_FOUND ||
                afterCommit.markedLength == 0L,
        ) {
            "successful insertText must clear marked text, got marked=" +
                "(${afterCommit.markedLocation}, ${afterCommit.markedLength})"
        }
        injectMarked(handle, "xyz")
        awaitUntil("second preedit") {
            value.get().endsWith("xyz") && composition.get() != null
        }
        injectMarked(handle, "")
        awaitUntil("empty setMarkedText unmarks") {
            composition.get() == null && value.get() == marked
        }
    }

    private fun injectMarked(
        handle: Long,
        text: String,
    ) {
        check(
            MacOsTextInputClientProbe.setMarkedText(
                handle,
                text,
                selectedLocation = text.length,
                selectedLength = 0,
            ),
        ) { "setMarkedText(\"$text\") was not delivered" }
    }

    private fun assertClientSnapshot(
        handle: Long,
        marked: String,
    ) {
        val snap = MacOsTextInputClientProbe.query(handle)
        check(snap.markedLocation == 0L && snap.markedLength == marked.length.toLong()) {
            "markedRange must be (0, ${marked.length}), got " +
                "(${snap.markedLocation}, ${snap.markedLength})"
        }
        check(
            snap.selectedLocation == marked.length.toLong() &&
                snap.selectedLength == 0L,
        ) {
            "selectedRange must be the caret at marked end " +
                "(${marked.length}, 0), got (${snap.selectedLocation}, ${snap.selectedLength})"
        }
        check(snap.substring == marked) {
            "attributedSubstringForProposedRange must return the marked text, " +
                "got ${snap.substring.debug()}"
        }
        check(snap.characterIndex == marked.length.toLong()) {
            "characterIndexForPoint must be the marked length (${marked.length}), " +
                "got ${snap.characterIndex}"
        }
    }

    private fun macOsOnly(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("mac") || os.contains("darwin")) null else "macOS only"
    }

    private fun kotoeriInstalled(): String? =
        if (MacOsKotoeriProbe.isAvailable()) {
            null
        } else {
            "Japanese Kotoeri IME is not installed"
        }

    private fun String.debug(): String = "\"$this\" (len=$length, cps=${map { it.code }})"

    private fun String.hasJapanese(): Boolean = any { it.isJapanese() }

    private fun String.hasKana(): Boolean = any { it.isKana() }

    private fun Char.isKana(): Boolean = this in '\u3040'..'\u30FF'

    private fun Char.isJapanese(): Boolean = isKana() || this in '\u4E00'..'\u9FFF' || this in '\uFF66'..'\uFF9D'

    private const val CASE_TIMEOUT_MILLIS = 45_000L
    private const val FOCUS_SETTLE_MILLIS = 200L
    private const val IME_SWITCH_SETTLE_MILLIS = 400L
    private const val POST_TYPE_SETTLE_MILLIS = 300L
}

private suspend fun TaoWindowTestScope.kotoeriStroke(
    keyCode: Int,
    characters: String,
) {
    check(MacOsKotoeriProbe.postKey(window.handle, keyCode, characters, down = true)) {
        "keyDown keyCode=$keyCode characters='$characters' was not delivered"
    }
    check(MacOsKotoeriProbe.postKey(window.handle, keyCode, characters, down = false)) {
        "keyUp keyCode=$keyCode characters='$characters' was not delivered"
    }
    delay(KEY_GAP_MILLIS)
}

private const val KEY_GAP_MILLIS = 80L

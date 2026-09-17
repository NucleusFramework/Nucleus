package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Routes native IME callbacks into the active Compose text-input session (#595).
 *
 * Three native events, three methods:
 * - [preedit] — macOS `setMarkedText:` / `unmarkText` (empty text cancels)
 * - [commit] — `insertText:` while a composition is active, via
 *   `TextEditingScope.commitText` (replaces the composing region in one edit)
 * - [replaceCommit] — `insertText:` with a valid `replacementRange` outside
 *   a composition (the press-and-hold accent picker replacing the base
 *   letter); the range is replaced select-then-insert, Chromium's
 *   `ImeCommitText` semantics (#611/#612)
 *
 * Raw key delivery while the IME owns the keyboard is decided natively
 * (`keyDown`/`keyUp` in tao's macOS view). This object does not filter keys.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class TaoImeSession(
    private val typedFallback: (String, Int) -> Unit = { _, _ -> },
) {
    @Volatile
    private var activeRequest: PlatformTextInputMethodRequest? = null

    @Volatile
    private var isComposing: Boolean = false

    /** Tracks the session lifecycle; a null [request] ends any composition. */
    fun onInputSession(request: PlatformTextInputMethodRequest?) {
        activeRequest = request
        if (request == null) {
            isComposing = false
        }
    }

    /**
     * Applies a composition update to the focused field. An empty [text]
     * ends the composition (`unmarkText` / cancellation) and removes the
     * composing text.
     *
     * The caret is placed after the composed text (`newCursorPosition = 1`),
     * matching the AWT backend; the IME's own selection within the marked
     * text is not representable through `TextEditingScope` and is ignored.
     */
    fun preedit(text: String) {
        val request = activeRequest ?: return
        if (text.isEmpty()) {
            if (!isComposing) return
            isComposing = false
            request.editText {
                setComposingText("", 1)
                finishComposingText()
            }
        } else {
            isComposing = true
            request.editText {
                setComposingText(text, 1)
            }
        }
    }

    /**
     * Commits [text] in place of the composing region. Called for
     * `insertText:` while marked text is active — not for ordinary typing,
     * which still travels `ReceivedImeText` → KEY_TYPED.
     *
     * An empty [text] is ignored: native filters Apple corporate/function-key
     * characters to `""`, and `commitText("")` would delete the composing
     * region.
     */
    fun commit(text: String) {
        val request = activeRequest ?: return
        if (text.isEmpty()) return
        isComposing = false
        request.editText {
            commitText(text, 1)
        }
    }

    /**
     * Commits [rawText] over the half-open committed-text range starting at
     * [replacementStart] and spanning [replacementLength] \u2014 UTF-16 offsets
     * in the same document-absolute space the host reports through
     * `nativeSetImeDocument`. This is `insertText:` with a valid
     * `replacementRange` (the press-and-hold accent picker replacing its
     * base letter, #611/#612). Select-then-insert, exactly like Blink's
     * `ReplaceTextAndKeepSelection` / WebKit's `_selectNSRange:` + insert.
     * Falls back to a typed-key sequence when no text-input session is up.
     */
    fun replaceCommit(
        rawText: String,
        replacementStart: Long,
        replacementLength: Long,
    ) {
        // Apple corporate (function-key) characters must never reach the
        // field: they render as tofu (#595).
        val text = rawText.filterNot { it in '\uF700'..'\uF8FF' }
        if (text.isEmpty()) return
        val request = activeRequest
        if (request == null) {
            // No field to address the range against; approximate it with
            // backspaces, bounded so a bogus range cannot eat the document.
            typedFallback(text, replacementLength.coerceIn(0L, MAX_FALLBACK_DELETE).toInt())
            return
        }
        val length = request.value().text.length
        val start = replacementStart.coerceIn(0L, length.toLong()).toInt()
        val end =
            (replacementStart + replacementLength)
                .coerceIn(start.toLong(), length.toLong())
                .toInt()
        // `commitText` replaces the composing region when one exists, which
        // would silently ignore the selection set here \u2014 end the
        // composition first, as [commit] does.
        isComposing = false
        request.editText {
            finishComposingText()
            setSelection(start, end)
            commitText(text, 1)
        }
    }

    private companion object {
        const val MAX_FALLBACK_DELETE = 8L
    }
}

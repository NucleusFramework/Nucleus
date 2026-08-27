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
 * - [replaceCommit] — PressAndHold accent pick (delete the already-committed
 *   base letter, then commit the accented character)
 *
 * Raw key delivery while the IME owns the keyboard is decided natively
 * (`keyDown`/`keyUp` in tao's macOS view). This object does not filter keys.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class TaoImeSession(
    private val typedFallback: (String) -> Unit = {},
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
     * PressAndHold picked an accent. The base letter is already in the
     * field; replace it via `TextEditingScope` (same as
     * `DesktopTextInputService2` / JDK-8074882). Falls back to a typed-key
     * sequence when no text-input session is up yet.
     */
    fun replaceCommit(rawText: String) {
        // Apple corporate (function-key) characters must never reach the field:
        // they render as tofu, and `deleteSurroundingTextInCodePoints` would
        // still remove a real character before "inserting" them (#595).
        val text = rawText.filterNot { it in '\uF700'..'\uF8FF' }
        if (text.isEmpty()) return
        val request = activeRequest
        if (request != null) {
            request.editText {
                deleteSurroundingTextInCodePoints(1, 0)
                commitText(text, 1)
            }
            return
        }
        typedFallback(text)
    }
}

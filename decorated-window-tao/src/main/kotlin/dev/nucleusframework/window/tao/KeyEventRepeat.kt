package dev.nucleusframework.window.tao

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.tao.event.TaoAwtKeyEvent

/**
 * Whether this key-down is an auto-repeat of a key the user is holding, rather
 * than a new press. The OS keeps sending key-downs while a key is held; this
 * tells them apart — e.g. so that holding Ctrl+W counts as one press, not two.
 *
 * Only a [KeyEventType.KeyDown] is ever a repeat: a key-up, and the typed-text
 * event that carries the character, always report `false` — skip the repeated
 * key-down and handle text through the text field as usual. `false` for any
 * event Nucleus did not produce.
 */
@ExperimentalNucleusApi
public val KeyEvent.isRepeat: Boolean
    get() = (awtEventOrNull as? TaoAwtKeyEvent)?.isRepeat == true

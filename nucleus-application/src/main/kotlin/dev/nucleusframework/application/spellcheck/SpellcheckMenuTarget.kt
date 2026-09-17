@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application.spellcheck

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import dev.nucleusframework.spellcheck.SpellcheckWord

internal fun misspellingAt(
    ranges: List<SpellcheckWord>,
    anchor: TextRange,
): SpellcheckWord? {
    val start = minOf(anchor.start, anchor.end)
    val end = maxOf(anchor.start, anchor.end)
    if (start != end) {
        ranges.firstOrNull { it.start == start && it.end == end }?.let { return it }
        return ranges.firstOrNull { start >= it.start && end <= it.end }
    }
    return ranges.firstOrNull { start in it.start until it.end }
}

internal fun spellcheckAnchor(
    clickOffset: Int?,
    selection: TextRange?,
): TextRange? = if (clickOffset != null) TextRange(clickOffset) else selection

internal fun textOffsetAtRoot(
    layout: TextLayoutResult,
    textOriginInRoot: Offset,
    clickInRoot: Offset,
): Int = layout.getOffsetForPosition(clickInRoot - textOriginInRoot)

internal fun clickOffsetInField(
    request: PlatformTextInputMethodRequest,
    clickInRoot: Offset,
): Int? {
    val layout = request.textLayoutResult() ?: return null
    val origin =
        resolveSpellcheckTextOriginInRoot(
            request.unclippedTextOffsetInRoot(),
            request.textClippingRectInRoot(),
        ) ?: return null
    return textOffsetAtRoot(layout, origin, clickInRoot)
}

internal fun Modifier.detectSecondaryClickInRoot(
    originInRoot: () -> Offset,
    onClick: (Offset) -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val local =
                    if (event.buttons.isSecondaryPressed) {
                        event.changes.firstOrNull()?.position
                    } else {
                        null
                    }
                if (local != null) {
                    onClick(originInRoot() + local)
                }
            }
        }
    }

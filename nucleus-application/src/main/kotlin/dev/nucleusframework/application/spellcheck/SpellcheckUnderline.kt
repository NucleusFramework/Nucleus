package dev.nucleusframework.application.spellcheck

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import dev.nucleusframework.spellcheck.SpellcheckWord

private val SpellcheckRed: Color = Color(red = 1f, green = 0f, blue = 0f)
private const val WAVE_LENGTH = 6f
private const val WAVE_AMPLITUDE = 1.5f
private const val STROKE_WIDTH = 1f

/**
 * Draws a red wavy underline under each [ranges] span, in the same role as
 * Electron/Chrome's spelling squiggle (not a solid text-color underline).
 */
public fun drawMisspellingSquiggles(
    drawScope: DrawScope,
    layout: TextLayoutResult,
    ranges: List<SpellcheckWord>,
    color: Color = SpellcheckRed,
) {
    if (ranges.isEmpty()) return
    val textLength = layout.layoutInput.text.length
    for (range in ranges) {
        val start = range.start.coerceIn(0, textLength)
        val end = range.end.coerceIn(start, textLength)
        if (start == end) continue
        for (box in boundingBoxesForRange(layout, start, end)) {
            drawScope.drawWavyUnderline(box, color)
        }
    }
}

/**
 * Root-space origin of `TextLayoutResult` (0, 0).
 *
 * Legacy IME reports [unclippedTextOffsetInRoot] as [Offset.Zero] until the
 * first layout pass. Prefer the inner clipping rect in that case.
 */
internal fun resolveSpellcheckTextOriginInRoot(
    unclippedTextOffsetInRoot: Offset?,
    textClippingRectInRoot: Rect?,
): Offset? {
    val clip = textClippingRectInRoot?.takeUnless { it.isEmpty }
    val raw = unclippedTextOffsetInRoot?.takeUnless { it == Offset.Unspecified }
    if (raw != null && raw != Offset.Zero) return raw
    return clip?.topLeft
}

internal fun DrawScope.drawImeAlignedSquiggles(
    layout: TextLayoutResult,
    ranges: List<SpellcheckWord>,
    textOriginInRoot: Offset,
    fieldOriginInRoot: Offset,
    clipInRoot: Rect?,
) {
    val delta = textOriginInRoot - fieldOriginInRoot
    val clip = clipInRoot?.takeUnless { it.isEmpty }
    if (clip != null) {
        val local = clip.translate(-fieldOriginInRoot.x, -fieldOriginInRoot.y)
        clipRect(left = local.left, top = local.top, right = local.right, bottom = local.bottom) {
            translate(left = delta.x, top = delta.y) {
                drawMisspellingSquiggles(this, layout, ranges)
            }
        }
    } else {
        translate(left = delta.x, top = delta.y) {
            drawMisspellingSquiggles(this, layout, ranges)
        }
    }
}

internal fun boundingBoxesForRange(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
): List<Rect> {
    if (start >= end) return emptyList()
    val last = (end - 1).coerceAtLeast(start)
    val startLine = layout.getLineForOffset(start)
    val endLine = layout.getLineForOffset(last)
    val boxes = ArrayList<Rect>(endLine - startLine + 1)
    for (line in startLine..endLine) {
        val lineStart = layout.getLineStart(line).coerceAtLeast(start)
        val lineEndExclusive = layout.getLineEnd(line, visibleEnd = true).coerceAtMost(end)
        if (lineStart >= lineEndExclusive) continue
        val left = layout.getHorizontalPosition(lineStart, usePrimaryDirection = true)
        val rightIndex = (lineEndExclusive - 1).coerceAtLeast(lineStart)
        val right =
            layout.getHorizontalPosition(rightIndex, usePrimaryDirection = true).let { pos ->
                val box = layout.getBoundingBox(rightIndex)
                maxOf(pos, box.right)
            }
        val bottom = layout.getLineBottom(line)
        val top = layout.getLineTop(line)
        boxes.add(Rect(left = minOf(left, right), top = top, right = maxOf(left, right), bottom = bottom))
    }
    return boxes
}

// UI thread only; rewind between words so draw does not allocate a Path per span.
private val WavePath = Path()

internal fun DrawScope.drawWavyUnderline(
    box: Rect,
    color: Color,
) {
    if (box.width <= 0f) return
    val baseline = box.bottom - STROKE_WIDTH
    WavePath.rewind()
    var x = box.left
    WavePath.moveTo(x, baseline)
    var crest = true
    while (x < box.right) {
        val next = (x + WAVE_LENGTH / 2f).coerceAtMost(box.right)
        val controlY = if (crest) baseline - WAVE_AMPLITUDE else baseline + WAVE_AMPLITUDE
        WavePath.quadraticTo((x + next) / 2f, controlY, next, baseline)
        x = next
        crest = !crest
    }
    drawPath(
        path = WavePath,
        color = color,
        style = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

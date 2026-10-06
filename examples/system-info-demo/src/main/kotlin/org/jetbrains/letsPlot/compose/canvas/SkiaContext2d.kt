/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

// Copy of lets-plot-compose 3.2.2's SkiaContext2d, recompiled against the skiko that Compose 1.12 ships.
// Skiko 0.150 turned Matrix33 into a value class, so the published binary fails with an IllegalAccessError
// on its Matrix33 constructor and Canvas.concat / setMatrix. This class shadows the library's copy on the
// classpath; delete it once lets-plot-compose publishes a build against Compose 1.12.

package org.jetbrains.letsPlot.compose.canvas

import org.jetbrains.letsPlot.commons.geometry.AffineTransform
import org.jetbrains.letsPlot.commons.geometry.DoubleRectangle
import org.jetbrains.letsPlot.commons.registration.Disposable
import org.jetbrains.letsPlot.commons.values.Color
import org.jetbrains.letsPlot.core.canvas.*
import org.jetbrains.letsPlot.core.canvas.Canvas.Snapshot
import org.jetbrains.skia.*
import org.jetbrains.skia.Color.TRANSPARENT
import java.util.*

class SkiaContext2d(
    val skCanvas: org.jetbrains.skia.Canvas,
    private val skiaFontManager: SkiaFontManager,
    private val contextState: ContextStateDelegate = ContextStateDelegate(failIfNotImplemented = false),
) : Context2d by contextState, Disposable {
    private val paintStack = Stack<Pair<Paint, Paint>>()

    private val clearPaint = Paint().apply {
        blendMode = BlendMode.CLEAR
    }

    private var strokePaint = Paint().apply {
        setStroke(true)
        isAntiAlias = true
    }

    private var fillPaint = Paint().apply {
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        color = TRANSPARENT
    }

    override fun drawImage(snapshot: Snapshot) {
        require(snapshot is SkiaSnapshot) { "Snapshot must be of type SkiaSnapshot" }
        drawImage(
            snapshot,
            0.0,
            0.0,
            snapshot.size.x.toDouble(),
            snapshot.size.y.toDouble(),
            0.0,
            0.0,
            snapshot.size.x.toDouble(),
            snapshot.size.y.toDouble()
        )
    }

    override fun drawImage(snapshot: Snapshot, x: Double, y: Double) {
        require(snapshot is SkiaSnapshot) { "Snapshot must be of type SkiaSnapshot" }
        drawImage(
            snapshot,
            0.0,
            0.0,
            snapshot.size.x.toDouble(),
            snapshot.size.y.toDouble(),
            x,
            y,
            snapshot.size.x.toDouble(),
            snapshot.size.y.toDouble()
        )
    }

    override fun drawImage(snapshot: Snapshot, x: Double, y: Double, dw: Double, dh: Double) {
        require(snapshot is SkiaSnapshot) { "Snapshot must be of type SkiaSnapshot" }
        drawImage(
            snapshot,
            0.0,
            0.0,
            snapshot.size.x.toDouble(),
            snapshot.size.y.toDouble(),
            x,
            y,
            dw,
            dh
        )
    }

    override fun drawImage(
        snapshot: Snapshot,
        sx: Double,
        sy: Double,
        sw: Double,
        sh: Double,
        dx: Double,
        dy: Double,
        dw: Double,
        dh: Double
    ) {
        require(snapshot is SkiaSnapshot) { "Snapshot must be of type SkiaSnapshot" }
        val srcRect = Rect(sx.toFloat(), sy.toFloat(), (sx + sw).toFloat(), (sy + sh).toFloat())
        val dstRect = Rect(dx.toFloat(), dy.toFloat(), (dx + dw).toFloat(), (dy + dh).toFloat())
        val paint = Paint()
        skCanvas.drawImageRect(
            snapshot.skImage,
            srcRect,
            dstRect,
            paint
        )

        paint.close()
    }

    override fun save() {
        contextState.save()
        paintStack.push(strokePaint.makeClone() to fillPaint.makeClone())
        skCanvas.save()
    }

    override fun restore() {
        contextState.restore()

        val (restoredStroke, restoredFill) = paintStack.pop()
        strokePaint.close()
        fillPaint.close()
        strokePaint = restoredStroke
        fillPaint = restoredFill

        skCanvas.restore()
    }

    override fun rotate(angle: Double) {
        contextState.rotate(angle)
        skCanvas.rotate(angle.toFloat())
    }

    override fun translate(x: Double, y: Double) {
        contextState.translate(x, y)
        skCanvas.translate(x.toFloat(), y.toFloat())
    }

    override fun transform(sx: Double, ry: Double, rx: Double, sy: Double, tx: Double, ty: Double) {
        contextState.transform(sx = sx, ry = ry, rx = rx, sy = sy, tx = tx, ty = ty)
        skCanvas.concat(
            Matrix33(
                sx.toFloat(), rx.toFloat(), tx.toFloat(),
                ry.toFloat(), sy.toFloat(), ty.toFloat(),
                0f, 0f, 1f
            )
        )
    }

    override fun scale(x: Double, y: Double) {
        contextState.scale(x, y)
        skCanvas.scale(x.toFloat(), y.toFloat())
    }

    override fun scale(xy: Double) {
        contextState.scale(xy)
        skCanvas.scale(xy.toFloat(), xy.toFloat())
    }

    override fun setTransform(m00: Double, m10: Double, m01: Double, m11: Double, m02: Double, m12: Double) {
        contextState.setTransform(m00, m10, m01, m11, m02, m12)
        skCanvas.setMatrix(
            Matrix33(
                m00.toFloat(), m10.toFloat(), 0f,
                m01.toFloat(), m11.toFloat(), 0f,
                m02.toFloat(), m12.toFloat(), 1f
            )
        )
    }

    override fun clearRect(rect: DoubleRectangle) {
        clearRect(rect.left, rect.top, rect.width, rect.height)
    }

    override fun clearRect(x: Double, y: Double, w: Double, h: Double) {
        skCanvas.drawRect(skiaRectFromXYWH(x, y, w, h), clearPaint)
    }

    override fun fillRect(x: Double, y: Double, w: Double, h: Double) {
        skCanvas.drawRect(skiaRectFromXYWH(x, y, w, h), fillPaint)
    }

    override fun strokeRect(x: Double, y: Double, w: Double, h: Double) {
        skCanvas.drawRect(skiaRectFromXYWH(x, y, w, h), strokePaint)
    }

    override fun drawCircle(x: Double, y: Double, radius: Double) {
        skCanvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), strokePaint)
        skCanvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), fillPaint)
    }

    override fun fillText(text: String, x: Double, y: Double) {
        drawText(text, x, y, fillPaint)
    }

    override fun strokeText(text: String, x: Double, y: Double) {
        drawText(text, x, y, strokePaint)
    }

    private fun drawText(text: String, x: Double, y: Double, paint: Paint) {
        val skiaFont = skiaFontManager.findFont(contextState.getFont())

        val textBlob = TextBlobBuilder()
            .appendRun(skiaFont, text, 0f, 0f)
            .build()

        if (textBlob == null) {
            // No glyphs to draw (e.g., empty string)
            return
        }

        skCanvas.drawTextBlob(textBlob, x.toFloat(), y.toFloat(), paint)
        textBlob.close()
    }

    override fun measureText(str: String): TextMetrics {
        val skiaFont = skiaFontManager.findFont(contextState.getFont())
        val r = skiaFont.measureText(str, fillPaint)

        // font.measureText ignores trailing spaces, so we need to use measureTextWidth
        val w = skiaFont.measureTextWidth(str)

        val textMetrics = TextMetrics(
            ascent = r.top.toDouble(),
            descent = r.bottom.toDouble(),
            bbox = DoubleRectangle.XYWH(
                x = r.left,
                y = r.top,
                width = w,
                height = r.height
            ),
        )

        return textMetrics
    }

    override fun setLineDash(lineDash: DoubleArray) {
        contextState.setLineDash(lineDash)
        setupPathEffect()
    }

    override fun setLineDashOffset(lineDashOffset: Double) {
        contextState.setLineDashOffset(lineDashOffset)
        setupPathEffect()
    }

    private fun setupPathEffect() {
        val lineDash = contextState.getLineDash().map(Double::toFloat).toFloatArray()
        if (lineDash.isEmpty()) {
            strokePaint.pathEffect = null
            return
        }

        val phase = contextState.getLineDashOffset().toFloat()

        strokePaint.pathEffect = PathEffect.makeDash(lineDash, phase)
    }


    override fun stroke() {
        // Make ctm identity. null for degenerate case, e.g., scale(0, 0) - skip drawing.
        val inverseCtmTransform = contextState.getCTM().inverse() ?: return

        withPath(contextState.getCurrentPath(), inverseCtmTransform) { path ->
            skCanvas.drawPath(path, strokePaint)
        }
    }

    override fun fill() {
        // Make ctm identity. null for degenerate case, e.g., scale(0, 0) - skip drawing.
        val inverseCtmTransform = contextState.getCTM().inverse() ?: return
        withPath(contextState.getCurrentPath(), inverseCtmTransform) { path ->
            skCanvas.drawPath(path, fillPaint)
        }
    }

    override fun fillEvenOdd() {
        // Make ctm identity. null for degenerate case, e.g., scale(0, 0) - skip drawing.
        val inverseCtmTransform = contextState.getCTM().inverse() ?: return

        withPath(contextState.getCurrentPath(), inverseCtmTransform) { path ->
            path.fillMode = PathFillMode.EVEN_ODD
            skCanvas.drawPath(path, fillPaint)
        }
    }

    override fun setFillStyle(color: Color?) {
        fillPaint.color = skiaIntFromColor(color)
    }

    override fun setStrokeStyle(color: Color?) {
        strokePaint.color = skiaIntFromColor(color)
    }

    override fun setLineWidth(lineWidth: Double) {
        strokePaint.strokeWidth = lineWidth.toFloat()
    }

    override fun setLineJoin(lineJoin: LineJoin) {
        strokePaint.strokeJoin = when (lineJoin) {
            LineJoin.BEVEL -> PaintStrokeJoin.BEVEL
            LineJoin.MITER -> PaintStrokeJoin.MITER
            LineJoin.ROUND -> PaintStrokeJoin.ROUND
        }
    }

    override fun setLineCap(lineCap: LineCap) {
        strokePaint.strokeCap = when (lineCap) {
            LineCap.BUTT -> PaintStrokeCap.BUTT
            LineCap.ROUND -> PaintStrokeCap.ROUND
            LineCap.SQUARE -> PaintStrokeCap.SQUARE
        }
    }

    override fun setStrokeMiterLimit(miterLimit: Double) {
        strokePaint.strokeMiter = miterLimit.toFloat()
    }

    override fun measureTextWidth(str: String): Double {
        return measureText(str).bbox.width
    }

    override fun clip() {
        // Make ctm identity. null for degenerate case, e.g., scale(0, 0) - skip drawing.
        val inverseCtmTransform = contextState.getCTM().inverse() ?: return

        withPath(contextState.getCurrentPath(), inverseCtmTransform) { path ->
            skCanvas.clipPath(path)
        }
    }

    private fun withPath(commands: List<Path2d.PathCommand>, transform: AffineTransform, block: (Path) -> Unit) {
        if (commands.isEmpty()) {
            return
        }

        val path = PathBuilder()

        commands
            .asSequence()
            .map { cmd -> cmd.transform(transform) }
            .forEach { cmd ->
                when (cmd) {
                    is Path2d.MoveTo -> path.moveTo(cmd.x.toFloat(), cmd.y.toFloat())
                    is Path2d.LineTo -> path.lineTo(cmd.x.toFloat(), cmd.y.toFloat())
                    is Path2d.CubicCurveTo -> {
                        cmd.controlPoints.asSequence()
                            .windowed(size = 3, step = 3)
                            .forEach { (cp1, cp2, cp3) ->
                                path.cubicTo(
                                    cp1.x.toFloat(), cp1.y.toFloat(),
                                    cp2.x.toFloat(), cp2.y.toFloat(),
                                    cp3.x.toFloat(), cp3.y.toFloat()
                                )
                            }
                    }

                    is Path2d.ClosePath -> path.closePath()
                }
            }

        block(path.detach())
        path.close()
    }

    override fun dispose() {
        strokePaint.close()
        fillPaint.close()
        clearPaint.close()

        paintStack.forEach { (stroke, fill) ->
            stroke.close()
            fill.close()
        }

        backgroundPaint.close()
    }

    companion object {
        private fun skiaRectFromXYWH(x: Double, y: Double, w: Double, h: Double): Rect {
            return Rect(
                x.toFloat(),
                y.toFloat(),
                (x + w).toFloat(),
                (y + h).toFloat()
            )
        }

        internal fun skiaIntFromColor(color: Color?, def: Int = 0): Int {
            if (color == null) {
                return def
            }

            return Color4f(
                r = (color.red / 255.0).toFloat(),
                g = (color.green / 255.0).toFloat(),
                b = (color.blue / 255.0).toFloat(),
                a = (color.alpha / 255.0).toFloat()
            ).toColor()
        }

    }
}

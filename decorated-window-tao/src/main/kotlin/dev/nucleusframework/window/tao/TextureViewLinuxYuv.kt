package dev.nucleusframework.window.tao

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/**
 * Coefficients of the Y'CbCr → RGB conversion for one colour space, as the
 * uniforms of [ShaderYuvPainter]'s runtime effect.
 *
 * The coefficients are derived from the format's Kr/Kb rather than copied from a
 * table of magic numbers, and the bundled planar test producer converts the other
 * way from the same definition — which is what lets a smoke test assert that a
 * frame composites back as the colour it was published as.
 */
internal class YuvConversion private constructor(
    private val rowR: FloatArray,
    private val rowG: FloatArray,
    private val rowB: FloatArray,
    private val offset: FloatArray,
) {
    /** One row of the matrix per output channel, plus the offset applied first. */
    fun bindMatrix(builder: RuntimeShaderBuilder) {
        builder.uniform("rowR", rowR[0], rowR[1], rowR[2])
        builder.uniform("rowG", rowG[0], rowG[1], rowG[2])
        builder.uniform("rowB", rowB[0], rowB[1], rowB[2])
        builder.uniform("yuvOffset", offset[0], offset[1], offset[2])
    }

    internal companion object {
        /** 16..235 studio swing, and the neutral chroma sample, as texture values. */
        private const val LIMITED_LUMA_FLOOR = 16f / 255f
        private const val LIMITED_LUMA_SCALE = 255f / 219f
        private const val LIMITED_CHROMA_SCALE = 255f / 224f
        private const val NEUTRAL_CHROMA = 128f / 255f

        private const val KR_601 = 0.299f
        private const val KB_601 = 0.114f
        private const val KR_709 = 0.2126f
        private const val KB_709 = 0.0722f

        fun of(colorSpace: NucleusYuvColorSpace): YuvConversion {
            val bt709 =
                colorSpace == NucleusYuvColorSpace.BT709_LIMITED ||
                    colorSpace == NucleusYuvColorSpace.BT709_FULL
            val limited =
                colorSpace == NucleusYuvColorSpace.BT601_LIMITED ||
                    colorSpace == NucleusYuvColorSpace.BT709_LIMITED
            val kr = if (bt709) KR_709 else KR_601
            val kb = if (bt709) KB_709 else KB_601
            val kg = 1f - kr - kb
            val lumaScale = if (limited) LIMITED_LUMA_SCALE else 1f
            val chromaGain = if (limited) LIMITED_CHROMA_SCALE else 1f
            // R = Y + 2(1-Kr)·Cr, B = Y + 2(1-Kb)·Cb, G = (Y - Kr·R - Kb·B) / Kg,
            // written as one row per output channel over (Y', Cb', Cr').
            val toRed = 2f * (1f - kr) * chromaGain
            val toBlue = 2f * (1f - kb) * chromaGain
            return YuvConversion(
                rowR = floatArrayOf(lumaScale, 0f, toRed),
                rowG = floatArrayOf(lumaScale, -kb / kg * toBlue, -kr / kg * toRed),
                rowB = floatArrayOf(lumaScale, toBlue, 0f),
                offset =
                    floatArrayOf(
                        if (limited) -LIMITED_LUMA_FLOOR else 0f,
                        -NEUTRAL_CHROMA,
                        -NEUTRAL_CHROMA,
                    ),
            )
        }
    }
}

/**
 * Paints a planar YUV import: one runtime effect samples the luma plane and the two
 * chroma planes and converts as it goes, so the frame reaches the scene with no
 * copy and no per-frame work at all — the property the packed RGB path has.
 *
 * This is why only the three-plane layouts are supported. Interleaved chroma
 * (`NV12`) would need the chroma plane as a two-channel texture, and the Skia
 * build Compose ships maps no colour type to one — `R8G8_UNORM` over `GL_RG8` is
 * refused at adoption. Reading that plane as *bytes* instead and picking Cb and Cr
 * out of neighbouring texels does not work either: a runtime effect only samples a
 * child image at coordinates that are an **affine** function of the draw's, and the
 * snapping that picking every other byte needs is not one — such a sample silently
 * yields transparent black.
 *
 * The paint is rebuilt only when the destination geometry or the filter changes —
 * the shader bakes both into its uniforms — so a producer frame still costs
 * nothing but the draw itself.
 */
internal class ShaderYuvPainter(
    private val conversion: YuvConversion,
    private val images: List<Image>,
    private val chromaScaleX: Float,
    private val chromaScaleY: Float,
) {
    private var paint: YuvPaint? = null
    private var paintSampling: SamplingMode? = null
    private var paintDst = Rect(0f, 0f, 0f, 0f)

    fun draw(
        canvas: Canvas,
        srcRect: Rect,
        dst: Rect,
        sampling: SamplingMode,
    ) {
        val cached = paint
        val current =
            if (cached != null && paintSampling == sampling && sameGeometry(paintDst, dst)) {
                cached
            } else {
                cached?.close()
                buildPaint(srcRect, dst, sampling).also {
                    paint = it
                    paintSampling = sampling
                    paintDst = dst
                }
            }
        canvas.drawRect(dst, current.paint)
    }

    private fun buildPaint(
        srcRect: Rect,
        dst: Rect,
        sampling: SamplingMode,
    ): YuvPaint {
        val children =
            images.map { image ->
                image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null)
            }
        val builder = RuntimeShaderBuilder(effect)
        builder.child("yPlane", children[0])
        builder.child("cbPlane", children[1])
        builder.child("crPlane", children[2])
        conversion.bindMatrix(builder)
        // The shader is fed the canvas' local coordinates, i.e. the same space
        // `dst` is in, and maps them back to luma pixels itself.
        builder.uniform("srcOrigin", dst.left, dst.top)
        builder.uniform("srcScale", srcRect.width / dst.width, srcRect.height / dst.height)
        builder.uniform("chromaScale", chromaScaleX, chromaScaleY)
        val shader = builder.makeShader(null)
        builder.close()
        return YuvPaint(Paint().apply { this.shader = shader }, shader, children)
    }

    fun close() {
        paint?.close()
        paint = null
    }

    private fun sameGeometry(
        a: Rect,
        b: Rect,
    ): Boolean = a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

    private companion object {
        /**
         * `float` rather than `half` throughout: the uniform layout of a runtime
         * effect is unambiguous that way, and the arithmetic is a handful of
         * multiply-adds per pixel either way. Every child is sampled at an affine
         * function of the draw's coordinates, which is the only sampling Skia
         * reliably performs at all.
         */
        private val effect: RuntimeEffect =
            RuntimeEffect.makeForShader(
                """
                uniform shader yPlane;
                uniform shader cbPlane;
                uniform shader crPlane;
                uniform float3 rowR;
                uniform float3 rowG;
                uniform float3 rowB;
                uniform float3 yuvOffset;
                uniform float2 srcOrigin;
                uniform float2 srcScale;
                uniform float2 chromaScale;
                half4 main(float2 coord) {
                    float2 tex = (coord - srcOrigin) * srcScale;
                    float2 chroma = tex * chromaScale;
                    float3 yuv = float3(
                        float(yPlane.eval(tex).r),
                        float(cbPlane.eval(chroma).r),
                        float(crPlane.eval(chroma).r)) + yuvOffset;
                    float3 rgb = clamp(float3(dot(rowR, yuv), dot(rowG, yuv), dot(rowB, yuv)), 0.0, 1.0);
                    return half4(half3(rgb), 1.0);
                }
                """.trimIndent(),
            )
    }
}

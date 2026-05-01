package io.github.kdroidfilter.nucleus.window.tao.render

/**
 * Output of `NativeMetalBridge.nativeBeginFrame`. Constructed from the ObjC
 * side via JNI — keep the constructor signature `(JJIIF)V` in sync with
 * `NucleusTaoMetal.m → ensureFrameClassLoaded`.
 *
 * @param drawablePtr retained `id<CAMetalDrawable>`; **must** be released by a
 *   matching `nativePresent` call.
 * @param texturePtr  raw `id<MTLTexture>` to wrap in a Skia `BackendRenderTarget`.
 * @param widthPx, heightPx physical pixels of the texture.
 * @param scale       contentsScale applied to the layer (1.0 / 2.0 / 3.0).
 */
data class MetalFrame(
    val drawablePtr: Long,
    val texturePtr: Long,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
)

package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import java.util.logging.Logger

internal val textureLogger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.texture")

/** `GL_TEXTURE_2D` and the Skia GL formats of the textures we adopt. */
private const val GL_TEXTURE_2D = 0x0DE1
private const val GR_GL_RGBA8 = 0x8058
private const val GR_GL_R8 = 0x8229

/** `DRM_FORMAT_R8` — the format every plane of a planar buffer is imported as. */
private const val DRM_FORMAT_R8 = 0x20203852

/** Radix for the staged import-failure codes, which read as `stage | driver error`. */
private const val HEX = 16

/** The single RGBA plane of a packed buffer — the driver interprets its FourCC. */
internal fun packedPlaneSpec(source: DmaBufTextureSource): PlaneSpec =
    PlaneSpec(
        plane = NucleusDmaBufPlane(source.fd, source.stride, source.offset, source.modifier),
        fourcc = source.fourcc,
        widthPx = source.widthPx,
        heightPx = source.heightPx,
        glFormat = GR_GL_RGBA8,
        // RGBA8 whatever the buffer's FourCC: the driver interprets the DRM format
        // when creating the EGLImage, so sampling the texture already yields
        // (R, G, B, A). X-variants (no alpha) sample as opaque.
        colorType = ColorType.RGBA_8888,
    )

/** Adopts an RGBA texture imported elsewhere — the producer-owned `EGLImage` path. */
internal fun adoptPackedPlane(
    host: TaoGlTextureHost,
    handle: Long,
    widthPx: Int,
    heightPx: Int,
): ImportedPlane? = adoptPlane(host, handle, widthPx, heightPx, GR_GL_RGBA8, ColorType.RGBA_8888)

/** The luma plane: `R8` is its actual DRM format, so nothing is reinterpreted. */
internal fun lumaSpec(source: YuvDmaBufTextureSource): PlaneSpec =
    PlaneSpec(
        plane = source.planes[0],
        fourcc = DRM_FORMAT_R8,
        widthPx = source.widthPx,
        heightPx = source.heightPx,
    )

/**
 * The two chroma planes — each an `R8` plane at half resolution, read as it is
 * stored. `I420` lists Cb then Cr and `YV12` the other way round; normalising the
 * order here keeps one shader for both.
 */
internal fun chromaSpecs(source: YuvDmaBufTextureSource): List<PlaneSpec> {
    val chromaW = (source.widthPx + 1) / 2
    val chromaH = (source.heightPx + 1) / 2
    val cbFirst = source.format != NucleusYuvFormat.YV12
    return listOf(
        PlaneSpec(source.planes[if (cbFirst) 1 else 2], DRM_FORMAT_R8, chromaW, chromaH),
        PlaneSpec(source.planes[if (cbFirst) 2 else 1], DRM_FORMAT_R8, chromaW, chromaH),
    )
}

/**
 * Description of one plane to import: which descriptor, at which size, and how
 * both the driver (DRM FourCC) and Skia (GL format + colour type) are told to read
 * it. Single-channel by default, which every plane of a planar buffer is.
 */
internal class PlaneSpec(
    val plane: NucleusDmaBufPlane,
    val fourcc: Int,
    val widthPx: Int,
    val heightPx: Int,
    val glFormat: Int = GR_GL_R8,
    val colorType: ColorType = ColorType.GRAY_8,
)

/** Imports one plane and adopts it into Skia. The surface's EGL context must be current. */
internal fun importPlane(
    host: TaoGlTextureHost,
    spec: PlaneSpec,
): ImportedPlane? {
    val handle =
        NativeTaoLinuxTextureBridge.nativeImportDmaBuf(
            spec.plane.fd,
            spec.fourcc,
            spec.widthPx,
            spec.heightPx,
            spec.plane.stride,
            spec.plane.offset,
            spec.plane.modifier,
        )
    if (handle <= 0L) {
        logImportFailure(handle)
        return null
    }
    return adoptPlane(host, handle, spec.widthPx, spec.heightPx, spec.glFormat, spec.colorType)
}

/** Imports every plane of one attempt, or none of them. Context must be current. */
internal fun importPlanes(
    host: TaoGlTextureHost,
    specs: List<PlaneSpec>,
): List<ImportedPlane>? {
    val planes = ArrayList<ImportedPlane>(specs.size)
    for (spec in specs) {
        val plane = importPlane(host, spec)
        if (plane == null) {
            closePlanes(planes)
            return null
        }
        planes += plane
    }
    return planes
}

@Suppress("LongParameterList")
private fun adoptPlane(
    host: TaoGlTextureHost,
    handle: Long,
    widthPx: Int,
    heightPx: Int,
    glFormat: Int,
    colorType: ColorType,
): ImportedPlane? {
    val texId = NativeTaoLinuxTextureBridge.nativeGlTextureId(handle)
    val adopted =
        runCatching {
            Image.adoptTextureFrom(
                host.directContext,
                BackendTexture.makeGL(widthPx, heightPx, false, texId, GL_TEXTURE_2D, glFormat),
                SurfaceOrigin.TOP_LEFT,
                colorType,
            )
        }
    val image = adopted.getOrNull()
    if (image == null) {
        // Skia validates the colour type against the GL format and its own caps,
        // so this is where a plane format the build of Skia does not support on
        // the GPU shows up — worth naming, since the import itself succeeded.
        textureLogger.warning {
            "TextureView: Skia refused the imported plane ($colorType, GL format 0x${
                glFormat.toString(HEX)
            }): ${adopted.exceptionOrNull()?.message ?: "null image"}"
        }
        // Skia never adopted the texture — the native side must delete it.
        NativeTaoLinuxTextureBridge.nativeDestroy(handle, deleteTexture = true)
        return null
    }
    return ImportedPlane(handle, image)
}

/** Releases the planes imported before a later one failed. Context must be current. */
internal fun closePlanes(planes: List<ImportedPlane>) {
    planes.forEach { plane ->
        plane.image.close()
        NativeTaoLinuxTextureBridge.nativeDestroy(plane.handle, deleteTexture = false)
    }
}

internal fun logImportFailure(handle: Long) {
    // The import can fail for reasons the caller cannot see from Kotlin (driver
    // without EGL_EXT_image_dma_buf_import, a modifier the GPU can't read, a
    // FourCC/stride that doesn't describe the buffer), and the composable then
    // just renders an empty Box. Say why once.
    textureLogger.warning { "TextureView: external texture import failed (stage 0x${(-handle).toString(HEX)})" }
}

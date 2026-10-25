package dev.nucleusframework.systeminfo.model

/**
 * How well the GPU an app gets by default handles 3D content, so an app can turn off a 3D engine
 * (Filament, …) on a machine where it would fail or crawl, see `SystemInfo.renderTier()`.
 */
public enum class RenderTier {
    /** The platform could not be probed. */
    UNKNOWN,

    /**
     * No GPU acceleration: rendering falls back to a CPU rasterizer (Windows WARP / Microsoft Basic
     * Render Driver, Mesa llvmpipe, a Mac VM without Metal).
     */
    SOFTWARE,

    /**
     * A GPU too old for modern 3D engines: Direct3D feature level below 11_0 on Windows, no GL ES 3
     * context on Linux. Typically 2007–2010 hardware (Intel Ironlake / GMA, GeForce 8–200, Radeon HD 2000–4000).
     */
    LEGACY,

    /** A GPU at Direct3D 11 / GL ES 3 / Metal level or better. */
    CAPABLE,
}

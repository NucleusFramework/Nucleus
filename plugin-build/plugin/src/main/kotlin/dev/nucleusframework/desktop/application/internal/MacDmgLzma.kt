/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.tasks.ELECTRON_BUILDER_TOOL_DIR_NAME
import dev.nucleusframework.internal.utils.Arch
import java.io.File

/**
 * Pure helpers for post-processing a macOS DMG with LZMA (ULMO) compression.
 *
 * electron-builder's bundled `dmgbuild` caps DMG compression at bzip2 (UDBZ) — its format
 * allow-list has no `ULMO` entry — even though `hdiutil` has supported LZMA-compressed images
 * since macOS 10.15. Recompressing the finished image with `hdiutil convert -format ULMO`
 * typically shaves ~20% off a bzip2 DMG, matching the LZMA that NSIS uses on Windows.
 */
internal object MacDmgLzma {
    private const val CATALINA_MAJOR = 10
    private const val CATALINA_MINOR = 15

    /**
     * ULMO (LZMA) disk images only mount on macOS 10.15 (Catalina) and later. Returns whether a
     * DMG targeting [minimumSystemVersion] may safely use ULMO. A null/blank value carries no
     * constraint and is treated as a modern target.
     */
    fun isUlmoCompatible(minimumSystemVersion: String?): Boolean {
        if (minimumSystemVersion.isNullOrBlank()) return true
        val parts = minimumSystemVersion.trim().split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return when {
            major > CATALINA_MAJOR -> true
            major < CATALINA_MAJOR -> false
            else -> minor >= CATALINA_MINOR
        }
    }

    private const val APP_BUILDER_SEARCH_DEPTH = 8

    /**
     * Locates electron-builder's `app-builder` binary, used to regenerate the differential-update
     * blockmap after recompression.
     *
     * Search order, all task-local (see `isolatedCacheEnv`): electron-builder's own cache
     * (`<outputDir>/.electron-builder-cache`, where `ELECTRON_BUILDER_CACHE` points and where
     * electron-builder downloads `app-builder-bin` at run time), the provisioned toolchain
     * (`<outputDir>/.electron-builder-tool/node_modules`, in case a future version ships it as an
     * npm dependency again), then the isolated npm cache. `userHome` keeps npm's per-user npx cache
     * as a last resort for builds still carrying one from an earlier plugin version.
     *
     * Returns null when it cannot be found — the caller then drops the now-stale blockmap instead.
     */
    fun locateAppBuilder(
        outputDir: File,
        arch: Arch,
        userHome: File = File(System.getProperty("user.home")),
    ): File? {
        val binaryName = if (arch == Arch.Arm64) "app-builder_arm64" else "app-builder_amd64"
        val roots =
            listOf(
                File(outputDir, ".electron-builder-cache"),
                File(outputDir, "$ELECTRON_BUILDER_TOOL_DIR_NAME/node_modules"),
                File(outputDir, ".npm-cache"),
                File(userHome, ".npm/_npx"),
            )
        for (root in roots) {
            findAppBuilder(root, binaryName)?.let { return it }
        }
        return null
    }

    private fun findAppBuilder(
        root: File,
        binaryName: String,
    ): File? {
        if (!root.isDirectory) return null
        return root
            .walkTopDown()
            .maxDepth(APP_BUILDER_SEARCH_DEPTH)
            .firstOrNull { it.isFile && it.name == binaryName && it.parentFile?.name == "mac" }
    }
}

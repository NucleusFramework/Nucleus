/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class AppImageSettings {
    /** Desktop file category. */
    var category: AppImageCategory? = null

    /** Desktop file generic name */
    var genericName: String? = null

    /** Synopsis (short description for .desktop) */
    var synopsis: String? = null

    /** Additional desktop file entries (key=value) */
    var desktopEntries: Map<String, String> = emptyMap()

    /**
     * Archive compression for this AppImage only.
     *
     * Overrides the root [JvmApplicationDistributions.compressionLevel] when set.
     * Prefer [CompressionLevel.Normal] or [CompressionLevel.Store]:
     * [CompressionLevel.Maximum] / [CompressionLevel.Ultra] map to electron-builder
     * `maximum` and can make cold starts very slow (squashfs/FUSE decompression).
     */
    var compressionLevel: CompressionLevel? = null
}

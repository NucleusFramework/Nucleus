/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/** AppImage packaging options (`.desktop` entry metadata and AppImage-specific settings). */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class AppImageSettings {
    /** Desktop file category. */
    @get:Input
    @get:Optional
    var category: AppImageCategory? = null

    /** Desktop file generic name */
    @get:Input
    @get:Optional
    var genericName: String? = null

    /** Synopsis (short description for .desktop) */
    @get:Input
    @get:Optional
    var synopsis: String? = null

    /** Additional desktop file entries (key=value) */
    @get:Input
    var desktopEntries: Map<String, String> = emptyMap()

    /**
     * Archive compression for this AppImage only.
     *
     * Overrides the root [JvmApplicationDistributions.compressionLevel] when set.
     * Prefer [CompressionLevel.Normal] or [CompressionLevel.Store]:
     * [CompressionLevel.Maximum] / [CompressionLevel.Ultra] map to electron-builder
     * `maximum` and can make cold starts very slow (squashfs/FUSE decompression).
     */
    @get:Input
    @get:Optional
    var compressionLevel: CompressionLevel? = null
}

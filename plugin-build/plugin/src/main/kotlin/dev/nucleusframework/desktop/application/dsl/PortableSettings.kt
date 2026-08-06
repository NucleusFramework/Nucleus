/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

/**
 * Settings for the Windows portable (self-extracting) package.
 *
 * ```kotlin
 * nativeDistributions {
 *     compressionLevel = CompressionLevel.Ultra
 *     windows {
 *         portable {
 *             // Keep portable extraction snappy while DEB/DMG still use Ultra.
 *             compressionLevel = CompressionLevel.Normal
 *         }
 *     }
 * }
 * ```
 */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class PortableSettings {
    /**
     * Archive compression for the portable EXE only.
     *
     * Overrides the root [JvmApplicationDistributions.compressionLevel] when set.
     * Portable packages are self-extracting archives: higher levels shrink the download
     * but slow first launch while the payload is unpacked.
     */
    var compressionLevel: CompressionLevel? = null
}

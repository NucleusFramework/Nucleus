/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

/** AppImage packaging options (`.desktop` entry metadata and AppImage-specific settings). */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class AppImageSettings {
    /** Desktop file category. */
    var category: AppImageCategory? = null

    /** Desktop file generic name */
    var genericName: String? = null

    /** Synopsis (short description for .desktop) */
    var synopsis: String? = null

    /**
     * Additional desktop file entries (key=value), applied to the `.desktop` file of every
     * Linux target (deb, rpm, pacman, AppImage, …), not only the AppImage.
     *
     * Entries win over the values Nucleus derives: `Name` over `appName`, `Comment` over
     * `description`, `Categories` over [LinuxPlatformSettings.appCategory]. Localized keys
     * (`Name[he]`, `Comment[ru]`, …) are written as-is, so a launcher found by typing in one
     * script can carry translations, e.g. `"Name" to "Zayit", "Name[he]" to "זית"`.
     *
     * A `Comment` entry also replaces `description` as the Linux package description, since
     * electron-builder reads both from the same option.
     */
    var desktopEntries: Map<String, String> = emptyMap()

    /**
     * Archive compression for this AppImage only.
     *
     * Overrides the root [JvmApplicationDistributions.compressionLevel] when set.
     * [CompressionLevel.Store] builds a gzip image, every other level a zstd image.
     * [CompressionLevel.Normal], [CompressionLevel.Maximum] and [CompressionLevel.Ultra] produce
     * the same image: electron-builder passes no zstd level to `mksquashfs`, so all three use
     * its default.
     */
    var compressionLevel: CompressionLevel? = null
}

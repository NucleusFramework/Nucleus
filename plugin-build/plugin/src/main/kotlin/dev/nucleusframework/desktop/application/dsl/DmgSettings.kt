/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/** macOS DMG installer options: background, window layout, icon positions and signing. */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class DmgSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Background image for the DMG window (.tiff or .png) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val background: RegularFileProperty = objects.fileProperty()

    /** Background color as CSS color (e.g. "#ffffff"). Overridden by [background] if set. */
    @get:Input
    @get:Optional
    var backgroundColor: String? = null

    /** Badge icon shown when the DMG is mounted */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val badgeIcon: RegularFileProperty = objects.fileProperty()

    /** Volume icon (.icns) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val icon: RegularFileProperty = objects.fileProperty()

    /** Icon size in pixels. Default: null (electron-builder uses 80) */
    @get:Input
    @get:Optional
    var iconSize: Int? = null

    /** Icon text size in pixels. Default: null (electron-builder uses 12) */
    @get:Input
    @get:Optional
    var iconTextSize: Int? = null

    /** Volume name. Supports ${productName} and ${version} placeholders. */
    @get:Input
    @get:Optional
    var title: String? = null

    /** DMG image format. Default: null (electron-builder uses UDZO) */
    @get:Input
    @get:Optional
    var format: DmgFormat? = null

    /** DMG image size (e.g. "150m", "4g"). Default: null (auto-sized) */
    @get:Input
    @get:Optional
    var size: String? = null

    /** Whether to shrink the DMG image. Default: null (electron-builder uses true) */
    @get:Input
    @get:Optional
    var shrink: Boolean? = null

    /** Sign the DMG image. Default: false */
    @get:Input
    var sign: Boolean = false

    @get:Nested
    val window: DmgWindowSettings = objects.newInstance(DmgWindowSettings::class.java)

    /** Configures the Finder window shown when the DMG is opened. */
    fun window(fn: Action<DmgWindowSettings>) {
        fn.execute(window)
    }

    @get:Nested
    internal val contents: MutableList<DmgContentEntry> = mutableListOf()

    /** Add a content entry (icon position) to the DMG layout. */
    fun content(
        x: Int,
        y: Int,
        type: DmgContentType? = null,
        name: String? = null,
        path: String? = null,
    ) {
        contents.add(DmgContentEntry(x = x, y = y, type = type, name = name, path = path))
    }
}

/** DMG image format passed to hdiutil / electron-builder. */
enum class DmgFormat(
    val id: String,
) {
    /** UDIF read/write image */
    UDRW("UDRW"),

    /** UDIF read-only image */
    UDRO("UDRO"),

    /** UDIF ADC-compressed image */
    UDCO("UDCO"),

    /** UDIF zlib-compressed image */
    UDZO("UDZO"),

    /** UDIF bzip2-compressed image */
    UDBZ("UDBZ"),

    /** UDIF lzfse-compressed image */
    ULFO("ULFO"),
}

/** Position and size of the Finder window shown when the DMG is opened; `null` keeps electron-builder's default. */
@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class DmgWindowSettings {
    /** Window x position. Default: null (electron-builder uses 400) */
    @get:Input
    @get:Optional
    var x: Int? = null

    /** Window y position. Default: null (electron-builder uses 100) */
    @get:Input
    @get:Optional
    var y: Int? = null

    /** Window width. Default: null (electron-builder uses 540) */
    @get:Input
    @get:Optional
    var width: Int? = null

    /** Window height. Default: null (electron-builder uses 380) */
    @get:Input
    @get:Optional
    var height: Int? = null
}

/** An icon placed in the DMG window at ([x], [y]), as passed to electron-builder's `dmg.contents`. */
data class DmgContentEntry(
    @get:Input val x: Int,
    @get:Input val y: Int,
    @get:Input @get:Optional val type: DmgContentType? = null,
    @get:Input @get:Optional val name: String? = null,
    @get:Input @get:Optional val path: String? = null,
)

/** Type of content entry in DMG layout. */
enum class DmgContentType(
    val id: String,
) {
    Link("link"),
    File("file"),
    Dir("dir"),
}

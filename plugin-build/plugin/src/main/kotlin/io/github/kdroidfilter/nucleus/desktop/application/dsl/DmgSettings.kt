/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class DmgSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Background image for the DMG window (.tiff or .png) */
    val background: RegularFileProperty = objects.fileProperty()

    /** Background color as CSS color (e.g. "#ffffff"). Overridden by [background] if set. */
    var backgroundColor: String? = null

    /** Badge icon shown when the DMG is mounted */
    val badgeIcon: RegularFileProperty = objects.fileProperty()

    /** Volume icon (.icns) */
    val icon: RegularFileProperty = objects.fileProperty()

    /** Icon size in pixels. Default: null (electron-builder uses 80) */
    var iconSize: Int? = null

    /** Icon text size in pixels. Default: null (electron-builder uses 12) */
    var iconTextSize: Int? = null

    /** Volume name. Supports ${productName} and ${version} placeholders. */
    var title: String? = null

    /** DMG image format. Default: null (electron-builder uses UDZO) */
    var format: DmgFormat? = null

    /** DMG image size (e.g. "150m", "4g"). Default: null (auto-sized) */
    var size: String? = null

    /** Whether to shrink the DMG image. Default: null (electron-builder uses true) */
    var shrink: Boolean? = null

    /** Sign the DMG image. Default: false */
    var sign: Boolean = false

    val window: DmgWindowSettings = objects.newInstance(DmgWindowSettings::class.java)

    fun window(fn: Action<DmgWindowSettings>) {
        fn.execute(window)
    }

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

@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class DmgWindowSettings {
    /** Window x position. Default: null (electron-builder uses 400) */
    var x: Int? = null

    /** Window y position. Default: null (electron-builder uses 100) */
    var y: Int? = null

    /** Window width. Default: null (electron-builder uses 540) */
    var width: Int? = null

    /** Window height. Default: null (electron-builder uses 380) */
    var height: Int? = null
}

data class DmgContentEntry(
    val x: Int,
    val y: Int,
    val type: DmgContentType? = null,
    val name: String? = null,
    val path: String? = null,
)

/** Type of content entry in DMG layout. */
enum class DmgContentType(
    val id: String,
) {
    Link("link"),
    File("file"),
    Dir("dir"),
}

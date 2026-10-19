/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/** Snap package options, passed to electron-builder's `snap` target. */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class SnapSettings {
    /**
     * Snap name used in `meta/snap.yaml` and the Snap Store namespace.
     *
     * Defaults to the app's `packageName` when null. Set this when the Snap Store
     * registration uses a name distinct from `packageName`.
     *
     * Note: electron-builder 26.x derives the snap name from the Linux `executableName`,
     * so overriding this also renames the snap's launcher command and desktop entry.
     * Must be a valid snap name (lowercase letters, digits and hyphens).
     */
    @get:Input
    @get:Optional
    var name: String? = null

    /** Confinement level. Default: [SnapConfinement.Strict] */
    @get:Input
    var confinement: SnapConfinement = SnapConfinement.Strict

    /** Quality grade. Default: [SnapGrade.Stable] */
    @get:Input
    var grade: SnapGrade = SnapGrade.Stable

    /** Short summary (max 78 chars) */
    @get:Input
    @get:Optional
    var summary: String? = null

    /** Base snap. Default: "core22" */
    @get:Input
    @get:Optional
    var base: String? = null

    /** Snap interfaces (plugs) */
    @get:Input
    var plugs: List<SnapPlug> =
        listOf(
            SnapPlug.Desktop,
            SnapPlug.DesktopLegacy,
            SnapPlug.Home,
            SnapPlug.X11,
            SnapPlug.Wayland,
            SnapPlug.Unity7,
            SnapPlug.BrowserSupport,
            SnapPlug.Network,
            SnapPlug.Gsettings,
            SnapPlug.AudioPlayback,
            SnapPlug.Opengl,
        )

    /** Auto-start on login. Default: false */
    @get:Input
    var autoStart: Boolean = false

    /** Compression algorithm. Default: null (uses snap default) */
    @get:Input
    @get:Optional
    var compression: SnapCompression? = null
}

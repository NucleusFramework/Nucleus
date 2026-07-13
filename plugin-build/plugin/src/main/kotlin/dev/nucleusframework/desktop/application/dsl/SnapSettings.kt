/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

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
    var name: String? = null

    /** Confinement level. Default: [SnapConfinement.Strict] */
    var confinement: SnapConfinement = SnapConfinement.Strict

    /** Quality grade. Default: [SnapGrade.Stable] */
    var grade: SnapGrade = SnapGrade.Stable

    /** Short summary (max 78 chars) */
    var summary: String? = null

    /** Base snap. Default: "core22" */
    var base: String? = null

    /** Snap interfaces (plugs) */
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
    var autoStart: Boolean = false

    /** Compression algorithm. Default: null (uses snap default) */
    var compression: SnapCompression? = null
}

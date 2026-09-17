/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

abstract class MsiSettings {
    /**
     * Install per-machine (Program Files, all users). Default: true.
     * Mirrors [NsisSettings.perMachine] (maps 1:1 to electron-builder `msi.perMachine`).
     */
    var perMachine: Boolean
        get() = explicitPerMachine ?: true
        set(value) {
            explicitPerMachine = value
        }

    // Tracks whether the user set the value explicitly, so the deprecated
    // windows.perUserInstall flag can still take effect when this one is untouched.
    internal var explicitPerMachine: Boolean? = null

    /**
     * One-click installer: install immediately without showing a wizard. Default: true.
     *
     * Set to `false` for an assisted installer that shows the usual welcome/progress/finish
     * pages, which is what jpackage-produced MSI packages did.
     */
    var oneClick: Boolean = true

    /** Run the app once the installer finishes. Default: true */
    var runAfterFinish: Boolean = true

    /** Create a desktop shortcut. Default: true */
    var createDesktopShortcut: Boolean = true

    /** Create a start menu shortcut. Default: true */
    var createStartMenuShortcut: Boolean = true

    /**
     * Start menu submenu (and program files subdirectory) holding the shortcut.
     *
     * `null` (default) puts the shortcut directly under the start menu root. When left unset,
     * [WindowsPlatformSettings.menuGroup] is used instead, so the group declared for
     * jpackage-based packaging keeps working on the MSI target.
     */
    var menuCategory: String? = null

    /** Name used for the shortcuts. Defaults to the application name. */
    var shortcutName: String? = null
}

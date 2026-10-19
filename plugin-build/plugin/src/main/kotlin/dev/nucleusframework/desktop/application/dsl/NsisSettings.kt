/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/** Windows NSIS installer options, passed to electron-builder's `nsis` target. */
abstract class NsisSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** One-click installer (no wizard). Default: true */
    @get:Input
    var oneClick: Boolean = true

    /** Allow elevation to admin. Default: false */
    @get:Input
    var allowElevation: Boolean = false

    /** Install per-machine (all users). Default: false */
    @get:Input
    var perMachine: Boolean = false

    /** Allow user to change install directory. Default: false */
    @get:Input
    var allowToChangeInstallationDirectory: Boolean = false

    /** Create desktop shortcut. Default: true */
    @get:Input
    var createDesktopShortcut: Boolean = true

    /** Create start menu shortcut. Default: true */
    @get:Input
    var createStartMenuShortcut: Boolean = true

    /**
     * Start menu submenu (and program files subdirectory) holding the shortcut.
     *
     * `null` (default) puts the shortcut directly under the start menu root. When left unset,
     * [WindowsPlatformSettings.menuGroup] is used instead, so the group declared for
     * jpackage-based packaging keeps working on the NSIS targets. Mirrors [MsiSettings.menuCategory].
     */
    @get:Input
    @get:Optional
    var menuCategory: String? = null

    /** Name used for the shortcuts. Defaults to the application name. */
    @get:Input
    @get:Optional
    var shortcutName: String? = null

    /** Run app after install finishes. Default: true */
    @get:Input
    var runAfterFinish: Boolean = true

    /** Installer icon file (.ico) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val installerIcon: RegularFileProperty = objects.fileProperty()

    /** Uninstaller icon file (.ico) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val uninstallerIcon: RegularFileProperty = objects.fileProperty()

    /** License file (.txt, .rtf, .html) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val license: RegularFileProperty = objects.fileProperty()

    /** Custom NSIS include script (.nsh) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val includeScript: RegularFileProperty = objects.fileProperty()

    /** Custom NSIS script (.nsi) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val script: RegularFileProperty = objects.fileProperty()

    /** Delete app data on uninstall. Default: false */
    @get:Input
    var deleteAppDataOnUninstall: Boolean = false

    /** Multi-language installer. Default: false */
    @get:Input
    var multiLanguageInstaller: Boolean = false

    /** Installer languages (e.g., "en_US", "de_DE") */
    @get:Input
    var installerLanguages: List<String> = emptyList()

    /** Installer header image (assisted/wizard only) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val installerHeader: RegularFileProperty = objects.fileProperty()

    /** Installer sidebar bitmap (assisted/wizard only) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val installerSidebar: RegularFileProperty = objects.fileProperty()
}

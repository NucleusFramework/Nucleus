/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class NsisSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** One-click installer (no wizard). Default: true */
    var oneClick: Boolean = true

    /** Allow elevation to admin. Default: false */
    var allowElevation: Boolean = false

    /** Install per-machine (all users). Default: false */
    var perMachine: Boolean = false

    /** Allow user to change install directory. Default: false */
    var allowToChangeInstallationDirectory: Boolean = false

    /** Create desktop shortcut. Default: true */
    var createDesktopShortcut: Boolean = true

    /** Create start menu shortcut. Default: true */
    var createStartMenuShortcut: Boolean = true

    /** Run app after install finishes. Default: true */
    var runAfterFinish: Boolean = true

    /** Installer icon file (.ico) */
    val installerIcon: RegularFileProperty = objects.fileProperty()

    /** Uninstaller icon file (.ico) */
    val uninstallerIcon: RegularFileProperty = objects.fileProperty()

    /** License file (.txt, .rtf, .html) */
    val license: RegularFileProperty = objects.fileProperty()

    /** Custom NSIS include script (.nsh) */
    val includeScript: RegularFileProperty = objects.fileProperty()

    /** Custom NSIS script (.nsi) */
    val script: RegularFileProperty = objects.fileProperty()

    /** Delete app data on uninstall. Default: false */
    var deleteAppDataOnUninstall: Boolean = false

    /** Multi-language installer. Default: false */
    var multiLanguageInstaller: Boolean = false

    /** Installer languages (e.g., "en_US", "de_DE") */
    var installerLanguages: List<String> = emptyList()

    /** Installer header image (assisted/wizard only) */
    val installerHeader: RegularFileProperty = objects.fileProperty()

    /** Installer sidebar bitmap (assisted/wizard only) */
    val installerSidebar: RegularFileProperty = objects.fileProperty()
}

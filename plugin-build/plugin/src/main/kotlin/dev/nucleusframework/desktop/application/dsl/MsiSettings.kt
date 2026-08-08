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
}

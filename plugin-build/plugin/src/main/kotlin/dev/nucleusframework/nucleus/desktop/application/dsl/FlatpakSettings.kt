/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class FlatpakSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Freedesktop runtime. Default: "org.freedesktop.Platform" */
    var runtime: String = "org.freedesktop.Platform"

    /** Runtime version. Default: "23.08" */
    var runtimeVersion: String = "23.08"

    /** SDK. Default: "org.freedesktop.Sdk" */
    var sdk: String = "org.freedesktop.Sdk"

    /** Branch name. Default: "master" */
    var branch: String = "master"

    /** finish-args for sandboxing */
    var finishArgs: List<String> =
        listOf(
            "--share=ipc",
            "--socket=x11",
            "--socket=wayland",
            "--socket=pulseaudio",
            "--device=dri",
        )

    /** License file */
    val license: RegularFileProperty = objects.fileProperty()
}

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

/** Flatpak packaging options: runtime, SDK, branch, sandbox `finish-args` and license. */
abstract class FlatpakSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Freedesktop runtime. Default: "org.freedesktop.Platform" */
    @get:Input
    var runtime: String = "org.freedesktop.Platform"

    /** Runtime version. Default: "23.08" */
    @get:Input
    var runtimeVersion: String = "23.08"

    /** SDK. Default: "org.freedesktop.Sdk" */
    @get:Input
    var sdk: String = "org.freedesktop.Sdk"

    /** Branch name. Default: "master" */
    @get:Input
    var branch: String = "master"

    /** finish-args for sandboxing */
    @get:Input
    var finishArgs: List<String> =
        listOf(
            "--share=ipc",
            "--socket=x11",
            "--socket=wayland",
            "--socket=pulseaudio",
            "--device=dri",
        )

    /** License file */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val license: RegularFileProperty = objects.fileProperty()
}

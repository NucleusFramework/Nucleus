/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import java.util.*
import javax.inject.Inject

/**
 * Settings shared by every application distribution: identity metadata (name, version, vendor...),
 * bundled resources and the package formats to build.
 */
abstract class AbstractDistributions {
    @get:Inject
    internal abstract val objects: ObjectFactory

    @get:Inject
    internal abstract val layout: ProjectLayout

    @get:Internal
    val outputBaseDir: DirectoryProperty =
        objects.directoryProperty().apply {
            set(layout.buildDirectory.dir("compose/binaries"))
        }

    @get:Input
    @get:Optional
    var appName: String? = null
    @get:Input
    @get:Optional
    var packageName: String? = null
    @get:Input
    @get:Optional
    var packageVersion: String? = null
    @get:Input
    @get:Optional
    var copyright: String? = null

    @get:Input
    @get:Optional
    var description: String? = null
    @get:Input
    @get:Optional
    var vendor: String? = null
    @get:Input
    @get:Optional
    var homepage: String? = null
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val appResourcesRootDir: DirectoryProperty = objects.directoryProperty()
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val licenseFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    var targetFormats: Set<TargetFormat> = EnumSet.noneOf(TargetFormat::class.java)

    /** Sets the package formats to build, replacing any previously configured ones. */
    open fun targetFormats(vararg formats: TargetFormat) {
        targetFormats = EnumSet.copyOf(formats.toList())
    }
}

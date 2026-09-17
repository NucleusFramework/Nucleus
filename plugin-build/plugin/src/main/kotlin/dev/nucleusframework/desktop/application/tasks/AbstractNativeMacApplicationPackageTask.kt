/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.*
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "Depends on external macOS native tools")
abstract class AbstractNativeMacApplicationPackageTask : AbstractNucleusTask() {
    @get:Input
    val packageName: Property<String> = objects.notNullProperty()

    /**
     * Name of the `.app` bundle directory, without the `.app` extension.
     *
     * Kept separate from [packageName], which still names the launcher inside `Contents/MacOS`.
     * Defaults to [packageName] when unset.
     */
    @get:Input
    val bundleName: Property<String> = objects.notNullProperty<String>().convention(packageName)

    @get:Input
    val packageVersion: Property<String> = objects.notNullProperty("1.0.0")

    @get:Internal
    internal val fullPackageName: Provider<String> =
        project.provider { "${packageName.get()}-${packageVersion.get()}" }

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:LocalState
    val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/$name")

    @TaskAction
    fun run() {
        fileOperations.clearDirs(destinationDir, workingDir)

        createPackage(
            destinationDir = destinationDir.ioFile,
            workingDir = workingDir.ioFile,
        )
    }

    protected abstract fun createPackage(
        destinationDir: File,
        workingDir: File,
    )
}

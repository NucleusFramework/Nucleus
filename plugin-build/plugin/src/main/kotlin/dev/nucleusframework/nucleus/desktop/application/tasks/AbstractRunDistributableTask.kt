/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentOS
import dev.nucleusframework.nucleus.internal.utils.executableName
import dev.nucleusframework.nucleus.internal.utils.ioFile
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

// Custom task is used instead of Exec, because Exec does not support
// lazy configuration yet. Lazy configuration is needed to
// calculate appImageDir after the evaluation of createApplicationImage
@DisableCachingByDefault(because = "Runs the application, not a cacheable build step")
abstract class AbstractRunDistributableTask
    @Inject
    constructor(
        createApplicationImage: TaskProvider<AbstractJPackageTask>,
    ) : AbstractNucleusTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        internal val appImageRootDir: Provider<Directory> = createApplicationImage.flatMap { it.destinationDir }

        @get:Input
        internal val packageName: Provider<String> = createApplicationImage.flatMap { it.packageName }

        @TaskAction
        fun run() {
            val appDir =
                appImageRootDir.ioFile.let { appImageRoot ->
                    val files =
                        appImageRoot
                            .listFiles()
                            // Sometimes ".DS_Store" files are created on macOS, so ignore them.
                            ?.filterNot { it.name == ".DS_Store" }
                    if (files == null || files.isEmpty()) {
                        error("Could not find application image: $appImageRoot is empty!")
                    } else if (files.size > 1) {
                        error(
                            "Could not find application image: $appImageRoot " +
                                "contains multiple children [${files.joinToString(", ")}]",
                        )
                    } else {
                        files.single()
                    }
                }
            val appExecutableName = executableName(packageName.get())
            val (workingDir, executable) =
                when (currentOS) {
                    OS.Linux -> appDir to "bin/$appExecutableName"
                    OS.Windows -> appDir to appExecutableName
                    OS.MacOS -> appDir.resolve("Contents") to "MacOS/$appExecutableName"
                }

            execOperations
                .exec { spec ->
                    spec.workingDir(workingDir)
                    spec.executable(workingDir.resolve(executable).absolutePath)
                }.assertNormalExitValue()
        }
    }

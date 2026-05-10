/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.tasks

import dev.nucleusframework.nucleus.desktop.application.internal.NucleusProperties
import dev.nucleusframework.nucleus.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.nucleus.internal.utils.clearDirs
import dev.nucleusframework.nucleus.internal.utils.delete
import dev.nucleusframework.nucleus.internal.utils.ioFile
import dev.nucleusframework.nucleus.internal.utils.jvmToolFile
import dev.nucleusframework.nucleus.internal.utils.notNullProperty
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecResult
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.InputChanges
import java.io.File

@DisableCachingByDefault(because = "Depends on external JVM tools")
abstract class AbstractJvmToolOperationTask(
    private val toolName: String,
) : AbstractNucleusTask() {
    @get:LocalState
    protected val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/$name")

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val freeArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Internal
    val javaHome: Property<String> =
        objects.notNullProperty<String>().apply {
            set(providers.systemProperty("java.home"))
        }

    protected open fun prepareWorkingDir(inputChanges: InputChanges) {
        fileOperations.clearDirs(workingDir)
    }

    protected open fun makeArgs(tmpDir: File): MutableList<String> =
        arrayListOf<String>().apply {
            freeArgs.orNull?.forEach { add(it) }
        }

    protected open fun jvmToolEnvironment(): MutableMap<String, String> = HashMap()

    protected open fun checkResult(result: ExecResult) {
        result.assertNormalExitValue()
    }

    @TaskAction
    fun run(inputChanges: InputChanges) {
        initState()

        val jtool = jvmToolFile(toolName, javaHome = javaHome)

        fileOperations.delete(destinationDir)
        prepareWorkingDir(inputChanges)
        val argsFile =
            workingDir.ioFile.let { dir ->
                val args = makeArgs(dir)
                dir.resolveSibling("$name.args.txt").apply {
                    writeText(args.joinToString("\n"))
                }
            }

        try {
            runExternalTool(
                tool = jtool,
                args = listOf("@${argsFile.absolutePath}"),
                environment = jvmToolEnvironment(),
            ).also { checkResult(it) }
        } finally {
            if (!NucleusProperties.preserveWorkingDir(providers).get()) {
                fileOperations.delete(workingDir)
            }
        }
        saveStateAfterFinish()
    }

    protected open fun initState() {}

    protected open fun saveStateAfterFinish() {}
}

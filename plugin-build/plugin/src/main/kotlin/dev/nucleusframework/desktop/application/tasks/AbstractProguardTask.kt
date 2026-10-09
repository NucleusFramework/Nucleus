/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.ConsumerProguardRules
import dev.nucleusframework.desktop.application.internal.ExternalToolRunner
import dev.nucleusframework.desktop.application.internal.cliArg
import dev.nucleusframework.desktop.application.internal.files.mangledName
import dev.nucleusframework.desktop.application.internal.files.normalizedPath
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.clearDirs
import dev.nucleusframework.internal.utils.ioFile
import dev.nucleusframework.internal.utils.jvmToolFile
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.Writer
import kotlin.collections.LinkedHashMap

/**
 * Shrinks, optimizes and obfuscates the application JARs with ProGuard: every input JAR is mapped to an output
 * JAR in [destinationDir] (or joined into the main JAR with [joinOutputJars]), non-JAR inputs are copied as is,
 * and ProGuard runs on a generated root configuration that keeps [mainClass]'s `main` and includes the default
 * Compose rules, the consumer rules of the input JARs ([consumerRules]) and [configurationFiles].
 */
@DisableCachingByDefault(because = "Depends on external ProGuard tool")
abstract class AbstractProguardTask : AbstractNucleusTask() {
    @get:InputFiles
    @get:Classpath
    val inputFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val mainJar: RegularFileProperty = objects.fileProperty()

    @get:Internal
    val mainJarBaseName: Property<String> = objects.property(String::class.java)

    @get:Internal
    internal val mainJarInDestinationDir: Provider<RegularFile>
        get() = destinationDir.file(mainJarBaseName)

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val configurationFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Optional
    @get:Input
    val dontobfuscate: Property<Boolean> = objects.nullableProperty()

    @get:Optional
    @get:Input
    val dontoptimize: Property<Boolean> = objects.nullableProperty()

    @get:Optional
    @get:Input
    val joinOutputJars: Property<Boolean> = objects.nullableProperty()

    // todo: DSL for excluding default rules
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val defaultComposeRulesFile: RegularFileProperty = objects.fileProperty()

    /** Apply the keep rules the input JARs ship under `META-INF/proguard/` ([ConsumerProguardRules]). */
    @get:Input
    val consumerRules: Property<Boolean> = objects.notNullProperty(false)

    /** Dependencies whose embedded rules are left out: `group:module` or a project path, `*` wildcards. */
    @get:Input
    val consumerRulesExclusions: SetProperty<String> = objects.setProperty(String::class.java)

    /**
     * `group:module` (or project path) of each input JAR, keyed by absolute path, to match
     * [consumerRulesExclusions]. Derived from [inputFiles], so not an input of its own.
     */
    @get:Internal
    val artifactCoordinates: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)

    @get:Input
    val proguardVersion: Property<String> = objects.notNullProperty()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val proguardFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    val javaHome: Property<String> = objects.notNullProperty(System.getProperty("java.home"))

    @get:Input
    val mainClass: Property<String> = objects.notNullProperty()

    @get:Internal
    val maxHeapSize: Property<String> = objects.nullableProperty()

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:LocalState
    protected val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/$name")

    private val rootConfigurationFile = workingDir.map { it.file("root-config.pro") }

    private val jarsConfigurationFile = workingDir.map { it.file("jars-config.pro") }

    private val consumerRulesDir = workingDir.map { it.dir("consumer-rules") }

    /** Writes the ProGuard configuration and runs ProGuard into [destinationDir]. */
    @TaskAction
    fun execute() {
        val javaHome = File(javaHome.get())

        fileOperations.clearDirs(destinationDir, workingDir)
        val destinationDir = destinationDir.ioFile.absoluteFile

        // todo: can be cached for a jdk
        val libraryJarsConfig = jdkLibraryJarsConfig(javaHome)

        val inputToOutputJars = LinkedHashMap<File, File>()
        // avoid mangling mainJar
        inputToOutputJars[mainJar.ioFile] = mainJarInDestinationDir.ioFile
        for (inputFile in inputFiles) {
            if (inputFile.name.endsWith(".jar", ignoreCase = true)) {
                inputToOutputJars.putIfAbsent(inputFile, destinationDir.resolve(inputFile.mangledName()))
            } else {
                inputFile.copyTo(destinationDir.resolve(inputFile.name))
            }
        }

        jarsConfigurationFile.ioFile.bufferedWriter().use { writer ->
            val toSingleOutputJar = joinOutputJars.orNull == true
            for ((input, output) in inputToOutputJars.entries) {
                writer.writeLn("-injars '${input.normalizedPath()}'")
                if (!toSingleOutputJar) {
                    writer.writeLn("-outjars '${output.normalizedPath()}'")
                }
            }
            if (toSingleOutputJar) {
                writer.writeLn("-outjars '${mainJarInDestinationDir.ioFile.normalizedPath()}'")
            }

            for (libraryJar in libraryJarsConfig) {
                writer.writeLn(libraryJar)
            }
        }

        rootConfigurationFile.ioFile.bufferedWriter().use { writer ->
            if (dontobfuscate.orNull == true) {
                writer.writeLn("-dontobfuscate")
            }

            if (dontoptimize.orNull == true) {
                writer.writeLn("-dontoptimize")
            }

            writer.writeLn(
                """
                -keep public class ${mainClass.get()} {
                    public static void main(java.lang.String[]);
                }
                """.trimIndent(),
            )

            val includeFiles =
                sequenceOf(
                    jarsConfigurationFile.ioFile,
                    defaultComposeRulesFile.ioFile,
                ) + extractConsumerRules(inputToOutputJars.keys.toList()) + configurationFiles.files.asSequence()
            for (configFile in includeFiles.filterNotNull()) {
                writer.writeLn("-include '${configFile.normalizedPath()}'")
            }
        }

        val javaBinary = jvmToolFile(toolName = "java", javaHome = javaHome)
        val args =
            arrayListOf<String>().apply {
                val maxHeapSize = maxHeapSize.orNull
                if (maxHeapSize != null) {
                    add("-Xmx:$maxHeapSize")
                }
                cliArg("-cp", proguardFiles.map { it.normalizedPath() }.joinToString(File.pathSeparator))
                add("proguard.ProGuard")
                // todo: consider separate flag
                cliArg("-verbose", verbose)
                cliArg("-include", rootConfigurationFile)
            }

        runExternalTool(
            tool = javaBinary,
            args = args,
            environment = emptyMap(),
            logToConsole = ExternalToolRunner.LogToConsole.Always,
        ).assertNormalExitValue()
    }

    private fun extractConsumerRules(jars: List<File>): Sequence<File> {
        if (!consumerRules.get()) return emptySequence()
        val exclusions = consumerRulesExclusions.get()
        val coordinates = artifactCoordinates.get()
        if (exclusions.isNotEmpty() && coordinates.isEmpty()) {
            logger.warn(
                "w: proguard.consumerRulesExclusions ignored: no Gradle coordinates are known for the input " +
                    "JARs, so none can be matched (a fromFiles application, or no external dependency).",
            )
        }
        val matched = HashSet<String>()
        val result =
            ConsumerProguardRules.extract(jars, consumerRulesDir.ioFile) { jar ->
                val coordinate = coordinates[jar.absoluteFile.normalize().path]
                val hits = ConsumerProguardRules.matchingExclusions(coordinate, exclusions)
                matched += hits
                hits.isNotEmpty()
            }
        if (coordinates.isNotEmpty()) {
            for (pattern in exclusions - matched) {
                logger.warn("w: proguard.consumerRulesExclusions: '$pattern' matches no input JAR.")
            }
        }
        for (skipped in result.skipped) {
            logger.warn(
                "w: ProGuard rules '${skipped.entry}' of '${skipped.jar.name}' not applied (${skipped.reason}). " +
                    "Add the rules you need to proguard.configurationFiles.",
            )
        }
        logger.info("Applying ${result.files.size} ProGuard rule file(s) embedded in the input JARs")
        return result.files.asSequence()
    }

    /**
     * `-libraryjars` lines covering the JDK's own classes.
     *
     * Modular JDKs ship them as jmod files under `jmods`, but distributions built with run-time image linking
     * (JEP 493, JDK 25+) drop that directory entirely — Temurin 25 is one of them. With no library
     * jars ProGuard cannot resolve even `java.lang.Object` and aborts after several hundred thousand
     * "can't find referenced class" warnings, so fall back to extracting the run-time image with
     * `jimage` and hand ProGuard one class root per module.
     */
    private fun jdkLibraryJarsConfig(javaHome: File): List<String> {
        val jmodsDir = javaHome.resolve("jmods")
        val jmods =
            jmodsDir
                .walk()
                .filter {
                    it.isFile && it.path.endsWith("jmod", ignoreCase = true)
                }.toList()
        if (jmods.isNotEmpty()) {
            return jmods.map { "-libraryjars '${it.normalizedPath()}'(!**.jar;!module-info.class)" }
        }

        val runtimeImage = javaHome.resolve("lib").resolve("modules")
        check(runtimeImage.isFile) {
            "Cannot resolve the JDK classes required by ProGuard: neither '$jmodsDir' nor " +
                "'$runtimeImage' exists. Point the application's javaHome at a JDK."
        }

        val modulesDir = workingDir.ioFile.resolve("jdk-modules")
        runExternalTool(
            tool = jvmToolFile(toolName = "jimage", javaHome = javaHome),
            args = listOf("extract", "--dir", modulesDir.normalizedPath(), runtimeImage.normalizedPath()),
        ).assertNormalExitValue()

        val moduleDirs = modulesDir.listFiles().orEmpty().filter { it.isDirectory }
        check(moduleDirs.isNotEmpty()) {
            "Extracting '$runtimeImage' produced no modules in '$modulesDir'."
        }
        return moduleDirs.map { "-libraryjars '${it.normalizedPath()}'(!module-info.class,**.class)" }
    }

    private fun Writer.writeLn(s: String) {
        write(s)
        write("\n")
    }
}

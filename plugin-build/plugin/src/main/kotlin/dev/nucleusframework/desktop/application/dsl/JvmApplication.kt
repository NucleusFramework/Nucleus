/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

/**
 * The `application { }` block of a JVM desktop application: where its runtime classpath comes from,
 * how it is launched, and how it is packaged ([nativeDistributions], [buildTypes], [graalvm]).
 */
abstract class JvmApplication {
    /** Takes the application's runtime classpath from the Gradle source set [from]. */
    abstract fun from(from: SourceSet)

    /**
     * Takes the application's runtime classpath from the Kotlin Multiplatform JVM target [from];
     * any other kind of target fails.
     */
    abstract fun from(from: KotlinTarget)

    /**
     * Disables the automatic classpath setup (the project's JVM target or main source set);
     * the classpath must then be given with [from] or [fromFiles].
     */
    abstract fun disableDefaultConfiguration()

    /** Makes the application tasks depend on [tasks]. */
    abstract fun dependsOn(vararg tasks: Task)

    /** Makes the application tasks depend on the tasks at the given paths. */
    abstract fun dependsOn(vararg tasks: String)

    /** Adds [files] (resolved as by `Project.files`) to the application's runtime JARs. */
    abstract fun fromFiles(vararg files: Any)

    abstract var mainClass: String?
    abstract val mainJar: RegularFileProperty
    abstract var javaHome: String
    abstract val args: MutableList<String>

    /** Appends [args] to the arguments passed to the main class. */
    abstract fun args(vararg args: String)

    abstract val jvmArgs: MutableList<String>

    /** Appends [jvmArgs] to the JVM options of the packaged launcher and the `run` task. */
    abstract fun jvmArgs(vararg jvmArgs: String)

    /**
     * HotSpot garbage collector for the JVM distribution and the `run` task.
     * `null` (the default) leaves the choice to JVM ergonomics. See [GarbageCollector].
     */
    abstract var garbageCollector: GarbageCollector?

    /**
     * Master switch for the desktop startup pack: Serial GC, compact heap
     * (`-Xms32m`, `-XX:MaxRAMPercentage=25`), a single JAR in the jpackage
     * image, idle GC (3s after last unfocus, immediately on minimize), and
     * the current OpenJDK as the jpackage / jlink / `run` JDK (auto-downloaded,
     * like the GraalVM toolchain). Idle GC also applies to GraalVM native images.
     *
     * `true` turns on every knob still unset in the [nucleusOptimization]
     * configure block. An explicit [garbageCollector], [javaHome], or `-Xms` /
     * `-XX:MaxRAMPercentage` in [jvmArgs] is left unchanged.
     *
     * Does not enable AOT; set [JvmApplicationDistributions.enableAotCache]
     * separately. Does not change the Gradle compile JDK.
     */
    abstract var nucleusOptimization: Boolean

    /**
     * Per-knob overrides for [nucleusOptimization]. `null` follows the master
     * boolean; `true` / `false` force that piece on or off.
     *
     * ```
     * nucleusOptimization = true
     * nucleusOptimization { idleGc = false }
     *
     * nucleusOptimization { singleJar = true }
     * ```
     */
    abstract fun nucleusOptimization(fn: Action<NucleusOptimizationSettings>)

    /**
     * Debug overlays and logs for the `run` task only — see [NucleusDebugSettings].
     *
     * ```
     * debug {
     *     partialRedraw { tint = true }
     *     recomposition { enabled = true }
     * }
     * ```
     */
    abstract val debug: NucleusDebugSettings

    /** Configures [debug]. */
    abstract fun debug(fn: Action<NucleusDebugSettings>)

    abstract val nativeDistributions: JvmApplicationDistributions

    /** Configures the native packages built for the application. */
    abstract fun nativeDistributions(fn: Action<JvmApplicationDistributions>)

    abstract val buildTypes: JvmApplicationBuildTypes

    /** Configures the build types (the ProGuard-processed `release` build). */
    abstract fun buildTypes(fn: Action<JvmApplicationBuildTypes>)

    abstract val graalvm: GraalvmSettings

    /** Configures the GraalVM native image build. */
    abstract fun graalvm(fn: Action<GraalvmSettings>)

    abstract val additionalLaunchers: NamedDomainObjectContainer<AdditionalLauncher>

    /** Declares extra launchers packaged next to the main one, each with its own main class and arguments. */
    abstract fun additionalLaunchers(action: Action<NamedDomainObjectContainer<AdditionalLauncher>>)
}

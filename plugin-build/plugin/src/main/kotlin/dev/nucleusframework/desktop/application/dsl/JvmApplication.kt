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

abstract class JvmApplication {
    abstract fun from(from: SourceSet)

    abstract fun from(from: KotlinTarget)

    abstract fun disableDefaultConfiguration()

    abstract fun dependsOn(vararg tasks: Task)

    abstract fun dependsOn(vararg tasks: String)

    abstract fun fromFiles(vararg files: Any)

    abstract var mainClass: String?
    abstract val mainJar: RegularFileProperty
    abstract var javaHome: String
    abstract val args: MutableList<String>

    abstract fun args(vararg args: String)

    abstract val jvmArgs: MutableList<String>

    abstract fun jvmArgs(vararg jvmArgs: String)

    /**
     * HotSpot garbage collector for the JVM distribution and the `run` task.
     * `null` (the default) leaves the choice to JVM ergonomics. See [GarbageCollector].
     */
    abstract var garbageCollector: GarbageCollector?

    /**
     * Master switch for the desktop startup pack: Serial GC, compact heap
     * (`-Xms32m`, `-XX:MaxRAMPercentage=25`), a single JAR in the jpackage
     * image, and idle GC (3s after last unfocus, immediately on minimize).
     *
     * `true` turns on every knob still unset in the [nucleusOptimization]
     * configure block. An explicit [garbageCollector] or `-Xms` /
     * `-XX:MaxRAMPercentage` in [jvmArgs] is left unchanged.
     *
     * Does not enable AOT; set [JvmApplicationDistributions.enableAotCache]
     * separately.
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

    abstract val nativeDistributions: JvmApplicationDistributions

    abstract fun nativeDistributions(fn: Action<JvmApplicationDistributions>)

    abstract val buildTypes: JvmApplicationBuildTypes

    abstract fun buildTypes(fn: Action<JvmApplicationBuildTypes>)

    abstract val graalvm: GraalvmSettings

    abstract fun graalvm(fn: Action<GraalvmSettings>)

    abstract val additionalLaunchers: NamedDomainObjectContainer<AdditionalLauncher>

    abstract fun additionalLaunchers(action: Action<NamedDomainObjectContainer<AdditionalLauncher>>)
}

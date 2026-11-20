/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import dev.nucleusframework.internal.utils.new
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * The build types of a JVM application: the default one and [release], which runs ProGuard
 * and registers its own `*Release*` tasks (e.g. `packageReleaseDmg`).
 */
abstract class JvmApplicationBuildTypes
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /**
         * The default build type does not have a classifier
         * to preserve compatibility with tasks, existing before
         * the introduction of the release build type,
         * e.g. we don't want to break existing packageDmg,
         * createDistributable tasks after the introduction
         * of packageReleaseDmg and createReleaseDistributable tasks.
         */
        internal val default: JvmApplicationBuildType = objects.new("")

        val release: JvmApplicationBuildType =
            objects.new<JvmApplicationBuildType>("release").apply {
                proguard.isEnabled.set(true)
            }

        /** Configures the [release] build type. */
        fun release(fn: Action<JvmApplicationBuildType>) {
            fn.execute(release)
        }
    }

/** Settings of one build type of a JVM application. */
abstract class JvmApplicationBuildType
    @Inject
    constructor(
        /**
         * A classifier distinguishes tasks and directories of one build type from another.
         * E.g. `release` build type produces packageReleaseDmg task.
         */
        internal val classifier: String,
        objects: ObjectFactory,
    ) {
        val proguard: ProguardSettings = objects.new()

        /** Configures ProGuard processing for this build type. */
        fun proguard(fn: Action<ProguardSettings>) {
            fn.execute(proguard)
        }
    }

/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

private const val DEFAULT_PROGUARD_VERSION = "7.10.0"

abstract class ProguardSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val version: Property<String> = objects.notNullProperty(DEFAULT_PROGUARD_VERSION)
        val maxHeapSize: Property<String> = objects.nullableProperty()
        val configurationFiles: ConfigurableFileCollection = objects.fileCollection()
        val isEnabled: Property<Boolean> = objects.notNullProperty(false)
        val obfuscate: Property<Boolean> = objects.notNullProperty(false)
        val optimize: Property<Boolean> = objects.notNullProperty(true)
        val joinOutputJars: Property<Boolean> = objects.notNullProperty(false)

        /**
         * Apply the keep rules dependencies ship under `META-INF/proguard/`, and keep the providers
         * their `META-INF/services` files declare, as R8 and the Android Gradle plugin do. A rule file
         * using anything but keep, attribute, warning or assumption options is skipped with a
         * warning. Off by default: it keeps
         * more than an existing configuration did, so an application opts in.
         */
        val consumerRules: Property<Boolean> = objects.notNullProperty(false)

        /**
         * Dependencies whose embedded rules are ignored, as `group:module` or a project path such as
         * `:shared`; `*` matches any run of characters (`com.squareup.*:*`). The providers their
         * `META-INF/services` files declare are still kept.
         */
        val consumerRulesExclusions: SetProperty<String> = objects.setProperty(String::class.java)

        /** Adds [notations] to [consumerRulesExclusions]. */
        fun excludeConsumerRules(vararg notations: String) {
            consumerRulesExclusions.addAll(*notations)
        }
    }

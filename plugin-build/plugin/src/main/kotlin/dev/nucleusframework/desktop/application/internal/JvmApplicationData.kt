/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import dev.nucleusframework.desktop.application.dsl.GraalvmSettings
import dev.nucleusframework.desktop.application.dsl.NucleusOptimizationSettings
import dev.nucleusframework.desktop.application.dsl.JvmApplicationBuildTypes
import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.internal.utils.new
import dev.nucleusframework.desktop.application.dsl.AdditionalLauncher
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

internal open class JvmApplicationData
    @Inject
    constructor(
        objects: ObjectFactory,
        private val providers: ProviderFactory,
    ) {
        var jvmApplicationRuntimeFilesProvider: JvmApplicationRuntimeFilesProvider? = null
        var isDefaultConfigurationEnabled: Boolean = true
        val fromFiles: ConfigurableFileCollection = objects.fileCollection()
        val dependenciesTaskNames: MutableList<String> = ArrayList()
        var mainClass: String? = null
        val mainJar: RegularFileProperty = objects.fileProperty()

        private var customJavaHome: String? = null
        var javaHome: String
            get() = customJavaHome ?: System.getProperty("java.home") ?: error("'java.home' system property is not set")
            set(value) {
                customJavaHome = value
            }
        val javaHomeProvider: Provider<String>
            get() = providers.provider { javaHome }
        val args: MutableList<String> = ArrayList()
        val jvmArgs: MutableList<String> = ArrayList()
        var garbageCollector: GarbageCollector? = null
        var nucleusOptimization: Boolean = false
        val nucleusOptimizationSettings: NucleusOptimizationSettings = objects.new()
        val nativeDistributions: JvmApplicationDistributions = objects.new()
        val buildTypes: JvmApplicationBuildTypes = objects.new()
        val graalvm: GraalvmSettings = objects.new()
        val additionalLaunchers: NamedDomainObjectContainer<AdditionalLauncher> =
            objects.domainObjectContainer(AdditionalLauncher::class.java)
    }

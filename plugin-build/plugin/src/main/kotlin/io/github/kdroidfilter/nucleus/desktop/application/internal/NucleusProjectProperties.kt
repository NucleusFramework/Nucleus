/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

import dev.nucleusframework.nucleus.internal.utils.findLocalOrGlobalProperty
import dev.nucleusframework.nucleus.internal.utils.toBooleanProvider
import dev.nucleusframework.nucleus.internal.utils.valueOrNull
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

internal object NucleusProperties {
    internal const val VERBOSE = "compose.desktop.verbose"
    internal const val PRESERVE_WD = "compose.preserve.working.dir"
    internal const val MAC_SIGN = "compose.desktop.mac.sign"
    internal const val MAC_SIGN_ID = "compose.desktop.mac.signing.identity"
    internal const val MAC_SIGN_KEYCHAIN = "compose.desktop.mac.signing.keychain"
    internal const val MAC_SIGN_PREFIX = "compose.desktop.mac.signing.prefix"
    internal const val MAC_NOTARIZATION_APPLE_ID = "compose.desktop.mac.notarization.appleID"
    internal const val MAC_NOTARIZATION_PASSWORD = "compose.desktop.mac.notarization.password"
    internal const val MAC_NOTARIZATION_TEAM_ID_PROVIDER = "compose.desktop.mac.notarization.teamID"
    internal const val CHECK_JDK_VENDOR = "compose.desktop.packaging.checkJdkVendor"
    internal const val DISABLE_MULTIMODULE_RESOURCES = "org.jetbrains.compose.resources.multimodule.disable"
    internal const val SYNC_RESOURCES_PROPERTY = "compose.ios.resources.sync"
    internal const val DISABLE_RESOURCE_CONTENT_HASH_GENERATION = "org.jetbrains.compose.resources.content.hash.generation.disable"
    internal const val ELECTRON_BUILDER_NODE_PATH = "compose.electronBuilder.nodePath"
    internal const val ELECTRON_BUILDER_PUBLISH_MODE = "compose.electronBuilder.publishMode"

    fun isVerbose(providers: ProviderFactory): Provider<Boolean> = providers.valueOrNull(VERBOSE).toBooleanProvider(false)

    fun preserveWorkingDir(providers: ProviderFactory): Provider<Boolean> = providers.valueOrNull(PRESERVE_WD).toBooleanProvider(false)

    fun macSign(providers: ProviderFactory): Provider<Boolean> = providers.valueOrNull(MAC_SIGN).toBooleanProvider(false)

    fun macSignIdentity(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_ID)

    fun macSignKeychain(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_KEYCHAIN)

    fun macSignPrefix(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_PREFIX)

    @Suppress("MaxLineLength")
    fun macNotarizationAppleID(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_NOTARIZATION_APPLE_ID)

    @Suppress("MaxLineLength")
    fun macNotarizationPassword(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_NOTARIZATION_PASSWORD)

    @Suppress("MaxLineLength")
    fun macNotarizationTeamID(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_NOTARIZATION_TEAM_ID_PROVIDER)

    fun checkJdkVendor(providers: ProviderFactory): Provider<Boolean> = providers.valueOrNull(CHECK_JDK_VENDOR).toBooleanProvider(true)

    fun disableMultimoduleResources(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(DISABLE_MULTIMODULE_RESOURCES).toBooleanProvider(false)

    fun disableResourceContentHashGeneration(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(DISABLE_RESOURCE_CONTENT_HASH_GENERATION).toBooleanProvider(false)

    @Suppress("MaxLineLength")
    fun electronBuilderNodePath(providers: ProviderFactory): Provider<String> = providers.valueOrNull(ELECTRON_BUILDER_NODE_PATH)

    @Suppress("MaxLineLength")
    fun electronBuilderPublishMode(providers: ProviderFactory): Provider<String> = providers.valueOrNull(ELECTRON_BUILDER_PUBLISH_MODE)

    // providers.valueOrNull works only with root gradle.properties
    fun dontSyncResources(project: Project): Provider<Boolean> =
        project.findLocalOrGlobalProperty(SYNC_RESOURCES_PROPERTY).map { it == "false" }
}

/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal.validation

import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSSigningSettings
import dev.nucleusframework.nucleus.desktop.application.internal.NucleusProperties
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.currentOS
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

internal data class ValidatedMacOSSigningSettings(
    val bundleID: String,
    val identity: String,
    val keychain: File?,
    val prefix: String,
    private val appStore: Boolean,
) {
    val fullDeveloperID: String
        get() {
            val developerIdPrefix = "Developer ID Application: "
            val thirdPartyMacDeveloperPrefix = "3rd Party Mac Developer Application: "
            return when {
                identity.startsWith(developerIdPrefix) -> identity
                identity.startsWith(thirdPartyMacDeveloperPrefix) -> identity
                else -> (if (!appStore) developerIdPrefix else thirdPartyMacDeveloperPrefix) + identity
            }
        }

    /** Identity with all known certificate-type prefixes stripped. */
    val bareIdentityName: String
        get() {
            val knownPrefixes =
                listOf(
                    "Developer ID Application: ",
                    "3rd Party Mac Developer Application: ",
                    "Developer ID Installer: ",
                    "3rd Party Mac Developer Installer: ",
                )
            return knownPrefixes
                .firstOrNull { identity.startsWith(it) }
                ?.let { identity.removePrefix(it) }
                ?: identity
        }

    /** Team ID extracted from the identity string, e.g. "NAME (XXXXXXX)" → "XXXXXXX". */
    val teamID: String?
        get() = TEAM_ID_REGEX.find(identity.trim())?.groupValues?.get(1)
}

internal fun MacOSSigningSettings.validate(
    bundleIDProvider: Provider<String>,
    project: Project,
    appStoreProvider: Provider<Boolean>,
): ValidatedMacOSSigningSettings {
    check(currentOS == OS.MacOS) { ERR_WRONG_OS }

    val bundleID = validateBundleID(bundleIDProvider)
    val signPrefix =
        this.prefix.orNull
            ?: (bundleID.substringBeforeLast(".") + ".").takeIf { bundleID.contains('.') }
            ?: error(ERR_UNKNOWN_PREFIX)
    val signIdentity =
        this.identity.orNull
            ?: error(ERR_UNKNOWN_SIGN_ID)
    val keychainPath = this.keychain.orNull
    val keychainFile =
        if (keychainPath != null) {
            val keychainFile =
                listOf(project.file(keychainPath), project.rootProject.file(keychainPath))
                    .firstOrNull { it.exists() }
            check(keychainFile != null) {
                "$ERR_PREFIX could not find the specified keychain: $keychainPath"
            }
            keychainFile
        } else {
            null
        }
    val appStore = appStoreProvider.orNull == true

    return ValidatedMacOSSigningSettings(
        bundleID = bundleID,
        identity = signIdentity,
        keychain = keychainFile,
        prefix = signPrefix,
        appStore = appStore,
    )
}

private const val ERR_PREFIX = "Signing settings error:"
private val ERR_WRONG_OS =
    "$ERR_PREFIX macOS was expected, actual OS is $currentOS"
private val ERR_UNKNOWN_PREFIX =
    """|$ERR_PREFIX Could not infer signing prefix. To specify:
       |  * Set bundleID to reverse DNS notation (e.g. "com.mycompany.myapp");
       |  * Use '${NucleusProperties.MAC_SIGN_PREFIX}' Gradle property;
       |  * Use 'nativeDistributions.macOS.signing.prefix' DSL property;
    """.trimMargin()
private val ERR_UNKNOWN_SIGN_ID =
    """|$ERR_PREFIX signing identity is null or empty. To specify:
       |  * Use '${NucleusProperties.MAC_SIGN_ID}' Gradle property;
       |  * Use 'nativeDistributions.macOS.signing.identity' DSL property;
    """.trimMargin()

private val TEAM_ID_REGEX = Regex("\\(([A-Z0-9]+)\\)\\s*$")

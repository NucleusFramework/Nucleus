/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal.validation

import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSNotarizationSettings
import dev.nucleusframework.nucleus.desktop.application.internal.NucleusProperties

internal sealed class NotarizationAuth {
    data class AppleId(
        val appleID: String,
        val password: String,
        val teamID: String,
    ) : NotarizationAuth()

    data class KeychainProfile(
        val profileName: String,
        val keychainPath: String?,
    ) : NotarizationAuth()

    data class ApiKey(
        val keyPath: String,
        val keyId: String,
        val issuerId: String,
    ) : NotarizationAuth()
}

internal data class ValidatedMacOSNotarizationSettings(val auth: NotarizationAuth)

/**
 * Builds the `notarytool` authentication arguments and an optional stdin payload
 * (the Apple ID password is fed via stdin to keep it off the command line).
 */
internal fun NotarizationAuth.toNotaryToolArgs(): Pair<List<String>, String?> =
    when (this) {
        is NotarizationAuth.AppleId ->
            listOf("--apple-id", appleID, "--team-id", teamID) to password
        is NotarizationAuth.KeychainProfile ->
            buildList {
                add("--keychain-profile")
                add(profileName)
                keychainPath?.let {
                    add("--keychain")
                    add(it)
                }
            } to null
        is NotarizationAuth.ApiKey ->
            listOf(
                "--key", keyPath,
                "--key-id", keyId,
                "--issuer", issuerId,
            ) to null
    }

internal fun MacOSNotarizationSettings?.validate(): ValidatedMacOSNotarizationSettings {
    checkNotNull(this) {
        ERR_NOTARIZATION_SETTINGS_ARE_NOT_PROVIDED
    }

    val appleId = appleID.orNull?.takeUnless { it.isEmpty() }
    val pwd = password.orNull?.takeUnless { it.isEmpty() }
    val team = teamID.orNull?.takeUnless { it.isEmpty() }
    val profile = keychainProfile.orNull?.takeUnless { it.isEmpty() }
    val keychainPathValue = keychainPath.orNull?.takeUnless { it.isEmpty() }
    val key = apiKey.orNull?.takeUnless { it.isEmpty() }
    val keyId = apiKeyId.orNull?.takeUnless { it.isEmpty() }
    val issuer = apiIssuer.orNull?.takeUnless { it.isEmpty() }

    val appleIdMode = appleId != null || pwd != null || team != null
    val keychainMode = profile != null
    val apiKeyMode = key != null || keyId != null || issuer != null

    val activeModes = listOf(appleIdMode, keychainMode, apiKeyMode).count { it }
    check(activeModes <= 1) {
        ERR_MUTUALLY_EXCLUSIVE
    }
    check(activeModes == 1) {
        ERR_NO_MODE_CONFIGURED
    }

    return when {
        apiKeyMode -> {
            checkNotNull(key) { ERR_API_KEY_IS_EMPTY }
            checkNotNull(keyId) { ERR_API_KEY_ID_IS_EMPTY }
            checkNotNull(issuer) { ERR_API_ISSUER_IS_EMPTY }
            ValidatedMacOSNotarizationSettings(
                NotarizationAuth.ApiKey(
                    keyPath = key,
                    keyId = keyId,
                    issuerId = issuer,
                ),
            )
        }
        profile != null -> {
            ValidatedMacOSNotarizationSettings(
                NotarizationAuth.KeychainProfile(
                    profileName = profile,
                    keychainPath = keychainPathValue,
                ),
            )
        }
        else -> {
            checkNotNull(appleId) { ERR_APPLE_ID_IS_EMPTY }
            checkNotNull(pwd) { ERR_PASSWORD_IS_EMPTY }
            checkNotNull(team) { ERR_TEAM_ID_IS_EMPTY }
            ValidatedMacOSNotarizationSettings(
                NotarizationAuth.AppleId(
                    appleID = appleId,
                    password = pwd,
                    teamID = team,
                ),
            )
        }
    }
}

private const val ERR_PREFIX = "Notarization settings error:"
private const val ERR_NOTARIZATION_SETTINGS_ARE_NOT_PROVIDED =
    "$ERR_PREFIX notarization settings are not provided"
private val ERR_NO_MODE_CONFIGURED =
    """|$ERR_PREFIX no authentication mode configured. Configure one of:
       |  * Apple ID mode: appleID + password + teamID
       |    (Gradle properties: ${NucleusProperties.MAC_NOTARIZATION_APPLE_ID},
       |     ${NucleusProperties.MAC_NOTARIZATION_PASSWORD},
       |     ${NucleusProperties.MAC_NOTARIZATION_TEAM_ID_PROVIDER});
       |  * Keychain profile mode: keychainProfile (created via 'xcrun notarytool store-credentials')
       |    (Gradle property: ${NucleusProperties.MAC_NOTARIZATION_KEYCHAIN_PROFILE});
       |  * App Store Connect API key mode: apiKey + apiKeyId + apiIssuer
       |    (Gradle properties: ${NucleusProperties.MAC_NOTARIZATION_API_KEY},
       |     ${NucleusProperties.MAC_NOTARIZATION_API_KEY_ID},
       |     ${NucleusProperties.MAC_NOTARIZATION_API_ISSUER});
    """.trimMargin()
private val ERR_MUTUALLY_EXCLUSIVE =
    """|$ERR_PREFIX appleID/keychainProfile/apiKey are mutually exclusive authentication modes.
       |Configure only one mode at a time.
    """.trimMargin()
private val ERR_APPLE_ID_IS_EMPTY =
    """|$ERR_PREFIX appleID is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_APPLE_ID}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.appleID' DSL property;
    """.trimMargin()
private val ERR_PASSWORD_IS_EMPTY =
    """|$ERR_PREFIX password is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_PASSWORD}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.password' DSL property;
    """.trimMargin()
private val ERR_TEAM_ID_IS_EMPTY =
    """|$ERR_PREFIX teamID is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_TEAM_ID_PROVIDER}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.teamID' DSL property;
    """.trimMargin()
private val ERR_API_KEY_IS_EMPTY =
    """|$ERR_PREFIX apiKey is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_API_KEY}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.apiKey' DSL property;
    """.trimMargin()
private val ERR_API_KEY_ID_IS_EMPTY =
    """|$ERR_PREFIX apiKeyId is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_API_KEY_ID}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.apiKeyId' DSL property;
    """.trimMargin()
private val ERR_API_ISSUER_IS_EMPTY =
    """|$ERR_PREFIX apiIssuer is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_API_ISSUER}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.apiIssuer' DSL property;
    """.trimMargin()

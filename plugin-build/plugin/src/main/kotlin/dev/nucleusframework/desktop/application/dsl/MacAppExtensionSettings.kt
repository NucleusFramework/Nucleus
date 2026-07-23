/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.Action
import java.io.File
import java.io.Serializable

/**
 * DSL block for embedding macOS app extensions (`.appex`) in the app bundle at
 * `Contents/PlugIns/`.
 *
 * Nucleus copies each extension into the bundle and signs it with its OWN
 * entitlements and provisioning profile, then seals the outer app without
 * `--deep` so the extension keeps its distinct signature. This is what a macOS
 * Network Extension needs (its own `com.apple.developer.networking.networkextension`
 * entitlement, its own App Group, its own `embedded.provisionprofile`).
 *
 * Nucleus does not build the `.appex` — build it with Xcode or Kotlin/Native and
 * point [MacAppExtension.appex] at the result.
 *
 * ```kotlin
 * macOS {
 *     appExtensions {
 *         extension("NetworkFilter") {
 *             appex(file("build/NetworkExtension/NetworkFilter.appex"))
 *             entitlements(file("packaging/networkextension.entitlements"))
 *             provisioningProfile(file("packaging/NetworkFilter.provisionprofile"))
 *         }
 *     }
 * }
 * ```
 */
class MacAppExtensionSettings : Serializable {
    internal val extensions: MutableList<MacAppExtension> = mutableListOf()

    /**
     * Declares an app extension to embed.
     *
     * @param name identifier used for diagnostics only
     */
    fun extension(name: String, fn: Action<MacAppExtension>) {
        val extension = MacAppExtension(name)
        fn.execute(extension)
        extensions.add(extension)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A single macOS app extension (`.appex`) to embed under `Contents/PlugIns/`.
 *
 * The extension is signed with its own [entitlements] (and, when set,
 * [provisioningProfile]), using the app's signing identity. The outer app is then
 * re-sealed without `--deep` so the extension's signature is preserved.
 */
class MacAppExtension(
    /** Identifier used for diagnostics only. */
    val name: String,
) : Serializable {
    internal var appex: File? = null
    internal var entitlements: File? = null
    internal var provisioningProfile: File? = null

    /** The prebuilt `.appex` bundle to embed. */
    fun appex(bundle: File) {
        appex = bundle
    }

    /** Entitlements plist applied to the extension (distinct from the app's). */
    fun entitlements(file: File) {
        entitlements = file
    }

    /** Provisioning profile embedded as `Contents/embedded.provisionprofile` inside the extension. */
    fun provisioningProfile(file: File) {
        provisioningProfile = file
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

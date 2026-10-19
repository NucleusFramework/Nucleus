/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import dev.nucleusframework.desktop.application.internal.NucleusProperties
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import java.io.File
import javax.inject.Inject

/**
 * GPG signing for Linux packages (`.deb` / `.rpm`), for distribution outside of a store.
 *
 * All values fall back to Gradle properties / environment variables under
 * `compose.desktop.linux.signing.*`, so CI only needs to set those and toggle [enabled].
 */
abstract class LinuxSigningSettings {
    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Inject
    protected abstract val providers: ProviderFactory

    /** Enable Linux package signing. Default: `false` (or `compose.desktop.linux.sign`). */
    @get:Input
    val enabled: Property<Boolean> =
        objects.notNullProperty<Boolean>().apply {
            set(NucleusProperties.linuxSign(providers).orElse(false))
        }

    /** GPG key id, fingerprint or email used to sign. */
    @get:Input
    @get:Optional
    val keyId: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.linuxSignKeyId(providers))
        }

    /**
     * ASCII-armored private key file to import before signing.
     * Optional: omit when the key is already present in the signing keyring.
     */
    @get:Input
    @get:Optional
    val keyFile: RegularFileProperty =
        objects.fileProperty().apply {
            fileProvider(NucleusProperties.linuxSignKeyFile(providers).map(::File))
        }

    /** Passphrase protecting the private key. Optional. */
    @get:Input
    @get:Optional
    val passphrase: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.linuxSignPassphrase(providers))
        }

    /** Method used to sign `.deb` packages. Default: [DebSignMethod.Detached]. */
    @get:Input
    var debMethod: DebSignMethod = DebSignMethod.Detached

    /**
     * Enable passwordless self-update for DEB/RPM installs (Linux only).
     *
     * Requires [enabled] — the update helper only installs packages whose detached signature
     * verifies against the public key bundled in the app. When on, the package ships a root-owned
     * update helper plus a polkit policy that lets the app apply a verified update without a
     * password prompt. Off by default. Falls back to the standard `pkexec` (password) install when
     * the helper is absent.
     */
    @get:Input
    val silentUpdate: Property<Boolean> =
        objects.notNullProperty<Boolean>().apply {
            set(NucleusProperties.linuxSignSilentUpdate(providers).orElse(false))
        }
}

/**
 * Strategy used to sign a `.deb` package.
 *
 * - [Detached] (default): writes a detached `<pkg>.deb.asc` signature next to the package,
 *   verifiable with `gpg --verify <pkg>.deb.asc <pkg>.deb`. Needs only `gpg`, so it works on
 *   every distro — the right choice for direct-download distribution. (`dpkg-sig` was removed
 *   from recent Debian/Ubuntu, which is why this, not in-package signing, is the default.)
 * - [DpkgSig]: embeds an `_gpgorigin` member via `dpkg-sig`, verifiable with a plain
 *   `gpg --verify <pkg>.deb`. Requires `dpkg-sig` to be installed at build time.
 * - [Debsig]: embeds an `_gpgorigin` member via `debsigs`, verifiable with `debsig-verify`,
 *   which additionally requires a per-key policy and keyring on the client.
 */
enum class DebSignMethod {
    Detached,
    DpkgSig,
    Debsig,
}

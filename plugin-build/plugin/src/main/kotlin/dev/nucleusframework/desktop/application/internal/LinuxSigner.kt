/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 *
 * The deb (`dpkg-sig`) / rpm (`rpm --addsign`) signing strategy and the corresponding
 * verification flow are adapted from goreleaser/nfpm (MIT) — github.com/goreleaser/nfpm.
 * No code is copied; the pattern is re-implemented by shelling out to gpg/rpm/dpkg-sig.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.DebSignMethod
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * Signs Linux `.deb` / `.rpm` packages with a GPG key and exports the public key
 * next to the artifacts, so end users can verify a download with `gpg --verify`
 * (deb) or `rpm -K` (rpm) without any repository configuration.
 *
 * Signing happens inside a throwaway `GNUPGHOME` so the user's keyring is never touched.
 */
internal class LinuxSigner(
    private val runTool: ExternalToolRunner,
    private val logger: Logger,
) {
    /**
     * Signs every `.deb`/`.rpm` found in [packages] in place and writes a
     * `<package>.pub.asc` public key file next to each one.
     *
     * Missing optional tools (e.g. `dpkg-sig`, `debsigs`) are logged and skipped
     * rather than failing the build.
     */
    fun sign(
        packages: List<File>,
        keyId: String,
        keyFile: File?,
        passphrase: String?,
        debMethod: DebSignMethod,
        requireDetachedSignature: Boolean = false,
    ) {
        if (packages.isEmpty()) return

        val gpg =
            findInPath("gpg")
                ?: run {
                    logger.warn("Linux signing skipped: 'gpg' not found in PATH")
                    return
                }

        withKeyring(keyFile, passphrase) { home, passphraseFile ->
            for (pkg in packages) {
                val wroteDetached =
                    when (pkg.extension.lowercase()) {
                        "rpm" -> {
                            signRpm(gpg, home, keyId, passphraseFile, pkg)
                            false
                        }
                        "deb" -> signDeb(gpg, home, keyId, passphraseFile, debMethod, pkg)
                        else -> false
                    }
                // The silent-update helper verifies a detached `<pkg>.asc`; make sure one exists
                // even for formats whose primary signature lives elsewhere (RPM header, _gpgorigin).
                if (requireDetachedSignature && !wroteDetached) {
                    detachSign(gpg, home, keyId, passphraseFile, pkg)
                }
                exportPublicKey(gpg, home, keyId, File(pkg.parentFile, "${pkg.name}.pub.asc"))
            }
        }
    }

    /**
     * Exports just the public key for [keyId] to [destination], without touching any package.
     * Used to bundle the key inside the app before packaging so the update helper can verify
     * downloads against it.
     */
    fun exportPublicKey(
        keyId: String,
        keyFile: File?,
        passphrase: String?,
        destination: File,
    ) {
        val gpg =
            findInPath("gpg")
                ?: run {
                    logger.warn("Public key export skipped: 'gpg' not found in PATH")
                    return
                }
        withKeyring(keyFile, passphrase) { home, _ ->
            exportPublicKey(gpg, home, keyId, destination)
        }
    }

    /**
     * Runs [block] with a throwaway `GNUPGHOME` that has [keyFile] imported (if given), so the
     * user's real keyring is never touched. The keyring and its agent are cleaned up afterwards.
     */
    private fun withKeyring(
        keyFile: File?,
        passphrase: String?,
        block: (home: File, passphraseFile: File?) -> Unit,
    ) {
        val gpg = findInPath("gpg") ?: return
        val home = Files.createTempDirectory("nucleus-gpg").toFile()
        restrictToOwner(home)
        // Allow non-interactive (loopback) passphrase entry in this throwaway keyring.
        File(home, "gpg-agent.conf").writeText("allow-loopback-pinentry\n")
        val passphraseFile = passphrase?.let { writePassphraseFile(home, it) }
        try {
            if (keyFile != null) {
                importKey(gpg, home, keyFile, passphraseFile)
            }
            block(home, passphraseFile)
        } finally {
            killAgent(home)
            home.deleteRecursively()
        }
    }

    /** Writes a detached, armored `<pkg>.asc` signature next to [pkg]. */
    private fun detachSign(
        gpg: File,
        home: File,
        keyId: String,
        passphraseFile: File?,
        pkg: File,
    ) {
        val sig = File(pkg.parentFile, "${pkg.name}.asc")
        logger.lifecycle("Writing detached signature ${sig.name}")
        runTool(
            tool = gpg,
            args =
                gpgBaseArgs(home, passphraseFile) +
                    listOf("-u", keyId, "--detach-sign", "--armor", "-o", sig.absolutePath, pkg.absolutePath),
        )
    }

    private fun gpgBaseArgs(
        home: File,
        passphraseFile: File?,
    ): List<String> =
        buildList {
            add("--homedir")
            add(home.absolutePath)
            add("--batch")
            add("--yes")
            add("--no-tty")
            if (passphraseFile != null) {
                add("--pinentry-mode")
                add("loopback")
                add("--passphrase-file")
                add(passphraseFile.absolutePath)
            }
        }

    private fun importKey(
        gpg: File,
        home: File,
        keyFile: File,
        passphraseFile: File?,
    ) {
        logger.info("Importing signing key from ${keyFile.absolutePath}")
        runTool(
            tool = gpg,
            args = gpgBaseArgs(home, passphraseFile) + listOf("--import", keyFile.absolutePath),
        )
    }

    private fun signRpm(
        gpg: File,
        home: File,
        keyId: String,
        passphraseFile: File?,
        pkg: File,
    ) {
        val rpm =
            findInPath("rpmsign") ?: findInPath("rpm")
                ?: run {
                    logger.warn("RPM signing skipped for ${pkg.name}: neither 'rpmsign' nor 'rpm' found in PATH")
                    return
                }

        // `rpm --addsign` invokes gpg through gpg-agent and cannot pass a passphrase itself.
        // Its sign-command macro is version-specific (named vs positional args between rpm 4 and 6),
        // so instead of overriding it we prime the agent's passphrase cache with a loopback dummy
        // sign, then let rpm use its native default command.
        if (passphraseFile != null) {
            val dummy = File(home, "prime.txt").apply { writeText("prime") }
            runTool(
                tool = gpg,
                args =
                    gpgBaseArgs(home, passphraseFile) +
                        listOf(
                            "-u",
                            keyId,
                            "--detach-sign",
                            "-o",
                            File(home, "prime.sig").absolutePath,
                            dummy.absolutePath,
                        ),
            )
        }

        logger.lifecycle("Signing RPM ${pkg.name}")
        runTool(
            tool = rpm,
            args =
                listOf(
                    "--define", "_gpg_name $keyId",
                    "--define", "_gpg_path ${home.absolutePath}",
                    "--addsign", pkg.absolutePath,
                ),
        )
    }

    /** Signs a `.deb`. Returns `true` when it wrote a detached `<pkg>.asc` (the `Detached` method). */
    private fun signDeb(
        gpg: File,
        home: File,
        keyId: String,
        passphraseFile: File?,
        debMethod: DebSignMethod,
        pkg: File,
    ): Boolean {
        val gpgOpts =
            buildString {
                append("--homedir ${home.absolutePath} --batch --yes --no-tty")
                if (passphraseFile != null) {
                    append(" --pinentry-mode loopback --passphrase-file ${passphraseFile.absolutePath}")
                }
            }

        when (debMethod) {
            DebSignMethod.Detached -> {
                detachSign(gpg, home, keyId, passphraseFile, pkg)
                return true
            }
            DebSignMethod.DpkgSig -> {
                val dpkgSig =
                    findInPath("dpkg-sig")
                        ?: run {
                            logger.warn("DEB signing skipped for ${pkg.name}: 'dpkg-sig' not found in PATH")
                            return false
                        }
                logger.lifecycle("Signing DEB ${pkg.name} (dpkg-sig)")
                runTool(
                    tool = dpkgSig,
                    args = listOf("-g", gpgOpts, "--sign", "builder", "-k", keyId, pkg.absolutePath),
                )
            }
            DebSignMethod.Debsig -> {
                val debsigs =
                    findInPath("debsigs")
                        ?: run {
                            logger.warn("DEB signing skipped for ${pkg.name}: 'debsigs' not found in PATH")
                            return false
                        }
                logger.lifecycle("Signing DEB ${pkg.name} (debsigs/_gpgorigin)")
                runTool(
                    tool = debsigs,
                    args = listOf("--sign=origin", "--default-key=$keyId", "--gpgopts", gpgOpts, pkg.absolutePath),
                )
            }
        }
        return false
    }

    private fun exportPublicKey(
        gpg: File,
        home: File,
        keyId: String,
        destination: File,
    ) {
        runTool(
            tool = gpg,
            args = listOf("--homedir", home.absolutePath, "--batch", "--armor", "--export", keyId),
            processStdout = { armored -> destination.writeText(armored) },
        )
        logger.info("Exported public key to ${destination.absolutePath}")
    }

    private fun writePassphraseFile(
        home: File,
        passphrase: String,
    ): File {
        val file = File(home, "passphrase")
        file.writeText(passphrase)
        restrictToOwner(file)
        return file
    }

    private fun killAgent(home: File) {
        val gpgconf = findInPath("gpgconf") ?: return
        runCatching {
            runTool(
                tool = gpgconf,
                args = listOf("--homedir", home.absolutePath, "--kill", "gpg-agent"),
                checkExitCodeIsNormal = false,
            )
        }
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    private fun findInPath(executableName: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(":").firstNotNullOfOrNull { dir ->
            File(dir, executableName).takeIf { it.exists() && it.canExecute() }
        }
    }
}

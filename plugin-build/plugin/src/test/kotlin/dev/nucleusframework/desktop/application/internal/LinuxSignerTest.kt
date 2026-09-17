package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.DebSignMethod
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end test of [LinuxSigner] against the real `gpg` / `rpm` / `rpmbuild` tools,
 * plus [LinuxUpdateHelper] package-ownership and silent-update regressions.
 *
 * Skipped (not failed) on machines without the required tools, e.g. CI runners without rpm.
 */
class LinuxSignerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = "nucleus-test-pass"

    @Test
    fun `signs rpm and the signature verifies with only the exported public key`() {
        Assume.assumeTrue("requires gpg, rpm and rpmbuild", toolsAvailable("gpg", "rpm", "rpmbuild"))

        val keyHome = tmp.newFolder("keygen").apply { restrict() }
        val keyId = generateKey(keyHome)
        val privateKey = exportPrivateKey(keyHome, keyId)
        val rpm = buildMinimalRpm()

        LinuxSigner(runner(), project.logger).sign(
            packages = listOf(rpm),
            keyId = keyId,
            keyFile = privateKey,
            passphrase = passphrase,
            debMethod = DebSignMethod.DpkgSig,
        )

        val publicKey = File(rpm.parentFile, "${rpm.name}.pub.asc")
        assertTrue("public key not exported next to the package", publicKey.isFile && publicKey.length() > 0)
        assertTrue("rpm signature did not verify", rpmSignatureVerifies(rpm, publicKey))
    }

    @Test
    fun `signs deb with a detached signature that verifies against the exported public key`() {
        Assume.assumeTrue("requires gpg", toolsAvailable("gpg"))

        val keyHome = tmp.newFolder("keygen-deb").apply { restrict() }
        val keyId = generateKey(keyHome)
        val privateKey = exportPrivateKey(keyHome, keyId)
        val deb = tmp.newFile("app_1.0.0_amd64.deb").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        LinuxSigner(runner(), project.logger).sign(
            packages = listOf(deb),
            keyId = keyId,
            keyFile = privateKey,
            passphrase = passphrase,
            debMethod = DebSignMethod.Detached,
        )

        val detachedSig = File(deb.parentFile, "${deb.name}.asc")
        val publicKey = File(deb.parentFile, "${deb.name}.pub.asc")
        assertTrue("detached signature not written", detachedSig.isFile && detachedSig.length() > 0)
        assertTrue("public key not exported", publicKey.isFile && publicKey.length() > 0)
        assertTrue("detached signature did not verify", detachedSignatureVerifies(deb, detachedSig, publicKey))
    }

    @Test
    fun `silent-update signing also writes a detached asc for rpm`() {
        Assume.assumeTrue("requires gpg, rpm and rpmbuild", toolsAvailable("gpg", "rpm", "rpmbuild"))

        val keyHome = tmp.newFolder().apply { restrict() }
        val keyId = generateKey(keyHome)
        val privateKey = exportPrivateKey(keyHome, keyId)
        val rpm = buildMinimalRpm()

        LinuxSigner(runner(), project.logger).sign(
            packages = listOf(rpm),
            keyId = keyId,
            keyFile = privateKey,
            passphrase = passphrase,
            debMethod = DebSignMethod.Detached,
            requireDetachedSignature = true,
        )

        val detached = File(rpm.parentFile, "${rpm.name}.asc")
        val publicKey = File(rpm.parentFile, "${rpm.name}.pub.asc")
        assertTrue("detached .asc not written for rpm", detached.isFile && detached.length() > 0)
        assertTrue("detached rpm signature did not verify", detachedSignatureVerifies(rpm, detached, publicKey))
    }

    @Test
    fun `update helper accepts a valid signature and rejects a tampered or missing one`() {
        Assume.assumeTrue("requires gpg and dpkg-deb", toolsAvailable("gpg", "dpkg-deb"))

        val keyHome = tmp.newFolder().apply { restrict() }
        val keyId = generateKey(keyHome)

        val appDir = tmp.newFolder()
        LinuxUpdateHelper.writeHelper(appDir)
        val helper = File(appDir, LinuxUpdateHelper.HELPER_FILE_NAME)
        File(appDir, "resources").mkdirs()
        File(appDir, LinuxUpdateHelper.PUBLIC_KEY_RELATIVE_PATH)
            .writeText(capture("gpg", "--homedir", keyHome.absolutePath, "--batch", "--armor", "--export", keyId))

        val pkg = buildMinimalDeb(packageName = "nucleus-helper-test", version = "1.0.0")
        val sig = File("${pkg.absolutePath}.asc")
        detachSign(keyHome, keyId, pkg, sig)

        // Valid signature: passes verification, then stops at ownership (exit 3) — helper is not
        // owned by an installed package in this temp layout.
        assertEquals("valid signature must pass verification", 3, exitCodeOf("bash", helper.absolutePath, pkg.absolutePath))

        pkg.appendBytes(byteArrayOf(0))
        val tampered = exitCodeOf("bash", helper.absolutePath, pkg.absolutePath)
        assertTrue("tampered package must be rejected (got $tampered)", tampered != 0 && tampered != 3)

        sig.delete()
        assertEquals("missing signature must be refused", 4, exitCodeOf("bash", helper.absolutePath, pkg.absolutePath))
    }

    /**
     * CI-safe regression for the #158 ownership bug: the helper must appear in the package
     * file list. Without that, `dpkg -S` is empty and every upgrade dies with exit 3.
     * No root required.
     */
    @Test
    fun `silent-update deb package lists helper and public key in payload`() {
        Assume.assumeTrue("requires dpkg-deb", toolsAvailable("dpkg-deb"))

        val packageName = "nucleus-silent-payload-test"
        val productDir = "opt/$packageName"
        val root = tmp.newFolder("payload")
        val appDir = File(root, productDir).apply { mkdirs() }
        File(appDir, "VERSION").writeText("1.0.0")
        File(appDir, "resources").mkdirs()
        File(appDir, LinuxUpdateHelper.PUBLIC_KEY_RELATIVE_PATH).writeText("-----BEGIN PGP PUBLIC KEY BLOCK-----\n")
        LinuxUpdateHelper.writeHelper(appDir)

        val deb =
            buildMinimalDeb(
                packageName = packageName,
                version = "1.0.0",
                payloadRoot = root,
            )
        val listing = capture("dpkg-deb", "-c", deb.absolutePath)
        assertTrue(
            "helper missing from package file list:\n$listing",
            listing.contains(LinuxUpdateHelper.HELPER_FILE_NAME),
        )
        assertTrue(
            "public key missing from package file list:\n$listing",
            listing.contains("nucleus-update.pub.asc"),
        )
    }

    @Test
    fun `polkit afterInstall fragment does not rewrite the helper script`() {
        val fragment = LinuxUpdateHelper.polkitAfterInstallFragment()
        assertTrue(fragment.contains("allow_active>yes"))
        assertTrue(fragment.contains("org.freedesktop.policykit.exec.path"))
        assertTrue(fragment.contains(LinuxUpdateHelper.HELPER_FILE_NAME))
        // Must not embed/rewrite the helper body (payload is the single source of truth).
        assertFalse(
            "afterInstall must not cat-rewrite the helper",
            fragment.contains("NUCLEUS_HELPER_EOF") || fragment.contains("#!/usr/bin/env bash"),
        )
    }

    @Test
    fun `polkit afterRemove fragment removes the policy file`() {
        val fragment = LinuxUpdateHelper.polkitAfterRemoveFragment()
        assertTrue(fragment.contains("rm -f"))
        assertTrue(fragment.contains("polkit-1/actions"))
        assertTrue(fragment.contains(".update.policy"))
        assertFalse(fragment.contains("#!/usr/bin/env bash"))
    }

    private fun detachSign(
        keyHome: File,
        keyId: String,
        pkg: File,
        sig: File,
    ) {
        run(
            "gpg", "--homedir", keyHome.absolutePath, "--batch", "--yes",
            "--pinentry-mode", "loopback", "--passphrase", passphrase,
            "-u", keyId, "--detach-sign", "--armor", "-o", sig.absolutePath, pkg.absolutePath,
        )
    }

    private fun buildMinimalDeb(
        packageName: String,
        version: String,
        payloadRoot: File? = null,
    ): File {
        val root = payloadRoot ?: tmp.newFolder()
        File(root, "DEBIAN").mkdirs()
        File(root, "DEBIAN/control").writeText(
            buildString {
                appendLine("Package: $packageName")
                appendLine("Version: $version")
                appendLine("Architecture: all")
                appendLine("Maintainer: Nucleus Test <test@nucleus.dev>")
                appendLine("Description: helper test package $version")
            },
        )
        val deb = File(tmp.newFolder(), "${packageName}_${version}_all.deb")
        run("dpkg-deb", "--build", "--root-owner-group", root.absolutePath, deb.absolutePath)
        return deb
    }

    private fun detachedSignatureVerifies(
        deb: File,
        sig: File,
        publicKey: File,
    ): Boolean {
        val verifyHome = tmp.newFolder().apply { restrict() }
        run("gpg", "--homedir", verifyHome.absolutePath, "--batch", "--import", publicKey.absolutePath)
        val output =
            capture(
                "gpg", "--homedir", verifyHome.absolutePath, "--verify", sig.absolutePath, deb.absolutePath,
                env = mapOf("LC_ALL" to "C"),
            )
        return output.contains("Good signature")
    }

    private val project by lazy { ProjectBuilder.builder().withProjectDir(tmp.root).build() }

    private fun runner(): ExternalToolRunner {
        val execOps = (project as ProjectInternal).services.get(ExecOperations::class.java)
        val verbose = project.objects.property(Boolean::class.java).convention(false)
        val logsDir = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("logs"))
        return ExternalToolRunner(verbose, logsDir, execOps)
    }

    private fun generateKey(home: File): String {
        val params = File(home, "params")
        params.writeText(
            buildString {
                appendLine("Key-Type: RSA")
                appendLine("Key-Length: 2048")
                appendLine("Name-Real: Nucleus Test")
                appendLine("Name-Email: test@nucleus.dev")
                appendLine("Expire-Date: 0")
                appendLine("Passphrase: $passphrase")
                appendLine("%commit")
            },
        )
        run("gpg", "--batch", "--homedir", home.absolutePath, "--gen-key", params.absolutePath)
        val colons =
            capture("gpg", "--homedir", home.absolutePath, "--list-keys", "--with-colons", "test@nucleus.dev")
        return colons.lineSequence()
            .first { it.startsWith("pub:") }
            .split(":")[4]
    }

    private fun exportPrivateKey(
        home: File,
        keyId: String,
    ): File {
        val out = File(home, "private.asc")
        val text =
            capture(
                "gpg", "--homedir", home.absolutePath, "--batch", "--yes",
                "--pinentry-mode", "loopback", "--passphrase", passphrase,
                "--armor", "--export-secret-keys", keyId,
            )
        out.writeText(text)
        return out
    }

    private fun buildMinimalRpm(): File {
        val top = tmp.newFolder()
        listOf("SPECS", "BUILD", "RPMS", "SOURCES").forEach { File(top, it).mkdirs() }
        val spec = File(top, "SPECS/foo.spec")
        spec.writeText(
            buildString {
                appendLine("Name: foo")
                appendLine("Version: 1.0.0")
                appendLine("Release: 1")
                appendLine("Summary: test")
                appendLine("License: MIT")
                appendLine("%description")
                appendLine("test")
                appendLine("%files")
                appendLine("%changelog")
            },
        )
        run("rpmbuild", "--define", "_topdir ${top.absolutePath}", "-bb", spec.absolutePath)
        return File(top, "RPMS").walkTopDown().first { it.extension == "rpm" }
    }

    private fun rpmSignatureVerifies(
        rpm: File,
        publicKey: File,
    ): Boolean {
        val db = tmp.newFolder()
        run("rpm", "--dbpath", db.absolutePath, "--initdb")
        run("rpm", "--dbpath", db.absolutePath, "--import", publicKey.absolutePath)
        val output = capture("rpm", "--dbpath", db.absolutePath, "-K", rpm.absolutePath, env = mapOf("LC_ALL" to "C"))
        return output.contains("signatures OK")
    }

    private fun toolsAvailable(vararg tools: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return tools.all { tool ->
            path.split(":").any { File(it, tool).let { f -> f.exists() && f.canExecute() } }
        }
    }

    private fun run(vararg command: String) {
        val exit = process(command.toList(), emptyMap()).waitFor()
        check(exit == 0) { "command failed ($exit): ${command.joinToString(" ")}" }
    }

    private fun exitCodeOf(vararg command: String): Int {
        val proc = process(command.toList(), emptyMap())
        proc.inputStream.readBytes()
        return proc.waitFor()
    }

    private fun capture(
        vararg command: String,
        env: Map<String, String> = emptyMap(),
    ): String {
        val proc = process(command.toList(), env)
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output
    }

    private fun process(
        command: List<String>,
        env: Map<String, String>,
    ): Process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()

    private fun File.restrict() {
        setReadable(false, false)
        setReadable(true, true)
        setExecutable(true, true)
    }
}

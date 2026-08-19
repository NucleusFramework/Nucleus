package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards on the root-run update helper script. The helper is invoked as root via
 * pkexec, so these invariants (verify a root-owned copy, refuse downgrades) protect against a
 * local privilege escalation and a signed-package rollback. The bash itself is exercised on real
 * distros in packaging tests; here we lock the security-relevant shape so a refactor cannot drop it.
 */
class LinuxUpdateHelperTest {
    private val script = LinuxUpdateHelper.SCRIPT

    @Test
    fun `package and signature are copied into a root-owned work dir before verification`() {
        assertTrue("creates a private work dir", script.contains("mktemp -d"))
        assertTrue("copies the package", script.contains("""cp -- "${'$'}SRC_PKG" "${'$'}PKG""""))
        assertTrue("copies the signature", script.contains("""cp -- "${'$'}SRC_SIG" "${'$'}SIG""""))

        val copyIndex = script.indexOf("""cp -- "${'$'}SRC_PKG"""")
        val verifyIndex = script.indexOf("--verify")
        assertTrue("copy must run before verify (no TOCTOU window)", copyIndex in 0 until verifyIndex)
    }

    @Test
    fun `verification runs against the copied package, not the caller-supplied path`() {
        assertTrue("PKG is inside the work dir", script.contains("""PKG="${'$'}WORK/"""))
        assertTrue(
            "gpg verifies the copy",
            script.contains("""gpg --homedir "${'$'}KR" --batch --verify "${'$'}SIG" "${'$'}PKG""""),
        )
    }

    @Test
    fun `deb path refuses a non-upgrade to block a signed downgrade`() {
        assertTrue(
            "uses dpkg --compare-versions gt",
            script.contains("""dpkg --compare-versions "${'$'}NEWVER" gt "${'$'}CUR""""),
        )
        assertTrue("exits on non-upgrade", script.contains("refusing non-upgrade"))
    }
}

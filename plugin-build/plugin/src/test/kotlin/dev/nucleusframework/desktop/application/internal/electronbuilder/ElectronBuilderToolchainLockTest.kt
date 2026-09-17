package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderToolManager.Companion.ELECTRON_BUILDER_VERSION
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderToolManager.Companion.PACKAGE_JSON
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderToolManager.Companion.PACKAGE_LOCK_JSON
import dev.nucleusframework.desktop.application.internal.electronbuilder.ElectronBuilderToolManager.Companion.readToolchainResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the integrity boundary of the electron-builder toolchain.
 *
 * The plugin installs ~275 npm packages onto the machine that holds the code-signing certificates,
 * with `npm ci --ignore-scripts` against the lock file embedded here. `npm ci` only checks what the
 * lock file records, so these assertions check the lock file itself: pinned to the version the
 * Kotlin constant declares, every tarball from the public registry, every tarball carrying a
 * sha512 integrity hash. No network access — this reads the committed resources.
 */
class ElectronBuilderToolchainLockTest {
    private val packageJson = readToolchainResource(PACKAGE_JSON)
    private val lockJson = readToolchainResource(PACKAGE_LOCK_JSON)

    @Test
    fun `package json pins the same version as the tool manager`() {
        assertTrue(
            "package.json must pin electron-builder to exactly $ELECTRON_BUILDER_VERSION " +
                "(no range operator), got: $packageJson",
            packageJson.contains("\"electron-builder\": \"$ELECTRON_BUILDER_VERSION\""),
        )
    }

    @Test
    fun `lock file resolves the pinned electron-builder version`() {
        assertTrue(
            "package-lock.json must be lockfileVersion 3 so `npm ci` enforces the full tree",
            lockJson.contains("\"lockfileVersion\": 3"),
        )
        assertTrue(
            "package-lock.json must resolve electron-builder $ELECTRON_BUILDER_VERSION",
            lockJson.contains("/electron-builder/-/electron-builder-$ELECTRON_BUILDER_VERSION.tgz"),
        )
    }

    @Test
    fun `every locked package comes from the public npm registry`() {
        val foreign =
            RESOLVED_URL
                .findAll(lockJson)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("https://registry.npmjs.org/") }
                .toList()
        assertTrue(
            "package-lock.json must only resolve tarballs from registry.npmjs.org, found: $foreign",
            foreign.isEmpty(),
        )
    }

    @Test
    fun `every locked package carries a sha512 integrity hash`() {
        val resolved = RESOLVED_URL.findAll(lockJson).count()
        val integrities =
            INTEGRITY
                .findAll(lockJson)
                .map { it.groupValues[1] }
                .toList()

        assertTrue("package-lock.json resolves no package at all", resolved > 0)
        assertEquals(
            "every resolved package must have an integrity hash ($resolved resolved, " +
                "${integrities.size} hashed)",
            resolved,
            integrities.size,
        )
        val weak = integrities.filterNot { it.startsWith("sha512-") }
        assertTrue("integrity hashes must be sha512, found: $weak", weak.isEmpty())
    }

    private companion object {
        val RESOLVED_URL = """"resolved":\s*"([^"]+)"""".toRegex()
        val INTEGRITY = """"integrity":\s*"([^"]+)"""".toRegex()
    }
}

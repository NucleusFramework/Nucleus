package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The macOS `.app` bundle name has to be identical in every artifact of a release: electron-builder
 * stages the bundle inside a DMG as `${productFilename}.app` but archives the prepackaged directory
 * verbatim into the ZIP, so a mismatch ships `Nucleus Demo.app` in one and `NucleusDemo.app` in the
 * other — and the ZIP-based auto-updater then replaces an app that is not the one installed.
 */
class MacBundleNameTest {
    @Test
    fun `appName wins over packageName`() {
        assertEquals(
            "Nucleus Demo",
            resolveMacBundleName(
                bundleName = null,
                appName = "Nucleus Demo",
                packageName = "NucleusDemo",
                fallback = "demo",
            ),
        )
    }

    @Test
    fun `explicit bundleName wins over appName`() {
        assertEquals(
            "Legacy Name",
            resolveMacBundleName(
                bundleName = "Legacy Name",
                appName = "Nucleus Demo",
                packageName = "NucleusDemo",
                fallback = "demo",
            ),
        )
    }

    @Test
    fun `packageName is used when appName is unset`() {
        assertEquals(
            "NucleusDemo",
            resolveMacBundleName(bundleName = null, appName = null, packageName = "NucleusDemo", fallback = "demo"),
        )
    }

    @Test
    fun `project name is used when nothing else is configured`() {
        assertEquals(
            "demo",
            resolveMacBundleName(bundleName = null, appName = null, packageName = null, fallback = "demo"),
        )
    }

    @Test
    fun `blank candidates are skipped`() {
        assertEquals(
            "NucleusDemo",
            resolveMacBundleName(bundleName = "  ", appName = "", packageName = "NucleusDemo", fallback = "demo"),
        )
    }

    @Test
    fun `the bundle name is decomposed so it matches what the HFS+ volume of a DMG stores`() {
        // "Démo" written NFC must resolve to the NFD form HFS+ would produce, so the DMG entry and
        // the ZIP entry are byte-identical and not merely equivalent.
        assertEquals(
            "De\u0301mo",
            resolveMacBundleName(bundleName = null, appName = "D\u00E9mo", packageName = null, fallback = "demo"),
        )
        // Already-decomposed input is left alone.
        assertEquals(
            "De\u0301mo",
            resolveMacBundleName(bundleName = null, appName = "De\u0301mo", packageName = null, fallback = "demo"),
        )
    }

    @Test
    fun `decomposition alone is not reported as sanitization`() {
        assertEquals(
            emptyList<String>(),
            macBundleNameWarnings(
                bundleName = null,
                appName = "D\u00E9mo",
                macPackageName = null,
                packageName = null,
                resolved = "De\u0301mo",
            ),
        )
    }

    @Test
    fun `illegal characters are stripped exactly like electron-builder does`() {
        assertEquals(
            "MyApp",
            resolveMacBundleName(bundleName = null, appName = "My/App", packageName = null, fallback = "demo"),
        )
        assertEquals(
            "App Beta",
            resolveMacBundleName(bundleName = null, appName = "App: Beta", packageName = null, fallback = "demo"),
        )
    }

    @Test
    fun `a name that sanitizes away falls through to the next candidate`() {
        assertEquals(
            "NucleusDemo",
            resolveMacBundleName(bundleName = "...", appName = "con", packageName = "NucleusDemo", fallback = "demo"),
        )
        assertEquals(
            "demo",
            resolveMacBundleName(bundleName = "...", appName = null, packageName = null, fallback = "demo"),
        )
        assertEquals(
            "app",
            resolveMacBundleName(bundleName = "...", appName = null, packageName = null, fallback = "///"),
        )
    }

    @Test
    fun `sanitizeFileName matches npm sanitize-filename`() {
        // Expectations captured by running sanitize-filename@1.6.3 — the version electron-builder
        // uses to derive AppInfo.productFilename — with the default empty replacement.
        val expected =
            listOf(
                "." to "",
                ".." to "",
                "..." to "",
                "con" to "",
                "CON" to "",
                "COM1" to "",
                "com0" to "",
                "lpt9.txt" to "",
                "aux.txt" to "",
                "nul" to "",
                "prn" to "",
                "LPT1.foo.bar" to "",
                "hello." to "hello",
                "hello " to "hello",
                "hello. . " to "hello",
                "the end." to "the end",
                "..leading" to "..leading",
                "valid<>:\"/\\|?*file" to "validfile",
                "file|name" to "filename",
                "valid name" to "valid name",
                "Nucleus Demo" to "Nucleus Demo",
                "My/App" to "MyApp",
                "App: Beta" to "App Beta",
                "NucleusDemo" to "NucleusDemo",
                "holidays" to "holidays",
                "" to "",
                "a b" to "a b",
                // The 0x00-0x1F and 0x80-0x9F control ranges are dropped, 0xA0 and above are kept.
                "x\u0000y" to "xy",
                "ctrl\u001Fx" to "ctrlx",
                "ctrl\u0080x" to "ctrlx",
                "ctrl\u009Fx" to "ctrlx",
                "ctrl\u00A0x" to "ctrl\u00A0x",
                "soft\u00ADhyphen" to "soft\u00ADhyphen",
                "tab\tsep" to "tabsep",
            )
        for ((input, output) in expected) {
            assertEquals("sanitizeFileName of ${input.map { it.code }}", output, sanitizeFileName(input))
        }
        // sanitizeFileName itself does not normalize; only resolveMacBundleName does.
        assertEquals("caf\u00E9", sanitizeFileName("caf\u00E9"))
    }

    @Test
    fun `the resolved name is a fixed point of sanitizeFileName`() {
        // electron-builder sanitizes our productName again on its way to productFilename, so a name
        // that is not a fixed point would come back shorter than the directory we named — the DMG
        // and the ZIP would disagree once more. Truncation is what can expose a trailing dot.
        val names =
            listOf(
                "Nucleus Demo",
                "My/App: Beta",
                "Demo. ",
                "A".repeat(200) + ". " + "B".repeat(200),
                ("Beta 1. ").repeat(60),
                "Démo Nucléus",
            )
        for (name in names) {
            val resolved =
                resolveMacBundleName(bundleName = null, appName = name, packageName = null, fallback = "demo")
            assertEquals("not a fixed point: $resolved", resolved, sanitizeFileName(resolved))
        }
    }

    @Test
    fun `truncation matches npm sanitize-filename`() {
        // sanitize-filename truncates to 255 UTF-8 bytes on a character boundary.
        assertEquals(255, sanitizeFileName("a".repeat(300)).length)
        val truncated = sanitizeFileName("é".repeat(200))
        assertEquals(127, truncated.length)
        assertEquals(254, truncated.toByteArray(Charsets.UTF_8).size)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun `no warning for the common appName plus packageName setup`() {
        assertEquals(
            emptyList<String>(),
            macBundleNameWarnings(
                bundleName = null,
                appName = "Nucleus Demo",
                macPackageName = null,
                packageName = null,
                resolved = "Nucleus Demo",
            ),
        )
    }

    @Test
    fun `warns when macOS packageName loses to appName`() {
        val warnings =
            macBundleNameWarnings(
                bundleName = null,
                appName = "Nucleus Demo",
                macPackageName = "Localized Name",
                packageName = null,
                resolved = "Nucleus Demo",
            )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("macOS.bundleName"))
        assertTrue(warnings.single().contains("Localized Name"))
    }

    @Test
    fun `no warning when macOS packageName is what got resolved`() {
        assertEquals(
            emptyList<String>(),
            macBundleNameWarnings(
                bundleName = null,
                appName = null,
                macPackageName = "NucleusDemo",
                packageName = null,
                resolved = "NucleusDemo",
            ),
        )
    }

    @Test
    fun `no warning when bundleName is set explicitly`() {
        assertEquals(
            emptyList<String>(),
            macBundleNameWarnings(
                bundleName = "Legacy",
                appName = "Nucleus Demo",
                macPackageName = "Other",
                packageName = null,
                resolved = "Legacy",
            ),
        )
    }

    @Test
    fun `warns when the requested name had to be sanitized`() {
        val warnings =
            macBundleNameWarnings(
                bundleName = null,
                appName = "My/App",
                macPackageName = null,
                packageName = null,
                resolved = "MyApp",
            )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("illegal"))
    }

    @Test
    fun `warns when the sanitized name came from packageName alone`() {
        val warnings =
            macBundleNameWarnings(
                bundleName = null,
                appName = null,
                macPackageName = null,
                packageName = "My/App",
                resolved = "MyApp",
            )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("illegal"))
    }

    @Test
    fun `a blank bundleName does not suppress the macOS packageName warning`() {
        val warnings =
            macBundleNameWarnings(
                bundleName = "   ",
                appName = "Nucleus Demo",
                macPackageName = "Localized Name",
                packageName = null,
                resolved = "Nucleus Demo",
            )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("macOS.bundleName"))
    }
}

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.MacWidgetFamily
import dev.nucleusframework.desktop.application.dsl.MacWidgetsSettings
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MacWidgetsPackagingTest {
    private val widgets =
        MacWidgetsSettings().apply {
            widget("Counter") {
                it.displayName = "Counter & co"
                it.families = listOf(MacWidgetFamily.Small, MacWidgetFamily.Medium)
            }
            widget("Tasks") { }
        }

    private fun jar(
        dir: File,
        arch: String,
    ): File =
        dir.resolve("nucleus.widget-macos.jar").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                val prefix = MacWidgetsPackaging.jarPrefix(arch)
                zip.putNextEntry(ZipEntry(prefix + "NucleusWidgets"))
                zip.write(byteArrayOf(0xCF.toByte(), 0xFA.toByte()))
                zip.putNextEntry(ZipEntry(prefix + "Metadata.appintents/extract.actionsdata"))
                zip.write("{}".toByteArray())
                zip.putNextEntry(ZipEntry("nucleus/native/darwin-$arch/libnucleus_widget_macos.dylib"))
                zip.write(byteArrayOf(1))
            }
        }

    @Test
    fun infoPlistListsTheWidgetsAndTheHomeStore() {
        val plist = MacWidgetsPackaging.infoPlist(widgets, "dev.example.app", "1.2.3")
        assertTrue(plist, "<key>CFBundleIdentifier</key><string>dev.example.app.NucleusWidgets</string>" in plist)
        assertTrue(plist, "<key>NSExtensionPointIdentifier</key><string>com.apple.widgetkit-extension</string>" in plist)
        val store = "<key>StoreHomePath</key><string>Library/Application Support/dev.example.app/NucleusWidgets</string>"
        assertTrue(plist, store in plist)
        assertTrue(plist, "<key>DisplayName</key><string>Counter &amp; co</string>" in plist)
        assertTrue(plist, "<array><string>systemSmall</string><string>systemMedium</string></array>" in plist)
        // Unset families: small, medium, large.
        val defaults = "<string>systemSmall</string><string>systemMedium</string><string>systemLarge</string>"
        assertTrue(plist, defaults in plist)
    }

    @Test
    fun entitlementsSandboxTheExtensionToItsStore() {
        val home = MacWidgetsPackaging.entitlements(widgets, "dev.example.app")
        assertTrue(home, "<key>com.apple.security.app-sandbox</key><true/>" in home)
        assertTrue(home, "<string>/Library/Application Support/dev.example.app/NucleusWidgets/</string>" in home)

        widgets.appGroup = "ABCDE12345.dev.example.app"
        val group = MacWidgetsPackaging.entitlements(widgets, "dev.example.app")
        assertTrue(group, "<string>ABCDE12345.dev.example.app</string>" in group)
        assertFalse(group, "temporary-exception" in group)
        assertTrue("<key>StoreAppGroup</key>" in MacWidgetsPackaging.infoPlist(widgets, "dev.example.app", "1"))
    }

    @Test
    fun assemblesTheAppexFromTheJar() {
        val dir = Files.createTempDirectory("mac-widgets").toFile()
        try {
            val out = dir.resolve("out")
            MacWidgetsPackaging.assemble(widgets, "dev.example.app", "1.0", "aarch64", listOf(jar(dir, "aarch64")), out)
            val contents = out.resolve("NucleusWidgets.appex/Contents")
            assertTrue(contents.resolve("MacOS/NucleusWidgets").canExecute())
            assertEquals("{}", contents.resolve("Resources/Metadata.appintents/extract.actionsdata").readText())
            assertTrue(contents.resolve("Info.plist").isFile)
            assertTrue(out.resolve("NucleusWidgets.entitlements").isFile)
            assertFalse(contents.resolve("Resources/libnucleus_widget_macos.dylib").exists())
            assertEquals(out.resolve("NucleusWidgets.appex"), MacWidgetsPackaging.appExtension(out).appex)

            assertThrows(GradleException::class.java) {
                MacWidgetsPackaging.assemble(widgets, "dev.example.app", "1.0", "x64", listOf(jar(dir, "aarch64")), out)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsBadKindsAndTooManyWidgets() {
        assertThrows(IllegalArgumentException::class.java) { MacWidgetsSettings().widget("2bad") { } }
        val many = MacWidgetsSettings()
        repeat(16) { many.widget("W$it") { } }
        assertThrows(IllegalArgumentException::class.java) { many.widget("W16") { } }
    }

    @Test
    fun warnsWhenTheAppEntitlementsMissTheAppGroup() {
        val listed = "<key>com.apple.security.application-groups</key><array><string>T.com.example</string></array>"
        assertEquals(null, MacWidgetsPackaging.appGroupWarning(null, null))
        assertEquals(null, MacWidgetsPackaging.appGroupWarning("T.com.example", listed))
        assertTrue(MacWidgetsPackaging.appGroupWarning("T.com.example", null)!!.contains("T.com.example"))
        assertTrue(MacWidgetsPackaging.appGroupWarning("T.com.other", listed) != null)
    }
}

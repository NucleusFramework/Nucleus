package dev.nucleusframework.graalvm

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.graalvm.locale.NativeLocaleBridge
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraalVmInitializerTest {
    @Test
    fun `hotspot jvm is not a native image`() {
        assertFalse(GraalVmInitializer.isNativeImage)
        assertEquals(
            System.getProperty("org.graalvm.nativeimage.imagecode") != null,
            GraalVmInitializer.isNativeImage,
        )
    }

    @Test
    fun `initialize is safe on a regular jvm`() {
        GraalVmInitializer.initialize()
        GraalVmInitializer.initialize()
        assertFalse(GraalVmInitializer.isNativeImage)
    }

    @Test
    fun `macos font config is resolved from the bundle Resources dir`() {
        val bundle = Files.createTempDirectory("nucleus-bundle").toFile()
        try {
            val execDir = bundle.resolve("Contents/MacOS")
            val resourcesDir = bundle.resolve("Contents/Resources")
            assertTrue(execDir.mkdirs())
            assertTrue(resourcesDir.mkdirs())

            assertNull(GraalVmInitializer.resolveMacOsFontConfig(execDir))

            // The binary form is never handed to sun.awt.fontconfig: the JDK would parse it as text.
            resourcesDir.resolve("fontconfig.bfc").writeBytes(byteArrayOf(0))
            assertNull(GraalVmInitializer.resolveMacOsFontConfig(execDir))

            val fontConfig = resourcesDir.resolve("fontconfig.properties")
            fontConfig.writeText("version=1\n")
            assertEquals(fontConfig, GraalVmInitializer.resolveMacOsFontConfig(execDir))
            assertEquals(fontConfig, GraalVmInitializer.resolveMacOsFontConfig(File(execDir, ".")))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun `only the sandboxed bundle gets the Frameworks and JNA wiring`() {
        val bundle = Files.createTempDirectory("nucleus-bundle").toFile()
        try {
            val execDir = bundle.resolve("Contents/MacOS")
            assertTrue(execDir.mkdirs())
            assertTrue(bundle.resolve("Contents/Resources").mkdirs())
            // A regular bundle: nothing to wire.
            assertNull(GraalVmInitializer.macOsSandboxedLayout(execDir))

            val manifest = bundle.resolve("Contents/Resources/nucleus-sandbox-manifest.properties")
            manifest.writeText("abc=libjnidispatch.jnilib\n")
            val layout = GraalVmInitializer.macOsSandboxedLayout(File(execDir, "."))
            val frameworks = bundle.resolve("Contents/Frameworks").absoluteFile.normalize()
            assertEquals(frameworks, layout?.librariesDir?.absoluteFile?.normalize())
            val properties = layout!!.properties
            val manifestProperty = File(properties.getValue("nucleus.sandbox.manifest")).normalize()
            assertEquals(manifest.absoluteFile.normalize(), manifestProperty)
            assertEquals("true", properties["jna.nounpack"])
            assertEquals(frameworks.path, File(properties.getValue("jna.boot.library.path")).normalize().path)
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test
    fun `exec dir follows symlinks and relative paths to the real executable`() {
        val root = Files.createTempDirectory("nucleus-exec").toFile().canonicalFile
        try {
            val macOs = root.resolve("App.app/Contents/MacOS").apply { mkdirs() }
            val binary = macOs.resolve("app").apply { writeText("") }
            val link = root.resolve("bin/app")
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), binary.toPath())

            assertEquals(macOs.path, GraalVmInitializer.execDirOf(binary.path))
            assertEquals(macOs.path, GraalVmInitializer.execDirOf(link.path))
            assertEquals(".", GraalVmInitializer.execDirOf(""))
            // ./app started from Contents/MacOS: user.dir when it is right…
            assertEquals(macOs.path, GraalVmInitializer.execDirOf("./app", "/stale/pwd", macOs.path))
            // …PWD when user.dir is not (inside the App Sandbox)…
            assertEquals(macOs.path, GraalVmInitializer.execDirOf("./app", macOs.path, root.path))
            // …and a stale PWD never wins over a user.dir that names the executable.
            assertEquals(macOs.path, GraalVmInitializer.execDirOf("./app", root.path, macOs.path))
            // A relative symlink path: both resolutions apply.
            assertEquals(macOs.path, GraalVmInitializer.execDirOf("bin/app", null, root.path))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `hostile inputs keep the sandbox wiring sane`() {
        val account = Files.createTempDirectory("nucleus account é").toFile()
        try {
            val container = account.resolve("Library/Containers/dev.app/Data").apply { mkdirs() }
            val env = mapOf("APP_SANDBOX_CONTAINER_ID" to "dev.app", "HOME" to container.path + "/")
            // A trailing slash on either side still recognizes SubstrateVM's default.
            assertEquals(container.path + "/", GraalVmInitializer.sandboxedUserHome(env::get, account.path + "/"))
            // A HOME outside any container leaves an explicit user.home alone.
            val odd = mapOf("APP_SANDBOX_CONTAINER_ID" to "dev.app", "HOME" to account.path)
            assertNull(GraalVmInitializer.sandboxedUserHome(odd::get, "/explicit"))

            // A manifest that is a directory is not a sandboxed layout.
            val bundle = account.resolve("My App é.app/Contents")
            val execDir = bundle.resolve("MacOS").apply { mkdirs() }
            bundle.resolve("Resources/nucleus-sandbox-manifest.properties").mkdirs()
            assertNull(GraalVmInitializer.macOsSandboxedLayout(File(execDir, ".")))

            // Spaces and accents in the bundle path; a command naming a missing file.
            val binary = execDir.resolve("my app").apply { writeText("") }
            assertEquals(execDir.canonicalPath, GraalVmInitializer.execDirOf(binary.path))
            assertEquals(execDir.canonicalPath, GraalVmInitializer.execDirOf(execDir.resolve("gone").path))
            assertEquals(
                execDir.canonicalPath,
                GraalVmInitializer.execDirOf("./my app", null, execDir.path),
            )
        } finally {
            account.deleteRecursively()
        }
    }

    @Test
    fun `user home follows the container only inside the sandbox`() {
        val account = Files.createTempDirectory("nucleus-account").toFile()
        try {
            val container = account.resolve("Library/Containers/dev.app/Data").apply { mkdirs() }
            val sandboxed = mapOf("APP_SANDBOX_CONTAINER_ID" to "dev.app", "HOME" to container.path)
            // SubstrateVM's default (the account home) moves to the container.
            assertEquals(container.path, GraalVmInitializer.sandboxedUserHome(sandboxed::get, account.path))
            assertEquals(container.path, GraalVmInitializer.sandboxedUserHome(sandboxed::get, null))
            // An explicit -Duser.home wins.
            assertNull(GraalVmInitializer.sandboxedUserHome(sandboxed::get, "/explicit/home"))
            // Outside the sandbox nothing changes.
            val unsandboxed = mapOf("HOME" to container.path)
            assertNull(GraalVmInitializer.sandboxedUserHome(unsandboxed::get, account.path))
            val missingHome = mapOf("APP_SANDBOX_CONTAINER_ID" to "dev.app", "HOME" to "/nope")
            assertNull(GraalVmInitializer.sandboxedUserHome(missingHome::get, account.path))
        } finally {
            account.deleteRecursively()
        }
    }

    @Test
    fun `macos locale bridge reports a language tag when loaded`() {
        if (Platform.Current != Platform.MacOS) return
        assertTrue(NativeLocaleBridge.isLoaded)
        val tag = NativeLocaleBridge.nativePreferredLanguageTag()
        if (tag != null) {
            assertTrue(tag.isNotBlank())
            assertTrue(tag[0].isLetter())
        }
    }
}

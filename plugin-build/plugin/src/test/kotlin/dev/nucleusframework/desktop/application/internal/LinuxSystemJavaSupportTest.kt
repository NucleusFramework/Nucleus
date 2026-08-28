package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.LinuxSystemJava
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.OS
import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LinuxSystemJavaSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logger = Logging.getLogger(LinuxSystemJavaSupportTest::class.java)

    @Test
    fun `only filesystem packages omit the bundled JRE`() {
        assertTrue(LinuxSystemJavaSupport.appliesTo(TargetFormat.Deb))
        assertTrue(LinuxSystemJavaSupport.appliesTo(TargetFormat.Rpm))
        assertTrue(LinuxSystemJavaSupport.appliesTo(TargetFormat.Pacman))
        assertFalse(LinuxSystemJavaSupport.appliesTo(TargetFormat.AppImage))
        assertFalse(LinuxSystemJavaSupport.appliesTo(TargetFormat.Snap))
        assertFalse(LinuxSystemJavaSupport.appliesTo(TargetFormat.Flatpak))
        assertFalse(LinuxSystemJavaSupport.appliesTo(TargetFormat.Dmg))
    }

    @Test
    fun `skips AOT when every Linux package omits the bundled JRE`() {
        assertTrue(
            LinuxSystemJavaSupport.skipsAotCache(
                LinuxSystemJava.Java21,
                setOf(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Nsis),
                OS.Linux,
            ),
        )
    }

    @Test
    fun `keeps AOT when AppImage is also packaged`() {
        assertFalse(
            LinuxSystemJavaSupport.skipsAotCache(
                LinuxSystemJava.Java21,
                setOf(TargetFormat.Deb, TargetFormat.AppImage),
                OS.Linux,
            ),
        )
    }

    @Test
    fun `does not skip AOT when systemJava is unset`() {
        assertFalse(
            LinuxSystemJavaSupport.skipsAotCache(null, setOf(TargetFormat.Deb), OS.Linux),
        )
    }

    @Test
    fun `does not skip AOT on macOS`() {
        assertFalse(
            LinuxSystemJavaSupport.skipsAotCache(
                LinuxSystemJava.Java21,
                setOf(TargetFormat.Deb, TargetFormat.Dmg),
                OS.MacOS,
            ),
        )
    }

    @Test
    fun `build JDK newer than systemJava is rejected`() {
        val message =
            LinuxSystemJavaSupport.incompatibleBuildJdkMessage(LinuxSystemJava.Java21, 25)
        assertTrue(message!!.contains("Java 21"))
        assertTrue(message.contains("packaging JDK is 25"))
        assertTrue(message.contains("LinuxSystemJava.Java25"))
    }

    @Test
    fun `build JDK equal to or older than systemJava is accepted`() {
        assertEquals(
            null,
            LinuxSystemJavaSupport.incompatibleBuildJdkMessage(LinuxSystemJava.Java21, 21),
        )
        assertEquals(
            null,
            LinuxSystemJavaSupport.incompatibleBuildJdkMessage(LinuxSystemJava.Java25, 21),
        )
        assertEquals(
            null,
            LinuxSystemJavaSupport.incompatibleBuildJdkMessage(LinuxSystemJava.Java17, 17),
        )
    }

    @Test
    fun `mergeDepends prepends the system Java package and keeps user extras`() {
        val merged =
            LinuxSystemJavaSupport.mergeDepends(
                LinuxSystemJava.Java21,
                TargetFormat.Deb,
                listOf("libgtk-3-0"),
            )
        assertEquals(
            listOf("java21-runtime | java-runtime (>= 21)", "libgtk-3-0"),
            merged,
        )
    }

    @Test
    fun `mergeDepends is a no-op for AppImage even when systemJava is set`() {
        assertEquals(
            listOf("libfuse2"),
            LinuxSystemJavaSupport.mergeDepends(
                LinuxSystemJava.Java21,
                TargetFormat.AppImage,
                listOf("libfuse2"),
            ),
        )
    }

    @Test
    fun `rewrite strips the bundled runtime and writes a shell launcher from the cfg`() {
        val appDir = fakeJpackageImage()

        assertTrue(
            LinuxSystemJavaSupport.rewriteAppImage(appDir, LinuxSystemJava.Java21, logger),
        )

        assertFalse(appDir.resolve("lib/runtime").exists())
        assertFalse(appDir.resolve("lib/libapplauncher.so").exists())
        assertFalse(appDir.resolve("lib/app/app.aot").exists())

        val launcher = appDir.resolve("bin/Demo")
        assertTrue(launcher.isFile)
        val script = launcher.readText()
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("com.example.Main"))
        assertTrue(script.contains("\$APPDIR/demo.jar"))
        assertTrue(script.contains("-Dskiko.library.path=\$APPDIR"))
        assertTrue(script.contains("-Dnucleus.executable.type=deb"))
        assertFalse(script.contains("AOTCache"))
        assertTrue(script.contains("[ \"\$_major\" -ge 21 ]"))
        assertTrue(script.contains("libawt_xawt.so"))
        assertFalse(script.contains("--list-modules"))
        assertTrue(launcher.canExecute())
    }

    @Test
    fun `rewrite is skipped when the tree is not a jpackage app-image`() {
        val graalvmDir = tmp.newFolder("SampleCmp")
        graalvmDir.resolve("cmp-sample").writeText("native")

        assertFalse(
            LinuxSystemJavaSupport.rewriteAppImage(graalvmDir, LinuxSystemJava.Java21, logger),
        )
    }

    @Test
    fun `quoteForDoubleQuotes keeps APPDIR expandable and escapes other dollars`() {
        assertEquals(
            "\"-Dskiko.library.path=\$APPDIR\"",
            LinuxSystemJavaSupport.quoteForDoubleQuotes("-Dskiko.library.path=\$APPDIR"),
        )
        assertEquals(
            "\"-Dfoo=\\\$HOME\"",
            LinuxSystemJavaSupport.quoteForDoubleQuotes("-Dfoo=\$HOME"),
        )
    }

    @Test
    fun `generated launcher finds a system Java on this machine`() {
        Assume.assumeTrue("requires /bin/sh", File("/bin/sh").canExecute())
        val javaHome = System.getProperty("java.home")
        Assume.assumeFalse(javaHome.isNullOrBlank())
        Assume.assumeTrue(File(javaHome, "bin/java").canExecute())

        val appDir = fakeJpackageImage()
        LinuxSystemJavaSupport.rewriteAppImage(appDir, LinuxSystemJava.Java21, logger)

        val process =
            ProcessBuilder("/bin/sh", appDir.resolve("bin/Demo").absolutePath)
                .directory(appDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["NUCLEUS_PRINT_JAVA"] = "1"
                    environment()["JAVA_HOME"] = javaHome
                }.start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        assertTrue("launcher timed out", finished)
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("exit ${process.exitValue()}: $output", 0, process.exitValue())
        assertTrue("expected a java binary, got: $output", output.endsWith("/java") || output.endsWith("\\java"))
        assertTrue(File(output).canExecute())
    }

    private fun fakeJpackageImage(): File {
        val appDir = tmp.newFolder("Demo")
        appDir.resolve("bin").mkdirs()
        appDir.resolve("lib/app").mkdirs()
        appDir.resolve("lib/runtime/lib").mkdirs()
        appDir.resolve("bin/Demo").writeText("elf-placeholder")
        appDir.resolve("lib/libapplauncher.so").writeText("so")
        appDir.resolve("lib/runtime/bin/java").apply {
            parentFile.mkdirs()
            writeText("bundled-java")
        }
        appDir.resolve("lib/app/app.aot").writeText("aot")
        appDir.resolve("lib/app/demo.jar").writeText("jar")
        appDir.resolve("lib/app/Demo.cfg").writeText(
            """
            [Application]
            app.classpath=${'$'}APPDIR/demo.jar
            app.mainclass=com.example.Main

            [JavaOptions]
            java-options=-Dskiko.library.path=${'$'}APPDIR
            java-options=-Dnucleus.executable.type=deb
            java-options=-XX:AOTCache=${'$'}APPDIR/app.aot
            java-options=--enable-native-access=ALL-UNNAMED
            """.trimIndent() + "\n",
        )
        return appDir
    }
}

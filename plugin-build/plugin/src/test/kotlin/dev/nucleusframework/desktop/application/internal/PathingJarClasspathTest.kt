package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarFile
import java.util.jar.Attributes

class PathingJarClasspathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logger: Logger = Logging.getLogger(PathingJarClasspathTest::class.java)

    @Test
    fun `collapses multi-entry cfg into a single pathing jar preserving order`() {
        val appDir = tmp.newFolder("lib-app")
        listOf("main.jar", "lib-a.jar", "lib-b.jar").forEach { appDir.resolve(it).writeText("x") }

        val cfg =
            appDir.resolve("Demo.cfg").apply {
                writeText(
                    """
                    [Application]
                    app.classpath=${'$'}APPDIR/main.jar
                    app.mainclass=com.example.Main
                    app.classpath=${'$'}APPDIR/lib-a.jar
                    app.classpath=${'$'}APPDIR/lib-b.jar

                    [JavaOptions]
                    java-options=-Xmx1g
                    """.trimIndent() + "\n",
                )
            }

        assertTrue(PathingJarClasspath.collapseCfg(cfg, appDir, PathingJarClasspath.PATHING_JAR_NAME, logger))

        val lines = cfg.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val classpathLines = lines.filter { it.startsWith("app.classpath=") }
        assertEquals(1, classpathLines.size)
        assertEquals(
            "app.classpath=${'$'}APPDIR/${PathingJarClasspath.PATHING_JAR_NAME}",
            classpathLines.single(),
        )
        assertTrue(lines.contains("app.mainclass=com.example.Main"))
        assertTrue(lines.contains("java-options=-Xmx1g"))

        val pathing = appDir.resolve(PathingJarClasspath.PATHING_JAR_NAME)
        assertTrue(pathing.isFile)
        JarFile(pathing).use { jar ->
            val classPath = jar.manifest.mainAttributes.getValue(Attributes.Name.CLASS_PATH)
            assertEquals("main.jar lib-a.jar lib-b.jar", classPath)
        }
    }

    @Test
    fun `is a no-op when classpath has a single entry`() {
        val appDir = tmp.newFolder("single")
        val cfg =
            appDir.resolve("Demo.cfg").apply {
                writeText(
                    """
                    [Application]
                    app.classpath=${'$'}APPDIR/main.jar
                    app.mainclass=com.example.Main
                    """.trimIndent() + "\n",
                )
            }
        val before = cfg.readText()
        assertFalse(PathingJarClasspath.collapseCfg(cfg, appDir, PathingJarClasspath.PATHING_JAR_NAME, logger))
        assertEquals(before, cfg.readText())
        assertFalse(appDir.resolve(PathingJarClasspath.PATHING_JAR_NAME).exists())
    }

    @Test
    fun `is a no-op when already collapsed`() {
        val appDir = tmp.newFolder("already")
        val cfg =
            appDir.resolve("Demo.cfg").apply {
                writeText(
                    """
                    [Application]
                    app.classpath=${'$'}APPDIR/${PathingJarClasspath.PATHING_JAR_NAME}
                    app.mainclass=com.example.Main
                    """.trimIndent() + "\n",
                )
            }
        assertFalse(PathingJarClasspath.collapseCfg(cfg, appDir, PathingJarClasspath.PATHING_JAR_NAME, logger))
    }

    @Test
    fun `encodes spaces in jar names for the Class-Path attribute`() {
        assertEquals("my%20lib.jar", PathingJarClasspath.encodeClassPathEntry("my lib.jar"))
    }

    @Test
    fun `collapseInLinuxAppImage finds lib-app under the package dir`() {
        val dest = tmp.newFolder("dest")
        val appImage = dest.resolve("DemoApp").apply { mkdirs() }
        val appJarDir = appImage.resolve("lib").resolve("app").apply { mkdirs() }
        listOf("a.jar", "b.jar", "c.jar").forEach { appJarDir.resolve(it).writeText("x") }
        appJarDir.resolve("DemoApp.cfg").writeText(
            """
            [Application]
            app.classpath=${'$'}APPDIR/a.jar
            app.mainclass=Main
            app.classpath=${'$'}APPDIR/b.jar
            app.classpath=${'$'}APPDIR/c.jar
            """.trimIndent() + "\n",
        )

        val rewritten = PathingJarClasspath.collapseInLinuxAppImage(dest, "DemoApp", logger)
        assertEquals(1, rewritten)
        assertTrue(appJarDir.resolve(PathingJarClasspath.PATHING_JAR_NAME).isFile)
        val cp =
            appJarDir
                .resolve("DemoApp.cfg")
                .readLines()
                .filter { it.trim().startsWith("app.classpath=") }
        assertEquals(1, cp.size)
    }

    @Test
    fun `writePathingJar produces a readable empty jar with Class-Path`() {
        val jar = tmp.newFile("pathing.jar")
        PathingJarClasspath.writePathingJar(jar, listOf("one.jar", "two.jar"))
        JarFile(jar).use { jf ->
            assertEquals("one.jar two.jar", jf.manifest.mainAttributes.getValue(Attributes.Name.CLASS_PATH))
            assertEquals(0, jf.entries().asSequence().count { !it.name.startsWith("META-INF/") })
        }
    }
}

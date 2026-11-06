package dev.nucleusframework.desktop.application.internal

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end coverage for issue #439: single disposition table, report-by-default,
 * opt-in remove, exact-package protection, dryRun, and proxy entries.
 */
class CleanupGraalvmMetadataTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeMetadata(configDir: File, json: String): File {
        configDir.mkdirs()
        return File(configDir, "reachability-metadata.json").also { it.writeText(json) }
    }

    private fun classDirWith(fqcn: String): File {
        val classes = tmp.newFolder("classes-${fqcn.hashCode()}-${System.nanoTime()}")
        val path = fqcn.replace('.', '/') + ".class"
        val file = File(classes, path)
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        return classes
    }

    private fun createTask(
        configDir: File,
        classpath: List<File>,
        removeUnresolvable: Boolean = false,
        dryRun: Boolean = false,
        exactPackages: List<String> = emptyList(),
        targetJavaVersion: Int = 25,
    ): CleanupGraalvmMetadataTask {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        return project.tasks
            .register("cleanupGraalvmMetadata", CleanupGraalvmMetadataTask::class.java) { task ->
                task.runtimeClasspath.from(classpath)
                task.platformName.set("linux")
                // Leave mainClass blank so fixture types are not deduped as "main entry".
                task.mainClass.set("")
                task.configDir.set(configDir)
                task.removeUnresolvable.set(removeUnresolvable)
                task.dryRun.set(dryRun)
                task.exactReachabilityPackages.set(exactPackages)
                task.targetJavaVersion.set(targetJavaVersion)
            }.get()
    }

    private fun typeNames(file: File, section: String = "reflection"): List<String> {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(file.readText()) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val entries = root[section] as? List<Map<String, Any?>> ?: return emptyList()
        return entries.mapNotNull { it["type"] as? String }
    }

    /**
     * Package `acme.app` stays outside L1/L3 baselines so only the resolvability pass acts.
     */
    private val fixtureJson =
        """
        {
          "reflection": [
            { "type": "acme.app.RealClass" },
            { "type": "kotlin.Any" },
            { "type": "kotlin.Int" },
            { "type": "kotlin.collections.List" },
            { "type": "acme.app.OptionalProbe" },
            { "type": { "proxy": ["acme.app.RealClass", "acme.app.MissingIface"] } }
          ],
          "serialization": [
            { "type": "kotlin.Boolean" },
            { "type": "acme.app.RealClass" }
          ]
        }
        """.trimIndent()

    @Test
    fun `default mode drops agent noise but keeps other unresolvable probes`() {
        val configDir = tmp.newFolder("graalvm-default")
        val metadata = writeMetadata(configDir, fixtureJson)

        createTask(
            configDir = configDir,
            classpath = listOf(classDirWith("acme.app.RealClass")),
            removeUnresolvable = false,
        ).cleanup()

        val types = typeNames(metadata)
        // Kotlin mapped-type phantoms are always stripped (no removeUnresolvable flag).
        assertFalse(types.contains("kotlin.Any"))
        assertFalse(types.contains("kotlin.Int"))
        assertFalse(types.contains("kotlin.collections.List"))
        // Real app probes stay unless removeUnresolvable is on.
        assertTrue(types.contains("acme.app.RealClass"))
        assertTrue(types.contains("acme.app.OptionalProbe"))
        assertFalse(typeNames(metadata, "serialization").contains("kotlin.Boolean"))
        assertTrue(typeNames(metadata, "serialization").contains("acme.app.RealClass"))
    }

    @Test
    fun `removeUnresolvable drops agent noise and partial proxies, keeps resolvable types`() {
        val configDir = tmp.newFolder("graalvm-remove")
        val metadata = writeMetadata(configDir, fixtureJson)

        createTask(
            configDir = configDir,
            classpath = listOf(classDirWith("acme.app.RealClass")),
            removeUnresolvable = true,
            exactPackages = emptyList(),
        ).cleanup()

        val reflection = typeNames(metadata, "reflection")
        val serialization = typeNames(metadata, "serialization")

        assertTrue(reflection.contains("acme.app.RealClass"))
        assertFalse(reflection.contains("kotlin.Any"))
        assertFalse(reflection.contains("kotlin.Int"))
        assertFalse(reflection.contains("kotlin.collections.List"))
        assertFalse(reflection.contains("acme.app.OptionalProbe"))

        // Partial proxy (MissingIface) must be gone.
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(metadata.readText()) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val entries = root["reflection"] as List<Map<String, Any?>>
        assertTrue(entries.none { proxyInterfaceNames(it).isNotEmpty() })

        assertFalse(serialization.contains("kotlin.Boolean"))
        assertTrue(serialization.contains("acme.app.RealClass"))
    }

    @Test
    fun `exact reachability packages protect unresolvable probes and proxies under scope`() {
        val configDir = tmp.newFolder("graalvm-exact")
        val metadata = writeMetadata(configDir, fixtureJson)

        createTask(
            configDir = configDir,
            classpath = listOf(classDirWith("acme.app.RealClass")),
            removeUnresolvable = true,
            exactPackages = listOf("acme.app"),
        ).cleanup()

        val reflection = typeNames(metadata, "reflection")
        assertTrue(reflection.contains("acme.app.OptionalProbe"))
        assertTrue(reflection.contains("acme.app.RealClass"))
        assertFalse(reflection.contains("kotlin.Any"))
        assertFalse(reflection.contains("kotlin.Int"))

        // Proxy with any interface under acme.app is protected.
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(metadata.readText()) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val entries = root["reflection"] as List<Map<String, Any?>>
        assertTrue(entries.any { proxyInterfaceNames(it).contains("acme.app.MissingIface") })
    }

    @Test
    fun `dryRun never rewrites the file even with removeUnresolvable`() {
        val configDir = tmp.newFolder("graalvm-dry")
        val metadata = writeMetadata(configDir, fixtureJson)
        val before = metadata.readText()

        createTask(
            configDir = configDir,
            classpath = listOf(classDirWith("acme.app.RealClass")),
            removeUnresolvable = true,
            dryRun = true,
        ).cleanup()

        assertEquals(before, metadata.readText())
    }

    @Test
    fun `missing metadata file is a no-op`() {
        val configDir = tmp.newFolder("graalvm-empty")
        createTask(
            configDir = configDir,
            classpath = emptyList(),
        ).cleanup()
        assertFalse(File(configDir, "reachability-metadata.json").exists())
    }

    @Test
    fun `default mode keeps app probes while stripping agent noise`() {
        val configDir = tmp.newFolder("graalvm-msg")
        writeMetadata(configDir, fixtureJson)
        val task =
            createTask(
                configDir = configDir,
                classpath = listOf(classDirWith("acme.app.RealClass")),
                removeUnresolvable = false,
            )

        task.cleanup()
        val types = typeNames(File(configDir, "reachability-metadata.json"))
        // App-level unresolvable probes stay (report-only without removeUnresolvable).
        assertTrue(types.contains("acme.app.OptionalProbe"))
        assertTrue(types.contains("acme.app.RealClass"))
        // Agent phantoms are always gone.
        assertFalse(types.contains("kotlin.Int"))
        assertFalse(types.contains("kotlin.Any"))
    }

    @Test
    fun `a type only in a higher versions directory of a multi-release JAR is unresolvable`() {
        val configDir = tmp.newFolder("graalvm-multi-release")
        val metadata =
            writeMetadata(
                configDir,
                """
                {
                  "reflection": [
                    { "type": "demo.Upcalls" },
                    { "type": "demo.ffm.UpcallBinder" },
                    { "type": "demo.next.FutureApi" }
                  ]
                }
                """.trimIndent(),
            )

        createTask(
            configDir = configDir,
            classpath = listOf(multiReleaseJar(tmp.newFile("multi-release.jar"))),
            removeUnresolvable = true,
            targetJavaVersion = 25,
        ).cleanup()

        assertEquals(listOf("demo.Upcalls", "demo.ffm.UpcallBinder"), typeNames(metadata))
    }

    @Test
    fun `library metadata counts only from the JAR root META-INF native-image`() {
        val configDir = tmp.newFolder("graalvm-library-baseline")
        val metadata =
            writeMetadata(
                configDir,
                """
                {
                  "reflection": [
                    { "type": "acme.app.FromRoot" },
                    { "type": "acme.app.FromVersioned" }
                  ]
                }
                """.trimIndent(),
            )
        val library = tmp.newFile("library.jar")
        java.util.jar.JarOutputStream(library.outputStream()).use { out ->
            out.putNextEntry(java.util.jar.JarEntry("META-INF/native-image/acme/lib/reachability-metadata.json"))
            out.write("""{ "reflection": [ { "type": "acme.app.FromRoot" } ] }""".toByteArray())
            out.closeEntry()
            // native-image never discovers this copy, so it covers nothing.
            out.putNextEntry(
                java.util.jar.JarEntry("META-INF/versions/25/META-INF/native-image/acme/lib/reachability-metadata.json"),
            )
            out.write("""{ "reflection": [ { "type": "acme.app.FromVersioned" } ] }""".toByteArray())
            out.closeEntry()
        }

        createTask(
            configDir = configDir,
            classpath = listOf(library, classDirWith("acme.app.FromRoot"), classDirWith("acme.app.FromVersioned")),
        ).cleanup()

        assertEquals(listOf("acme.app.FromVersioned"), typeNames(metadata))
    }
}

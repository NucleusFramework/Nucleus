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
    fun `default mode reports unresolvable once and does not rewrite them away`() {
        val configDir = tmp.newFolder("graalvm-default")
        val metadata = writeMetadata(configDir, fixtureJson)
        val before = metadata.readText()

        createTask(
            configDir = configDir,
            classpath = listOf(classDirWith("acme.app.RealClass")),
            removeUnresolvable = false,
        ).cleanup()

        assertEquals("file must not drop unresolvable types by default", before, metadata.readText())
        val types = typeNames(metadata)
        assertTrue(types.contains("kotlin.Any"))
        assertTrue(types.contains("kotlin.Int"))
        assertTrue(types.contains("acme.app.RealClass"))
        assertTrue(types.contains("acme.app.OptionalProbe"))
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
    fun `default mode does not claim already clean when unresolvable remain`() {
        val configDir = tmp.newFolder("graalvm-msg")
        writeMetadata(configDir, fixtureJson)
        val task =
            createTask(
                configDir = configDir,
                classpath = listOf(classDirWith("acme.app.RealClass")),
                removeUnresolvable = false,
            )

        // Drive the action; assert on post-condition that is independent of logger wiring:
        // file still has unresolvable entries, so the task's success path for "already clean"
        // must not have rewritten. Message content is covered by classify + e2e gradle log.
        task.cleanup()
        assertTrue(typeNames(File(configDir, "reachability-metadata.json")).contains("kotlin.Int"))
        // Kept resolvable + unresolvable still present — not "clean" by deletion.
        assertEquals(5, typeNames(File(configDir, "reachability-metadata.json")).size)
    }
}

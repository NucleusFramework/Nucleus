package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors.ServiceLoaderDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

class ServiceLoaderDetectorTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    private fun findJar(nameContains: String): File? = analysisLibraries.find { it.name.contains(nameContains) }

    @Test
    fun `detects service providers in logback classic jar`() {
        val logbackJar = findJar("logback-classic")
        assumeTrue("Logback classic JAR not available", logbackJar != null)

        val result = BytecodeAnalyzer.analyzeJar(logbackJar!!)
        val serviceTypes = result.serviceLoaderEntries.map { it.type }.toSet()

        // Logback registers SLF4J service providers
        assertTrue(
            "Expected to find service loader entries in logback, got: $serviceTypes",
            serviceTypes.isNotEmpty(),
        )
    }

    @Test
    fun `service file parser handles comments and blank lines`() {
        val input =
            """
            # This is a comment
            com.example.ImplA

            # Another comment
            com.example.ImplB # inline comment
            """.trimIndent().byteInputStream()

        val implementations = ServiceLoaderDetector.parseServiceFile(input)
        assertTrue(implementations.contains("com.example.ImplA"))
        assertTrue(implementations.contains("com.example.ImplB"))
        assertFalse(implementations.any { it.startsWith("#") })
    }

    @Test
    fun `service loader detection produces resource patterns`() {
        val logbackJar = findJar("logback-classic")
        assumeTrue("Logback classic JAR not available", logbackJar != null)

        JarFile(logbackJar!!).use { jar ->
            val result = ServiceLoaderDetector.detect(jar)

            // Should produce resource patterns for META-INF/services/ files
            val serviceGlobs = result.resourcePatterns.mapNotNull { it.glob }
            assertTrue(
                "Expected META-INF/services/ resource patterns, got: $serviceGlobs",
                serviceGlobs.any { it.startsWith("META-INF/services/") },
            )

            // Each implementation should have a no-arg constructor entry
            for (entry in result.reflectionEntries) {
                assertTrue(
                    "Service implementation ${entry.type} should have <init>() method",
                    entry.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() },
                )
            }
        }
    }
}

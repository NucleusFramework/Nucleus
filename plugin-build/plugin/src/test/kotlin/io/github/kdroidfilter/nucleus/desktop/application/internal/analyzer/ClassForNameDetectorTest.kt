package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors.ClassForNameDetector
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors.isValidClassName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

class ClassForNameDetectorTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    @Test
    fun `detects Class forName calls in JNA jar`() {
        val jnaJar = analysisLibraries.find { it.name.contains("jna-") }
        assumeTrue("JNA JAR not available", jnaJar != null)

        var foundClassForName = false
        JarFile(jnaJar!!).use { jar ->
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                val entries = ClassForNameDetector.detect(bytes)
                if (entries.isNotEmpty()) {
                    foundClassForName = true
                    // All detected entries should have valid class names
                    for (e in entries) {
                        assertTrue(
                            "Detected class name should be valid: ${e.type}",
                            isValidClassName(e.type),
                        )
                    }
                }
            }
        }
        // JNA uses Class.forName extensively
        assertTrue("Expected to find Class.forName calls in JNA", foundClassForName)
    }

    @Test
    fun `detects Class forName calls across all test libraries`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val allEntries = mutableSetOf<ReflectionEntry>()
        for (jarFile in analysisLibraries) {
            if (!jarFile.name.endsWith(".jar")) continue
            val result = BytecodeAnalyzer.analyzeJar(jarFile)
            allEntries.addAll(result.reflectionEntries)
        }

        assertTrue(
            "Expected to find some Class.forName entries across test libraries",
            allEntries.isNotEmpty(),
        )
    }

    @Test
    fun `isValidClassName rejects invalid names`() {
        assertFalse(isValidClassName(""))
        assertFalse(isValidClassName("  "))
        assertFalse(isValidClassName("hello world"))
        assertFalse(isValidClassName("not a class"))
    }

    @Test
    fun `isValidClassName accepts valid names`() {
        assertTrue(isValidClassName("com.example.MyClass"))
        assertTrue(isValidClassName("java.lang.String"))
        assertTrue(isValidClassName("com.sun.jna.Native"))
        assertTrue(isValidClassName("org.slf4j.LoggerFactory"))
    }
}

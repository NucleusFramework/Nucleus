package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReflectionApiDetectorTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    @Test
    fun `detects reflection API usage across test libraries`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val allResults =
            analysisLibraries
                .filter { it.name.endsWith(".jar") }
                .map { BytecodeAnalyzer.analyzeJar(it) }
                .fold(AnalysisResult()) { acc, r -> acc + r }

        // Reflection API detection should find some entries across the test libs
        // JNA and logback both use reflection extensively
        val reflectionEntries = allResults.reflectionEntries
        assertTrue(
            "Expected to find reflection API entries across test libraries, got ${reflectionEntries.size}",
            reflectionEntries.isNotEmpty(),
        )
    }

    @Test
    fun `detected entries have valid type names`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        for (jarFile in analysisLibraries) {
            if (!jarFile.name.endsWith(".jar")) continue
            val result = BytecodeAnalyzer.analyzeJar(jarFile)
            for (entry in result.reflectionEntries) {
                assertTrue(
                    "Reflection entry type should not be blank: ${entry.type}",
                    entry.type.isNotBlank(),
                )
            }
        }
    }
}

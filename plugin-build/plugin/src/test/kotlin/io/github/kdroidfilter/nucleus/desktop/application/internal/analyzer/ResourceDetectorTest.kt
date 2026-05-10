package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ResourceDetectorTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    @Test
    fun `detects resource access patterns in logback`() {
        val logbackJar = analysisLibraries.find { it.name.contains("logback-classic") }
        assumeTrue("Logback classic JAR not available", logbackJar != null)

        val result = BytecodeAnalyzer.analyzeJar(logbackJar!!)
        val allResources = result.resourcePatterns
        val globs = allResources.mapNotNull { it.glob }
        val bundles = allResources.mapNotNull { it.bundle }

        // Logback accesses various resources
        assertTrue(
            "Expected to find resource patterns in logback, got globs=$globs, bundles=$bundles",
            allResources.isNotEmpty(),
        )
    }

    @Test
    fun `resource patterns have valid paths`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        for (jarFile in analysisLibraries) {
            if (!jarFile.name.endsWith(".jar")) continue
            val result = BytecodeAnalyzer.analyzeJar(jarFile)
            for (pattern in result.resourcePatterns) {
                if (pattern.glob != null) {
                    assertTrue(
                        "Resource glob should not be blank: ${pattern.glob}",
                        pattern.glob.isNotBlank(),
                    )
                    // Should not have leading slash (normalized)
                    assertTrue(
                        "Resource glob should not start with /: ${pattern.glob}",
                        !pattern.glob.startsWith("/"),
                    )
                }
                if (pattern.bundle != null) {
                    assertTrue(
                        "Bundle name should not be blank: ${pattern.bundle}",
                        pattern.bundle.isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun `detects resource bundles across test libraries`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val allResults =
            analysisLibraries
                .filter { it.name.endsWith(".jar") }
                .map { BytecodeAnalyzer.analyzeJar(it) }
                .fold(AnalysisResult()) { acc, r -> acc + r }

        // At least some of our test libraries should use resources or bundles
        assertTrue(
            "Expected to find some resource patterns across test libraries",
            allResults.resourcePatterns.isNotEmpty(),
        )
    }
}

package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BytecodeAnalyzerIntegrationTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    private val oracleRepoZip: File? by lazy {
        System.getProperty("test.oracle.repo.zip")?.let { File(it) }?.takeIf { it.exists() }
    }

    @Test
    fun `full analysis of all test libraries produces non-empty result`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val result = BytecodeAnalyzer.analyzeJars(analysisLibraries.filter { it.name.endsWith(".jar") })

        assertTrue("Expected JNI entries", result.jniEntries.isNotEmpty())
        assertTrue("Expected reflection entries", result.allReflectionEntries.isNotEmpty())
        assertTrue("Expected resource patterns", result.resourcePatterns.isNotEmpty())
    }

    @Test
    fun `JNA analysis detects core JNI types from Oracle config`() {
        val jnaJar = analysisLibraries.find { it.name.contains("jna-") }
        assumeTrue("JNA JAR not available", jnaJar != null)
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)

        val analyzerResult = BytecodeAnalyzer.analyzeJar(jnaJar!!)
        val oracleDir = NativeMethodDetectorTest.extractOracleRepoDir(oracleRepoZip!!, "net.java.dev.jna", "jna")
        assumeTrue("Oracle metadata for JNA not found", oracleDir != null)

        val oracleResult = OracleRepoParser.parseMetadataDir(oracleDir!!)

        // Compare JNI — the Oracle config includes types accessed FROM native code too,
        // which static analysis can't fully cover. Verify the gap report works.
        val report = ResultComparator.compareJni(analyzerResult, oracleResult)
        assertNotNull(report)

        // Should have at least some detected or partially detected types (classes with ACC_NATIVE)
        val found = report.detected.size + report.partiallyDetected.size
        assertTrue(
            "Should detect or partially detect at least some Oracle JNI types, found: $found",
            found > 0,
        )

        // NOT_DETECTABLE entries should have reasons
        for (entry in report.notDetectable) {
            assertNotNull("NOT_DETECTABLE entry ${entry.type} should have reason", entry.reason)
        }
    }

    @Test
    fun `logback analysis detects service loader entries`() {
        val logbackJar = analysisLibraries.find { it.name.contains("logback-classic") }
        assumeTrue("Logback classic JAR not available", logbackJar != null)

        val result = BytecodeAnalyzer.analyzeJar(logbackJar!!)

        assertTrue(
            "Expected service loader entries for logback",
            result.serviceLoaderEntries.isNotEmpty(),
        )

        // All service implementations should have <init>() method
        for (entry in result.serviceLoaderEntries) {
            assertTrue(
                "Service ${entry.type} should have no-arg constructor",
                entry.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() },
            )
        }
    }

    @Test
    fun `gap report correctly categorizes entries`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)

        val jnaJar = analysisLibraries.find { it.name.contains("jna-") }
        assumeTrue("JNA JAR not available", jnaJar != null)

        val analyzerResult = BytecodeAnalyzer.analyzeJar(jnaJar!!)
        val oracleDir = NativeMethodDetectorTest.extractOracleRepoDir(oracleRepoZip!!, "net.java.dev.jna", "jna")
        assumeTrue("Oracle metadata for JNA not found", oracleDir != null)

        val oracleResult = OracleRepoParser.parseMetadataDir(oracleDir!!)
        val report = ResultComparator.compare(analyzerResult, oracleResult)

        // Report should have entries in at least some categories
        assertFalse("Report should not be empty", report.entries.isEmpty())

        // Verify report formatting works
        val formatted = report.format()
        assertTrue("Formatted report should contain summary", formatted.contains("Summary:"))
        assertTrue("Formatted report should contain DETECTED count", formatted.contains("DETECTED:"))

        // Each NOT_DETECTABLE entry should have a reason
        for (entry in report.notDetectable) {
            assertNotNull(
                "NOT_DETECTABLE entry ${entry.type} should have a reason",
                entry.reason,
            )
            assertTrue(
                "Reason should not be blank for ${entry.type}",
                entry.reason!!.isNotBlank(),
            )
        }
    }

    @Test
    fun `analyzer finds extra entries beyond Oracle baseline`() {
        val jnaJar = analysisLibraries.find { it.name.contains("jna-") }
        assumeTrue("JNA JAR not available", jnaJar != null)
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)

        val analyzerResult = BytecodeAnalyzer.analyzeJar(jnaJar!!)
        val oracleDir = NativeMethodDetectorTest.extractOracleRepoDir(oracleRepoZip!!, "net.java.dev.jna", "jna")
        assumeTrue("Oracle metadata for JNA not found", oracleDir != null)

        val oracleResult = OracleRepoParser.parseMetadataDir(oracleDir!!)
        val report = ResultComparator.compare(analyzerResult, oracleResult)

        // Static analysis should find things the Oracle repo doesn't cover
        assertTrue(
            "Analyzer should find extra entries beyond Oracle baseline, found ${report.extra.size}",
            report.extra.isNotEmpty(),
        )
    }

    @Test
    fun `AnalysisResult plus operator merges correctly`() {
        val r1 =
            AnalysisResult(
                reflectionEntries = setOf(ReflectionEntry(type = "com.example.A")),
                jniEntries = setOf(JniEntry(type = "com.example.B")),
            )
        val r2 =
            AnalysisResult(
                reflectionEntries = setOf(ReflectionEntry(type = "com.example.C")),
                resourcePatterns = setOf(ResourcePattern(glob = "some/resource.txt")),
            )

        val merged = r1 + r2
        assertTrue(merged.reflectionEntries.size == 2)
        assertTrue(merged.jniEntries.size == 1)
        assertTrue(merged.resourcePatterns.size == 1)
    }
}

package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Comprehensive test that runs the static bytecode analyzer against every library
 * in the Oracle GraalVM Reachability Metadata Repository and compares the results.
 *
 * Produces a detailed coverage report showing how much reflection/JNI metadata
 * the static analyzer can discover compared to the Oracle manually-curated baseline.
 */
class OracleRepoCoverageTest {
    private val analysisLibraries: Map<String, File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        val jars = path.split(File.pathSeparator).map(::File).filter { it.exists() && it.name.endsWith(".jar") }
        jars.associateBy { jarToArtifactKey(it) }
    }

    private val oracleRepoZip: File? by lazy {
        System.getProperty("test.oracle.repo.zip")?.let { File(it) }?.takeIf { it.exists() }
    }

    private fun extractOracleRepo(): Map<String, OracleLibraryMeta> {
        val zipFile = oracleRepoZip ?: return emptyMap()
        val tempDir =
            kotlin.io.path
                .createTempDirectory("oracle-full-repo-")
                .toFile()

        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val target = File(tempDir, entry.name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val result = mutableMapOf<String, OracleLibraryMeta>()
        tempDir
            .walkTopDown()
            .filter { it.name == "index.json" }
            .forEach { indexFile ->
                val artifactDir = indexFile.parentFile
                val artifact = artifactDir.name
                val group = artifactDir.parentFile.name
                val key = "$group:$artifact"

                val versionDirs =
                    artifactDir
                        .listFiles()
                        ?.filter { it.isDirectory }
                        ?.filter { dir ->
                            dir.listFiles()?.any {
                                it.name.endsWith("-config.json") || it.name == "reachability-metadata.json"
                            } == true
                        } ?: emptyList()

                if (versionDirs.isNotEmpty()) {
                    result[key] = OracleLibraryMeta(group, artifact, versionDirs)
                }
            }

        return result
    }

    private fun mergeOracleMetadata(meta: OracleLibraryMeta): AnalysisResult {
        var merged = AnalysisResult()
        for (dir in meta.metadataDirs) {
            merged = merged + OracleRepoParser.parseMetadataDir(dir)
        }
        return merged
    }

    private fun findMatchingJar(oracleKey: String): File? {
        analysisLibraries[oracleKey]?.let { return it }
        val artifact = oracleKey.substringAfter(":")
        return analysisLibraries.entries
            .firstOrNull { it.key.endsWith(":$artifact") }
            ?.value
    }

    @Test
    fun `comprehensive Oracle repo coverage analysis`() {
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val oracleLibraries = extractOracleRepo()
        assertTrue("Oracle repo should contain libraries", oracleLibraries.isNotEmpty())

        val results = mutableListOf<CoverageRow>()
        var matchedCount = 0
        var unmatchedOracle = 0

        for ((key, oracleMeta) in oracleLibraries.entries.sortedBy { it.key }) {
            val jarFile = findMatchingJar(key)
            if (jarFile == null) {
                unmatchedOracle++
                continue
            }
            matchedCount++

            val analyzerResult = BytecodeAnalyzer.analyzeJar(jarFile)
            val oracleResult = mergeOracleMetadata(oracleMeta)
            val reflReport = ResultComparator.compareReflection(analyzerResult, oracleResult)
            val jniReport = ResultComparator.compareJni(analyzerResult, oracleResult)

            results.add(
                CoverageRow(
                    key = key,
                    oraRef = oracleResult.reflectionEntries.size,
                    detRef = reflReport.detected.size,
                    parRef = reflReport.partiallyDetected.size,
                    noRef = reflReport.notDetectable.size,
                    oraJni = oracleResult.jniEntries.size,
                    detJni = jniReport.detected.size,
                    parJni = jniReport.partiallyDetected.size,
                    noJni = jniReport.notDetectable.size,
                    extra = reflReport.extra.size + jniReport.extra.size,
                    svcLd = analyzerResult.serviceLoaderEntries.size,
                    resPat = analyzerResult.resourcePatterns.size,
                ),
            )
        }

        printReport(results, matchedCount, unmatchedOracle, oracleLibraries.size)

        assertTrue(
            "Should match at least 30 Oracle repo libraries, but matched only $matchedCount",
            matchedCount >= 30,
        )

        val totalExtra = results.sumOf { it.extra }
        println("\nTotal EXTRA discoveries: $totalExtra")
    }

    @Test
    fun `per-library analysis does not crash and produces valid gap reasons`() {
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val oracleLibraries = extractOracleRepo()
        val failures = mutableListOf<String>()

        for ((key, oracleMeta) in oracleLibraries) {
            val jarFile = findMatchingJar(key) ?: continue

            try {
                val analyzerResult = BytecodeAnalyzer.analyzeJar(jarFile)
                val oracleResult = mergeOracleMetadata(oracleMeta)
                val report = ResultComparator.compare(analyzerResult, oracleResult)

                for (entry in report.notDetectable) {
                    if (entry.reason.isNullOrBlank()) {
                        failures.add("$key: NOT_DETECTABLE entry ${entry.type} missing reason")
                    }
                }
            } catch (e: Exception) {
                failures.add("$key: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("Failures:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `static analysis finds extra entries beyond Oracle baseline`() {
        assumeTrue("Oracle repo ZIP not available", oracleRepoZip != null)
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        val oracleLibraries = extractOracleRepo()
        var totalExtra = 0

        for ((key, oracleMeta) in oracleLibraries) {
            val jarFile = findMatchingJar(key) ?: continue
            val analyzerResult = BytecodeAnalyzer.analyzeJar(jarFile)
            val oracleResult = mergeOracleMetadata(oracleMeta)
            val report = ResultComparator.compare(analyzerResult, oracleResult)
            totalExtra += report.extra.size
        }

        println("Total EXTRA entries found beyond Oracle baseline: $totalExtra")
        assertTrue("Static analysis should find extra entries", totalExtra > 0)
    }

    @Test
    fun `service loader detection covers libraries with META-INF services`() {
        assumeTrue("No analysis libraries available", analysisLibraries.isNotEmpty())

        var librariesWithServices = 0
        var totalServiceEntries = 0

        for ((_, jarFile) in analysisLibraries) {
            val result = BytecodeAnalyzer.analyzeJar(jarFile)
            if (result.serviceLoaderEntries.isNotEmpty()) {
                librariesWithServices++
                totalServiceEntries += result.serviceLoaderEntries.size
            }
        }

        println("Libraries with ServiceLoader entries: $librariesWithServices / ${analysisLibraries.size}")
        println("Total ServiceLoader implementations: $totalServiceEntries")
        assertTrue("Should find ServiceLoader entries", totalServiceEntries > 0)
    }

    // ----- report -----

    private fun printReport(
        results: List<CoverageRow>,
        matched: Int,
        unmatched: Int,
        total: Int,
    ) {
        val sep = "=".repeat(130)
        println(sep)
        println("ORACLE REACHABILITY METADATA REPOSITORY — STATIC ANALYZER COVERAGE REPORT")
        println(sep)
        println("Libraries in Oracle repo: $total | Matched: $matched | Unmatched: $unmatched")
        println()

        val header = "%-50s | %5s | %5s | %5s | %5s | %5s | %5s | %5s | %5s | %5s | %5s | %5s | %5s"
        println(
            header.format(
                "Library",
                "OraRf",
                "DetRf",
                "ParRf",
                "NoRf",
                "Rf%",
                "OraJn",
                "DetJn",
                "ParJn",
                "NoJn",
                "Extra",
                "Svc",
                "Res",
            ),
        )
        println("-".repeat(130))

        val sorted = results.sortedByDescending { it.oraRef + it.oraJni }
        for (r in sorted) {
            val refPct = if (r.oraRef == 0) "N/A" else "${(r.detRef + r.parRef) * 100 / r.oraRef}%"
            println(
                header.format(
                    r.key.take(50),
                    r.oraRef,
                    r.detRef,
                    r.parRef,
                    r.noRef,
                    refPct,
                    r.oraJni,
                    r.detJni,
                    r.parJni,
                    r.noJni,
                    r.extra,
                    r.svcLd,
                    r.resPat,
                ),
            )
        }

        println("-".repeat(130))

        val tOraRef = results.sumOf { it.oraRef }
        val tDetRef = results.sumOf { it.detRef }
        val tParRef = results.sumOf { it.parRef }
        val tNoRef = results.sumOf { it.noRef }
        val tOraJni = results.sumOf { it.oraJni }
        val tDetJni = results.sumOf { it.detJni }
        val tParJni = results.sumOf { it.parJni }
        val tNoJni = results.sumOf { it.noJni }
        val tExtra = results.sumOf { it.extra }
        val tSvc = results.sumOf { it.svcLd }
        val tRes = results.sumOf { it.resPat }
        val refPctTotal = if (tOraRef == 0) "N/A" else "${(tDetRef + tParRef) * 100 / tOraRef}%"

        println(
            header.format(
                "TOTAL",
                tOraRef,
                tDetRef,
                tParRef,
                tNoRef,
                refPctTotal,
                tOraJni,
                tDetJni,
                tParJni,
                tNoJni,
                tExtra,
                tSvc,
                tRes,
            ),
        )
        println(sep)
    }

    // ----- data -----

    private data class OracleLibraryMeta(
        val group: String,
        val artifact: String,
        val metadataDirs: List<File>,
    )

    private data class CoverageRow(
        val key: String,
        val oraRef: Int,
        val detRef: Int,
        val parRef: Int,
        val noRef: Int,
        val oraJni: Int,
        val detJni: Int,
        val parJni: Int,
        val noJni: Int,
        val extra: Int,
        val svcLd: Int,
        val resPat: Int,
    )

    companion object {
        /**
         * Maps a JAR file to "group:artifact" from the Gradle cache path structure:
         * .../group/artifact/version/hash/artifact-version.jar
         */
        fun jarToArtifactKey(jar: File): String {
            val parts = jar.absolutePath.split(File.separator)
            val hashIdx = parts.indexOfLast { it.length == 40 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            if (hashIdx >= 4) {
                val artifact = parts[hashIdx - 2]
                val group = parts[hashIdx - 3]
                return "$group:$artifact"
            }
            return "unknown:${jar.nameWithoutExtension}"
        }
    }
}

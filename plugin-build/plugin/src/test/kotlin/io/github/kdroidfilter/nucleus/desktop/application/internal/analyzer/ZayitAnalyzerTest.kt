package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-world integration test: runs the static bytecode analyzer on the Zayit (SeforimApp)
 * runtime dependencies and compares against the project's existing reachability-metadata.json.
 *
 * This validates that the analyzer works on a production Compose Desktop + Lucene + SQLite app.
 */
class ZayitAnalyzerTest {
    private val zayitLibraries: List<File> by lazy {
        val path = System.getProperty("test.zayit.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() && it.name.endsWith(".jar") }
    }

    private val zayitMetadataDir: File? by lazy {
        System.getProperty("test.zayit.metadata.dir")?.let { File(it) }?.takeIf { it.exists() }
    }

    @Test
    fun `analyzer runs on all Zayit dependencies without errors`() {
        assumeTrue("Zayit libraries not available", zayitLibraries.isNotEmpty())

        val result = BytecodeAnalyzer.analyzeJars(zayitLibraries)

        println("=== ZAYIT STATIC ANALYSIS SUMMARY ===")
        println("JARs analyzed:              ${zayitLibraries.size}")
        println("JNI entries found:          ${result.jniEntries.size}")
        println("Reflection entries found:   ${result.reflectionEntries.size}")
        println("ServiceLoader entries:      ${result.serviceLoaderEntries.size}")
        println("Resource patterns found:    ${result.resourcePatterns.size}")
        println("Total reflection (all):     ${result.allReflectionEntries.size}")

        assertTrue("Should find JNI entries", result.jniEntries.isNotEmpty())
        assertTrue("Should find reflection entries", result.allReflectionEntries.isNotEmpty())
        assertTrue("Should find resource patterns", result.resourcePatterns.isNotEmpty())
    }

    @Test
    fun `per-jar analysis breakdown`() {
        assumeTrue("Zayit libraries not available", zayitLibraries.isNotEmpty())

        println()
        println("=".repeat(110))
        println("ZAYIT PER-JAR ANALYSIS BREAKDOWN")
        println("=".repeat(110))

        val header = "%-55s | %5s | %5s | %5s | %5s | %5s"
        println(header.format("JAR", "JNI", "Refl", "SvcLd", "Res", "CfN"))
        println("-".repeat(110))

        var totalJni = 0
        var totalRefl = 0
        var totalSvc = 0
        var totalRes = 0
        var totalCfn = 0

        for (jar in zayitLibraries.sortedBy { it.name }) {
            val result = BytecodeAnalyzer.analyzeJar(jar)
            val jni = result.jniEntries.size
            val refl = result.reflectionEntries.size
            val svc = result.serviceLoaderEntries.size
            val res = result.resourcePatterns.size
            val cfn = result.allReflectionEntries.size - refl // ClassForName vs other reflection

            totalJni += jni
            totalRefl += refl
            totalSvc += svc
            totalRes += res
            totalCfn += cfn

            if (jni + refl + svc + res > 0) {
                println(header.format(jar.name.take(55), jni, refl, svc, res, cfn))
            }
        }

        println("-".repeat(110))
        println(header.format("TOTAL", totalJni, totalRefl, totalSvc, totalRes, totalCfn))
        println("=".repeat(110))
    }

    @Test
    fun `compare analyzer output with existing Zayit reachability-metadata`() {
        assumeTrue("Zayit libraries not available", zayitLibraries.isNotEmpty())
        assumeTrue("Zayit metadata dir not available", zayitMetadataDir != null)

        val metadataFile = File(zayitMetadataDir!!, "reachability-metadata.json")
        assumeTrue("reachability-metadata.json not found", metadataFile.exists())

        // Parse existing metadata
        val existingMetadata = OracleRepoParser.parseReachabilityMetadata(metadataFile)

        // Run analyzer on all Zayit JARs
        val analyzerResult = BytecodeAnalyzer.analyzeJars(zayitLibraries)

        // Compare reflection
        val existingReflTypes = existingMetadata.reflectionEntries.map { it.type }.toSet()
        val analyzerReflTypes = analyzerResult.allReflectionEntries.map { it.type }.toSet()

        val coveredByAnalyzer = existingReflTypes.intersect(analyzerReflTypes)
        val onlyInExisting = existingReflTypes - analyzerReflTypes
        val onlyInAnalyzer = analyzerReflTypes - existingReflTypes

        println()
        println("=".repeat(90))
        println("ZAYIT: ANALYZER vs EXISTING reachability-metadata.json")
        println("=".repeat(90))
        println()
        println("Existing metadata reflection types:   ${existingReflTypes.size}")
        println("Analyzer-detected reflection types:    ${analyzerReflTypes.size}")
        println("Overlap (both found):                  ${coveredByAnalyzer.size}")
        println("Only in existing metadata:             ${onlyInExisting.size}")
        println("Only found by analyzer (NEW):          ${onlyInAnalyzer.size}")

        val coveragePct =
            if (existingReflTypes.isEmpty()) {
                0
            } else {
                coveredByAnalyzer.size * 100 / existingReflTypes.size
            }
        println("Coverage of existing metadata:         $coveragePct%")

        // Show what the analyzer found that the existing metadata doesn't have
        if (onlyInAnalyzer.isNotEmpty()) {
            println()
            println("--- NEW entries found by static analysis (not in existing metadata) ---")
            val grouped = onlyInAnalyzer.groupBy { it.substringBeforeLast('.', "unknown") }
            for ((pkg, types) in grouped.entries.sortedByDescending { it.value.size }.take(20)) {
                println("  $pkg (${types.size} types)")
                for (t in types.take(5)) {
                    println("    - $t")
                }
                if (types.size > 5) println("    ... and ${types.size - 5} more")
            }
        }

        // Show types from existing metadata that the analyzer couldn't detect
        if (onlyInExisting.isNotEmpty()) {
            println()
            println("--- Types in existing metadata NOT detected by static analysis ---")
            println("(These require the tracing agent or manual configuration)")
            val grouped = onlyInExisting.groupBy { it.substringBeforeLast('.', "unknown") }
            for ((pkg, types) in grouped.entries.sortedByDescending { it.value.size }.take(20)) {
                println("  $pkg (${types.size} types)")
                for (t in types.take(5)) {
                    println("    - $t")
                }
                if (types.size > 5) println("    ... and ${types.size - 5} more")
            }
        }

        // Compare JNI
        val existingJniTypes = existingMetadata.jniEntries.map { it.type }.toSet()
        val analyzerJniTypes = analyzerResult.jniEntries.map { it.type }.toSet()
        val jniOverlap = existingJniTypes.intersect(analyzerJniTypes)
        val jniNew = analyzerJniTypes - existingJniTypes

        println()
        println("--- JNI ---")
        println("Existing JNI types:     ${existingJniTypes.size}")
        println("Analyzer JNI types:     ${analyzerJniTypes.size}")
        println("Overlap:                ${jniOverlap.size}")
        println("New JNI discoveries:    ${jniNew.size}")

        // Compare resources
        val existingResources = existingMetadata.resourcePatterns.mapNotNull { it.glob }.toSet()
        val analyzerResources = analyzerResult.resourcePatterns.mapNotNull { it.glob }.toSet()

        println()
        println("--- Resources ---")
        println("Existing resource globs: ${existingResources.size}")
        println("Analyzer resource globs: ${analyzerResources.size}")
        println("New resource discoveries: ${(analyzerResources - existingResources).size}")

        // ServiceLoader entries are very important for GraalVM
        println()
        println("--- ServiceLoader ---")
        println("ServiceLoader implementations found: ${analyzerResult.serviceLoaderEntries.size}")
        for (entry in analyzerResult.serviceLoaderEntries.sortedBy { it.type }) {
            println("  ${entry.type}")
        }

        println()
        println("=".repeat(90))
    }

    @Test
    fun `Lucene reflection detection`() {
        assumeTrue("Zayit libraries not available", zayitLibraries.isNotEmpty())

        val luceneJars = zayitLibraries.filter { it.name.startsWith("lucene-") }
        assumeTrue("No Lucene JARs found", luceneJars.isNotEmpty())

        val result = BytecodeAnalyzer.analyzeJars(luceneJars)

        println()
        println("=== LUCENE ANALYSIS ===")
        println("JARs: ${luceneJars.map { it.name }}")
        println("Reflection entries: ${result.reflectionEntries.size}")
        println("ServiceLoader entries: ${result.serviceLoaderEntries.size}")
        println("Resource patterns: ${result.resourcePatterns.size}")

        // Lucene uses ServiceLoader extensively for codecs, analyzers, etc.
        assertTrue(
            "Lucene should have ServiceLoader entries (codecs, analyzers)",
            result.serviceLoaderEntries.isNotEmpty(),
        )

        // Show Lucene service implementations
        println()
        println("Lucene ServiceLoader implementations:")
        for (entry in result.serviceLoaderEntries.sortedBy { it.type }) {
            println("  ${entry.type}")
        }

        println()
        println("Lucene reflection entries:")
        for (entry in result.reflectionEntries.sortedBy { it.type }) {
            val details = mutableListOf<String>()
            if (entry.methods.isNotEmpty()) details.add("methods=${entry.methods.map { it.name }}")
            if (entry.fields.isNotEmpty()) details.add("fields=${entry.fields}")
            println("  ${entry.type} ${if (details.isNotEmpty()) details.joinToString() else ""}")
        }
    }

    @Test
    fun `SQLite and JDBC reflection detection`() {
        assumeTrue("Zayit libraries not available", zayitLibraries.isNotEmpty())

        val sqliteJars =
            zayitLibraries.filter {
                it.name.contains("sqlite") || it.name.contains("sqldelight") || it.name.contains("jdbc")
            }
        assumeTrue("No SQLite/JDBC JARs found", sqliteJars.isNotEmpty())

        val result = BytecodeAnalyzer.analyzeJars(sqliteJars)

        println()
        println("=== SQLITE/JDBC ANALYSIS ===")
        println("JARs: ${sqliteJars.map { it.name }}")
        println("JNI entries: ${result.jniEntries.size}")
        println("Reflection entries: ${result.reflectionEntries.size}")
        println("ServiceLoader entries: ${result.serviceLoaderEntries.size}")

        // SQLite JDBC uses JNI for native SQLite
        if (result.jniEntries.isNotEmpty()) {
            println()
            println("SQLite JNI entries:")
            for (entry in result.jniEntries.sortedBy { it.type }) {
                println("  ${entry.type} (${entry.methods.size} native methods)")
            }
        }
    }
}

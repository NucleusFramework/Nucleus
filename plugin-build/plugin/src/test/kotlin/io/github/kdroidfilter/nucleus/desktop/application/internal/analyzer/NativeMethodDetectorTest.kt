package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors.NativeMethodDetector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

class NativeMethodDetectorTest {
    private val analysisLibraries: List<File> by lazy {
        val path = System.getProperty("test.analysis.libraries") ?: ""
        path.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    private fun findJar(nameContains: String): File? = analysisLibraries.find { it.name.contains(nameContains) }

    @Test
    fun `detects native methods in JNA jar`() {
        val jnaJar = findJar("jna-")
        assumeTrue("JNA JAR not available", jnaJar != null)

        val result = BytecodeAnalyzer.analyzeJar(jnaJar!!)
        val jniTypes = result.jniEntries.map { it.type }.toSet()

        // JNA's Native class is full of native methods
        assertTrue(
            "Expected com.sun.jna.Native in JNI entries, got: $jniTypes",
            jniTypes.contains("com.sun.jna.Native"),
        )
    }

    @Test
    fun `native method detector finds ACC_NATIVE flag`() {
        val jnaJar = findJar("jna-")
        assumeTrue("JNA JAR not available", jnaJar != null)

        var foundNativeMethods = false
        JarFile(jnaJar!!).use { jar ->
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                val jniEntries = NativeMethodDetector.detect(bytes)
                if (jniEntries.isNotEmpty()) {
                    foundNativeMethods = true
                    // Verify each entry has at least one method
                    for (jniEntry in jniEntries) {
                        assertTrue(
                            "JNI entry ${jniEntry.type} should have methods",
                            jniEntry.methods.isNotEmpty(),
                        )
                    }
                }
            }
        }
        assertTrue("Should find at least one class with native methods in JNA", foundNativeMethods)
    }

    @Test
    fun `JNA analysis detects native method classes from Oracle JNI config`() {
        val jnaJar = findJar("jna-")
        val oracleZip = System.getProperty("test.oracle.repo.zip")?.let { File(it) }
        assumeTrue("JNA JAR not available", jnaJar != null)
        assumeTrue("Oracle repo ZIP not available", oracleZip != null && oracleZip.exists())

        val result = BytecodeAnalyzer.analyzeJar(jnaJar!!)
        val analyzerJniTypes = result.jniEntries.map { it.type }.toSet()

        val oracleDir = extractOracleRepoDir(oracleZip!!, "net.java.dev.jna", "jna")
        assumeTrue("Oracle metadata for JNA not found in repo", oracleDir != null)

        val oracleResult = OracleRepoParser.parseMetadataDir(oracleDir!!)
        val oracleJniTypes = oracleResult.jniEntries.map { it.type }.toSet()

        // The Oracle JNI config includes both classes with native methods AND classes
        // accessed from native code. The NativeMethodDetector only finds ACC_NATIVE classes.
        // Verify we detect a significant subset — at least the classes that declare native methods.
        val detected = oracleJniTypes.intersect(analyzerJniTypes)
        assertTrue(
            "Analyzer should detect at least one Oracle JNI type, detected: $detected",
            detected.isNotEmpty(),
        )
        assertTrue(
            "com.sun.jna.Native should be in both analyzer and Oracle JNI",
            "com.sun.jna.Native" in detected,
        )

        // Gap report should categorize the rest as NOT_DETECTABLE
        val report = ResultComparator.compareJni(result, oracleResult)
        assertTrue(
            "Gap report should have entries",
            report.entries.isNotEmpty(),
        )
    }

    companion object {
        fun extractOracleRepoDir(
            zipFile: File,
            group: String,
            artifact: String,
        ): File? {
            val tempDir =
                kotlin.io.path
                    .createTempDirectory("oracle-repo-")
                    .toFile()
            tempDir.deleteOnExit()
            java.util.zip.ZipFile(zipFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val target = File(tempDir, entry.name)
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            // Oracle repo structure: metadata/<group>/<artifact>/<version>/
            // Find the module directory
            return findModuleDir(tempDir, group, artifact)
        }

        fun findModuleDir(
            repoRoot: File,
            group: String,
            artifact: String,
        ): File? {
            // Search for the artifact directory recursively
            val candidates =
                repoRoot
                    .walkTopDown()
                    .filter { it.isDirectory && it.name == artifact && it.parentFile?.name == group }
                    .toList()

            if (candidates.isEmpty()) return null

            // Return the first version directory that has config files
            val moduleDir = candidates.first()
            return moduleDir
                .listFiles()
                ?.filter { it.isDirectory }
                ?.firstOrNull { dir ->
                    dir.listFiles()?.any {
                        it.name.endsWith("-config.json") || it.name == "reachability-metadata.json"
                    } == true
                }
        }
    }
}

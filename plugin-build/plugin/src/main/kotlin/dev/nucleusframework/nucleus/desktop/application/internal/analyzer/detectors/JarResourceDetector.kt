package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ResourcePattern
import java.util.jar.JarFile

/**
 * Scans JAR entries to detect resources that GraalVM native-image needs to include:
 * - Native libraries (.so, .dll, .dylib, .jnilib)
 * - Properties files at root or known paths (excluding META-INF)
 * - Text resources in known framework resource directories (Lucene, ICU4J, etc.)
 */
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
internal object JarResourceDetector {
    private val NATIVE_LIB_EXTENSIONS = setOf("so", "dll", "dylib", "jnilib", "a")

    private val RESOURCE_EXTENSIONS = setOf("properties", "txt", "xml", "json", "cfg", "conf")

    // NLP/analysis directory names specific enough to avoid false positives
    private val ANALYSIS_PATH_SEGMENTS =
        arrayOf(
            "/stopwords/",
            "/dictionaries/",
            "/snowball/",
            "/hunspell/",
            "/charfilter/",
            "/stemmer/",
        )

    // Framework prefixes known to contain runtime-loaded resources
    private val FRAMEWORK_RESOURCE_PREFIXES =
        arrayOf(
            "org/apache/lucene/",
            "com/ibm/icu/impl/data/",
            "opennlp/",
        )

    fun detect(jarFile: JarFile): Set<ResourcePattern> {
        val patterns = mutableSetOf<ResourcePattern>()

        for (entry in jarFile.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name

            // Skip class files and META-INF signatures/manifests
            if (name.endsWith(".class")) continue
            if (name.startsWith("META-INF/MANIFEST.MF")) continue
            if (name.startsWith("META-INF/maven/")) continue
            if (name.startsWith("META-INF/versions/")) continue
            if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) continue

            val ext = name.substringAfterLast('.', "")

            when {
                // Native libraries — always include, they're loaded at runtime
                ext in NATIVE_LIB_EXTENSIONS -> {
                    patterns.add(ResourcePattern(glob = name))
                }

                // Properties files — commonly loaded via getResourceAsStream at runtime
                // Include root-level and known framework paths
                ext == "properties" && !name.startsWith("META-INF/") -> {
                    patterns.add(ResourcePattern(glob = name))
                }

                // Text/config resources in known framework or NLP analysis directories
                ext in RESOURCE_EXTENSIONS && isAnalysisResourcePath(name) -> {
                    patterns.add(ResourcePattern(glob = name))
                }
            }
        }

        return patterns
    }

    /**
     * Checks whether the path belongs to a known framework resource directory
     * or contains a specific NLP/analysis directory segment.
     *
     * Avoids generic substrings like "/resources/" or "/data/" that would match
     * arbitrary Java package paths (e.g. `com/company/data/Service.xml`).
     */
    private fun isAnalysisResourcePath(name: String): Boolean {
        if (FRAMEWORK_RESOURCE_PREFIXES.any { name.startsWith(it) }) return true
        if (ANALYSIS_PATH_SEGMENTS.any { name.contains(it) }) return true
        return false
    }
}

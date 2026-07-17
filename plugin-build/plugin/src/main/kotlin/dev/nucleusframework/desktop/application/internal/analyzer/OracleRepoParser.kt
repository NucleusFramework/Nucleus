package dev.nucleusframework.desktop.application.internal.analyzer

import groovy.json.JsonSlurper
import java.io.File

/**
 * Parses Oracle GraalVM Reachability Metadata Repository old-format JSON files
 * (reflect-config.json, jni-config.json, resource-config.json) into [AnalysisResult].
 *
 * The Oracle repo uses individual config files per type (reflect-config.json, jni-config.json)
 * which are JSON arrays, unlike the newer reachability-metadata.json format.
 */
internal object OracleRepoParser {
    // Instantiate locally per call — JsonSlurper is not thread-safe
    private fun slurper() = JsonSlurper()

    /**
     * Parses all config files in a metadata directory.
     */
    fun parseMetadataDir(dir: File): AnalysisResult {
        val reflectConfig = File(dir, "reflect-config.json")
        val jniConfig = File(dir, "jni-config.json")
        val resourceConfig = File(dir, "resource-config.json")

        // Newer repo versions ship a single reachability-metadata.json instead of the
        // per-type *-config.json files. Prefer it when none of the old configs exist.
        val hasOldFormat = reflectConfig.exists() || jniConfig.exists() || resourceConfig.exists()
        val reachabilityMetadata = File(dir, "reachability-metadata.json")
        if (!hasOldFormat && reachabilityMetadata.exists()) {
            return parseReachabilityMetadata(reachabilityMetadata)
        }

        val reflectionEntries = if (reflectConfig.exists()) parseReflectConfig(reflectConfig) else emptySet()
        val jniEntries = if (jniConfig.exists()) parseJniConfig(jniConfig) else emptySet()
        val resourcePatterns = if (resourceConfig.exists()) parseResourceConfig(resourceConfig) else emptySet()

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
        )
    }

    /**
     * Parses a reflect-config.json (old format: JSON array of type entries).
     */
    fun parseReflectConfig(file: File): Set<ReflectionEntry> = parseTypeArray(file).map { it.toReflectionEntry() }.toSet()

    /**
     * Parses a jni-config.json (old format: JSON array of type entries).
     */
    fun parseJniConfig(file: File): Set<JniEntry> = parseTypeArray(file).map { it.toJniEntry() }.toSet()

    /**
     * Parses a resource-config.json.
     * Old format: `{ "resources": { "includes": [...] }, "bundles": [...] }`
     * New format: `{ "resources": [...], "bundles": [...] }`
     */
    fun parseResourceConfig(file: File): Set<ResourcePattern> {
        val patterns = mutableSetOf<ResourcePattern>()

        @Suppress("UNCHECKED_CAST")
        val root = slurper().parseText(file.readText()) as? Map<String, Any?> ?: return emptySet()

        // Handle old format: resources.includes
        @Suppress("UNCHECKED_CAST")
        val resources = root["resources"]
        when (resources) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val includes = resources["includes"] as? List<Map<String, Any?>> ?: emptyList()
                for (entry in includes) {
                    val pattern = entry["pattern"] as? String
                    if (pattern != null) {
                        patterns.add(ResourcePattern(glob = pattern))
                    }
                }
            }
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                for (entry in resources as List<Map<String, Any?>>) {
                    val glob = entry["glob"] as? String
                    if (glob != null) {
                        patterns.add(ResourcePattern(glob = glob))
                    }
                }
            }
        }

        // Handle bundles
        @Suppress("UNCHECKED_CAST")
        val bundles = root["bundles"] as? List<Map<String, Any?>>
        if (bundles != null) {
            for (entry in bundles) {
                val name = entry["name"] as? String
                if (name != null) {
                    patterns.add(ResourcePattern(bundle = name))
                }
            }
        }

        return patterns
    }

    /**
     * Parses a new-format reachability-metadata.json (wrapped in object with sections).
     */
    fun parseReachabilityMetadata(file: File): AnalysisResult {
        @Suppress("UNCHECKED_CAST")
        val root = slurper().parseText(file.readText()) as? Map<String, Any?> ?: return AnalysisResult()

        @Suppress("UNCHECKED_CAST")
        val reflectionArray = root["reflection"] as? List<Map<String, Any?>> ?: emptyList()

        // Legacy top-level "jni" array (older schema).
        @Suppress("UNCHECKED_CAST")
        val jniArray = root["jni"] as? List<Map<String, Any?>> ?: emptyList()

        val reflectionEntries = reflectionArray.map { parseTypeMap(it).toReflectionEntry() }.toSet()

        // Current schema no longer has a separate "jni" section; JNI-accessible types are
        // reflection entries flagged with "jniAccessible": true.
        val jniFromReflection =
            reflectionArray
                .filter { it["jniAccessible"] == true }
                .map { parseTypeMap(it).toJniEntry() }
        val jniEntries = (jniArray.map { parseTypeMap(it).toJniEntry() } + jniFromReflection).toSet()

        val resourcePatterns = mutableSetOf<ResourcePattern>()

        @Suppress("UNCHECKED_CAST")
        val resources = root["resources"] as? List<Map<String, Any?>>
        if (resources != null) {
            for (entry in resources) {
                val glob = entry["glob"] as? String
                val bundle = entry["bundle"] as? String
                val module = entry["module"] as? String
                if (glob != null || bundle != null) {
                    resourcePatterns.add(ResourcePattern(glob = glob, bundle = bundle, module = module))
                }
            }
        }

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
        )
    }

    // Internal parsed type representation before converting to ReflectionEntry or JniEntry
    private data class ParsedType(
        val name: String,
        val allDeclaredFields: Boolean = false,
        val allDeclaredMethods: Boolean = false,
        val allDeclaredConstructors: Boolean = false,
        val allPublicFields: Boolean = false,
        val allPublicMethods: Boolean = false,
        val allPublicConstructors: Boolean = false,
        val unsafeAllocated: Boolean = false,
        val methods: Set<MethodSignature> = emptySet(),
        val fields: Set<String> = emptySet(),
    ) {
        fun toReflectionEntry() =
            ReflectionEntry(
                type = name,
                allDeclaredFields = allDeclaredFields,
                allDeclaredMethods = allDeclaredMethods,
                allDeclaredConstructors = allDeclaredConstructors,
                allPublicFields = allPublicFields,
                allPublicMethods = allPublicMethods,
                allPublicConstructors = allPublicConstructors,
                unsafeAllocated = unsafeAllocated,
                methods = methods,
                fields = fields,
            )

        fun toJniEntry() =
            JniEntry(
                type = name,
                methods = methods,
                fields = fields,
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTypeArray(file: File): List<ParsedType> {
        val array = slurper().parseText(file.readText()) as? List<Map<String, Any?>> ?: return emptyList()
        return array.mapNotNull { map -> parseTypeMap(map).takeIf { it.name.isNotEmpty() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTypeMap(map: Map<String, Any?>): ParsedType {
        val name = (map["name"] as? String) ?: (map["type"] as? String) ?: ""

        val methods = mutableSetOf<MethodSignature>()
        val methodsList = map["methods"] as? List<Map<String, Any?>>
        if (methodsList != null) {
            for (m in methodsList) {
                val mName = m["name"] as? String ?: continue
                val params = (m["parameterTypes"] as? List<String>) ?: emptyList()
                methods.add(MethodSignature(mName, params))
            }
        }

        val fields = mutableSetOf<String>()
        val fieldsList = map["fields"] as? List<Map<String, Any?>>
        if (fieldsList != null) {
            for (f in fieldsList) {
                val fName = f["name"] as? String ?: continue
                fields.add(fName)
            }
        }

        return ParsedType(
            name = name,
            allDeclaredFields = map["allDeclaredFields"] == true,
            allDeclaredMethods = map["allDeclaredMethods"] == true,
            allDeclaredConstructors = map["allDeclaredConstructors"] == true,
            allPublicFields = map["allPublicFields"] == true,
            allPublicMethods = map["allPublicMethods"] == true,
            allPublicConstructors = map["allPublicConstructors"] == true,
            unsafeAllocated = map["unsafeAllocated"] == true,
            methods = methods,
            fields = fields,
        )
    }
}

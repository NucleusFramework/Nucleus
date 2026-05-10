package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

/**
 * A single reflection type entry discovered by static analysis.
 */
internal data class ReflectionEntry(
    val type: String,
    val allDeclaredFields: Boolean = false,
    val allDeclaredMethods: Boolean = false,
    val allDeclaredConstructors: Boolean = false,
    val allPublicFields: Boolean = false,
    val allPublicMethods: Boolean = false,
    val allPublicConstructors: Boolean = false,
    val unsafeAllocated: Boolean = false,
    val methods: Set<MethodSignature> = emptySet(),
    val fields: Set<String> = emptySet(),
)

/**
 * A single JNI type entry discovered by static analysis.
 */
internal data class JniEntry(
    val type: String,
    val methods: Set<MethodSignature> = emptySet(),
    val fields: Set<String> = emptySet(),
    val jniAccessible: Boolean = false,
)

/**
 * A method signature with name and parameter types.
 */
internal data class MethodSignature(
    val name: String,
    val parameterTypes: List<String> = emptyList(),
)

/**
 * A resource access pattern discovered by static analysis.
 */
internal data class ResourcePattern(
    val glob: String? = null,
    val bundle: String? = null,
    val module: String? = null,
)

/**
 * Aggregated results from all detectors running over a set of JARs.
 */
internal data class AnalysisResult(
    val reflectionEntries: Set<ReflectionEntry> = emptySet(),
    val jniEntries: Set<JniEntry> = emptySet(),
    val resourcePatterns: Set<ResourcePattern> = emptySet(),
    val serviceLoaderEntries: Set<ReflectionEntry> = emptySet(),
) {
    /**
     * All reflection entries including service loader implementations,
     * merged by type so each type appears only once with combined methods/fields/flags.
     */
    val allReflectionEntries: Set<ReflectionEntry>
        get() = mergeReflectionEntries(reflectionEntries + serviceLoaderEntries)

    /**
     * Merges this result with another.
     */
    operator fun plus(other: AnalysisResult): AnalysisResult =
        AnalysisResult(
            reflectionEntries = reflectionEntries + other.reflectionEntries,
            jniEntries = jniEntries + other.jniEntries,
            resourcePatterns = resourcePatterns + other.resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries + other.serviceLoaderEntries,
        )
}

/**
 * Merges reflection entries that share the same type into a single entry
 * with combined methods, fields, and the union of boolean flags.
 * This prevents duplicate type entries in the output JSON.
 */
internal fun mergeReflectionEntries(entries: Set<ReflectionEntry>): Set<ReflectionEntry> =
    entries
        .groupBy { it.type }
        .map { (_, group) ->
            group.reduce { acc, e ->
                ReflectionEntry(
                    type = acc.type,
                    allDeclaredFields = acc.allDeclaredFields || e.allDeclaredFields,
                    allDeclaredMethods = acc.allDeclaredMethods || e.allDeclaredMethods,
                    allDeclaredConstructors = acc.allDeclaredConstructors || e.allDeclaredConstructors,
                    allPublicFields = acc.allPublicFields || e.allPublicFields,
                    allPublicMethods = acc.allPublicMethods || e.allPublicMethods,
                    allPublicConstructors = acc.allPublicConstructors || e.allPublicConstructors,
                    unsafeAllocated = acc.unsafeAllocated || e.unsafeAllocated,
                    methods = acc.methods + e.methods,
                    fields = acc.fields + e.fields,
                )
            }
        }.toSet()

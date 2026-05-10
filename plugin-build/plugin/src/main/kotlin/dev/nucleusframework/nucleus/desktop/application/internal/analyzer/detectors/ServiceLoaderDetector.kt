package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ResourcePattern
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Detects ServiceLoader service providers by scanning META-INF/services/ files in JARs.
 *
 * Each service file produces:
 * - Reflection entries for each implementation class (with no-arg constructor)
 * - A resource pattern for the service file itself
 */
@Suppress("LoopWithTooManyJumpStatements")
internal object ServiceLoaderDetector {
    data class ServiceResult(
        val reflectionEntries: Set<ReflectionEntry>,
        val resourcePatterns: Set<ResourcePattern>,
    )

    fun detect(jarFile: JarFile): ServiceResult {
        val reflectionEntries = mutableSetOf<ReflectionEntry>()
        val resourcePatterns = mutableSetOf<ResourcePattern>()

        for (entry in jarFile.entries()) {
            if (!entry.name.startsWith("META-INF/services/") || entry.isDirectory) continue

            val serviceName = entry.name.removePrefix("META-INF/services/")
            if (serviceName.isEmpty() || serviceName.contains('/')) continue

            resourcePatterns.add(ResourcePattern(glob = entry.name))

            val implementations = parseServiceFile(jarFile.getInputStream(entry))
            for (impl in implementations) {
                reflectionEntries.add(
                    ReflectionEntry(
                        type = impl,
                        methods = setOf(MethodSignature("<init>", emptyList())),
                    ),
                )
            }
        }

        return ServiceResult(reflectionEntries, resourcePatterns)
    }

    /**
     * Parses a META-INF/services/ file, returning implementation class names.
     */
    internal fun parseServiceFile(input: InputStream): List<String> =
        input.bufferedReader().useLines { lines ->
            lines
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
}

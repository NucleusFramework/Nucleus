package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.util.jar.JarFile

/**
 * Prefixes treated as always resolvable without scanning the runtime classpath.
 * Covers the JDK and the classic `sun.*` / `com.sun.*` internal trees that live in
 * the image runtime, not in application JARs.
 */
internal val JDK_TYPE_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
    )

private val JVM_PRIMITIVE_ARRAY_ELEMENT =
    setOf('Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D')

/**
 * Scans classpath JARs and class directories for fully-qualified binary class names
 * (dots, `$` for nested types). Reads only entry names / file paths — no classloading,
 * so static initializers never run and no toolchain JVM is required.
 */
internal fun buildClasspathClassIndex(files: Collection<File>): Set<String> {
    val classes = mutableSetOf<String>()
    for (file in files) {
        if (!file.exists()) continue
        if (file.isDirectory) {
            file
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relative = classFile.relativeTo(file).path.replace(File.separatorChar, '/')
                    classNameFromClassPath(relative)?.let { classes.add(it) }
                }
        } else if (file.name.endsWith(".jar")) {
            try {
                JarFile(file).use { jar ->
                    for (entry in jar.entries()) {
                        if (entry.isDirectory) continue
                        classNameFromClassPath(entry.name)?.let { classes.add(it) }
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable JARs
            }
        }
    }
    return classes
}

/**
 * Converts a classpath-relative `.class` path into a binary name, or `null` when the
 * path is not a regular class (e.g. `module-info.class`). Handles multi-release JAR
 * prefixes (`META-INF/versions/N/...`).
 */
internal fun classNameFromClassPath(entryName: String): String? {
    if (!entryName.endsWith(".class")) return null
    if (entryName.endsWith("module-info.class")) return null
    var path = entryName
    if (path.startsWith("META-INF/versions/")) {
        val afterVersion = path.removePrefix("META-INF/versions/")
        val slash = afterVersion.indexOf('/')
        if (slash < 0) return null
        path = afterVersion.substring(slash + 1)
        if (path.endsWith("module-info.class")) return null
    }
    return path.removeSuffix(".class").replace('/', '.')
}

/**
 * Strips Java-style array suffixes (`[]`, possibly repeated) and decodes JVM array
 * descriptors (`[B`, `[Ljava.lang.String;`) down to the element binary name.
 * Returns the normalized name, or `null` when the descriptor is a primitive array
 * (always resolvable — caller should treat as present).
 */
internal fun normalizeTypeForLookup(typeName: String): String? {
    var name = typeName.trim()
    if (name.isEmpty()) return name

    // Java-style: "foo.Bar[][]"
    while (name.endsWith("[]")) {
        name = name.removeSuffix("[]").trimEnd()
    }

    // JVM descriptors: "[B", "[[Ljava.lang.String;"
    if (name.startsWith('[')) {
        var depth = 0
        while (depth < name.length && name[depth] == '[') depth++
        if (depth >= name.length) return name
        return when (val tag = name[depth]) {
            in JVM_PRIMITIVE_ARRAY_ELEMENT -> null // primitive array → always resolvable
            'L' -> {
                val semi = name.indexOf(';', depth)
                if (semi < 0) name.substring(depth + 1) else name.substring(depth + 1, semi)
            }
            else -> name.substring(depth)
        }
    }

    return name
}

/** True when [typeName] is a JDK / sun type that does not need a classpath hit. */
internal fun isJdkType(typeName: String): Boolean {
    val normalized = normalizeTypeForLookup(typeName) ?: return true
    if (normalized.isEmpty()) return true
    // Primitive keywords occasionally appear as agent noise.
    if (normalized in JVM_PRIMITIVE_KEYWORDS) return true
    return JDK_TYPE_PREFIXES.any { normalized.startsWith(it) }
}

private val JVM_PRIMITIVE_KEYWORDS =
    setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void")

/**
 * True when [typeName] can be found as a `.class` on the runtime classpath, or is a
 * JDK/primitive type that lives in the image runtime rather than application JARs.
 */
internal fun isTypeResolvable(
    typeName: String,
    classIndex: Set<String>,
): Boolean {
    val normalized = normalizeTypeForLookup(typeName) ?: return true
    if (normalized.isEmpty()) return true
    if (isJdkType(normalized)) return true
    return normalized in classIndex
}

/**
 * True when [typeName] falls under any of the exact-reachability package prefixes
 * (prefix match, same rule as `--exact-reachability-metadata=`).
 * Empty [packages] means exact mode is off → nothing is protected.
 */
internal fun isUnderExactReachabilityPackages(
    typeName: String,
    packages: Collection<String>,
): Boolean {
    if (packages.isEmpty()) return false
    val normalized = normalizeTypeForLookup(typeName) ?: return false
    return packages.any { prefix ->
        normalized == prefix || normalized.startsWith("$prefix.")
    }
}

/**
 * Extracts the type string from a reflection/jni/serialization entry, or `null` when
 * the entry is a proxy (`{"type": {"proxy": [...]}}`) or has no type field.
 */
@Suppress("UNCHECKED_CAST")
internal fun typeNameOfEntry(entry: Map<String, Any?>): String? {
    val type = entry["type"] ?: return null
    if (type is String) return type
    return null
}

/**
 * Interface names listed on a proxy entry, or empty when [entry] is not a proxy.
 */
@Suppress("UNCHECKED_CAST")
internal fun proxyInterfaceNames(entry: Map<String, Any?>): List<String> {
    val typeValue = entry["type"] as? Map<String, Any?> ?: return emptyList()
    val proxyList = typeValue["proxy"] as? List<*> ?: return emptyList()
    return proxyList.mapNotNull { it as? String }
}

/**
 * True when every type referenced by [entry] resolves on [classIndex] (or is JDK).
 * Proxy entries require every interface to resolve. Entries without a type field
 * are treated as resolvable (nothing to check).
 */
internal fun isEntryResolvable(
    entry: Map<String, Any?>,
    classIndex: Set<String>,
): Boolean {
    val proxies = proxyInterfaceNames(entry)
    if (proxies.isNotEmpty()) {
        return proxies.all { isTypeResolvable(it, classIndex) }
    }
    val typeName = typeNameOfEntry(entry) ?: return true
    return isTypeResolvable(typeName, classIndex)
}

/**
 * True when the entry should be protected from unresolvable removal because exact
 * reachability metadata is scoped to its package(s). For proxies, any interface
 * under the scope is enough to keep the whole entry (a partial probe still needs CNFE).
 */
internal fun isEntryProtectedByExactReachability(
    entry: Map<String, Any?>,
    packages: Collection<String>,
): Boolean {
    if (packages.isEmpty()) return false
    val proxies = proxyInterfaceNames(entry)
    if (proxies.isNotEmpty()) {
        return proxies.any { isUnderExactReachabilityPackages(it, packages) }
    }
    val typeName = typeNameOfEntry(entry) ?: return false
    return isUnderExactReachabilityPackages(typeName, packages)
}

/** Display label for an entry used in task logs. */
internal fun entryDisplayName(entry: Map<String, Any?>): String {
    val proxies = proxyInterfaceNames(entry)
    if (proxies.isNotEmpty()) return "proxy[${proxies.sorted().joinToString(",")}]"
    return typeNameOfEntry(entry) ?: "?"
}

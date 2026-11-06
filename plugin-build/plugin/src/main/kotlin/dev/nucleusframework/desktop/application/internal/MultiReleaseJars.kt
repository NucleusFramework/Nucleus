package dev.nucleusframework.desktop.application.internal

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** The version a JAR is opened for: [targetJavaVersion], or the base entries only when null. */
internal fun jarRuntimeVersion(targetJavaVersion: Int?): Runtime.Version =
    targetJavaVersion?.let { Runtime.Version.parse(it.toString()) } ?: JarFile.baseVersion()

/**
 * Opens [file] as a JDK of [version] reads it. Iterate it with [versionedClassEntries]: for a
 * multi-release JAR each class then resolves to the highest `META-INF/versions/N` copy with
 * `N <= version`, else the base entry, and a class that exists only under a higher `N` is absent.
 */
internal fun openVersionedJar(
    file: File,
    version: Runtime.Version,
): JarFile = JarFile(file, true, ZipFile.OPEN_READ, version)

/**
 * The `.class` entries of this JAR as its version sees them (see [openVersionedJar]). Entry
 * names are the unversioned paths (`org/lib/Helper.class`). Anything under `META-INF/` is left
 * out, which also drops `META-INF/versions/` of a JAR that is not multi-release: the JDK never
 * loads those as classes.
 */
internal fun JarFile.versionedClassEntries(): Sequence<JarEntry> =
    versionedStream()
        .iterator()
        .asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }

/**
 * Where native-image looks for a classpath JAR's `native-image.properties` and metadata files.
 * Its driver walks this directory from the JAR root without opening the JAR as multi-release,
 * so a copy under `META-INF/versions/N/META-INF/native-image/` (or any other nested path) is
 * never discovered, whatever JDK the image is built with.
 */
internal const val NATIVE_IMAGE_META_INF = "META-INF/native-image/"

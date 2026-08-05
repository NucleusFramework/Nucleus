/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Collapses multi-entry `app.classpath=` lines in jpackage `.cfg` files into a single
 * pathing jar whose manifest Class-Path lists the sibling jars in order.
 *
 * jpackage's Linux launcher (JDK 25 and earlier) serializes the full expanded classpath into a
 * JvmlLauncherData blob and transfers it parent-to-child over a pipe with a single plain
 * read() and no loop. When the payload is large enough that the pipe returns a short read,
 * envVarNames / envVarValues stay uninitialized and setenv() SIGSEGVs (JDK-8380085 /
 * Nucleus issue 454). A pathing jar shrinks the launcher payload to a few KB (typically under
 * PIPE_BUF), so the transfer always completes.
 *
 * Manifest Class-Path entries resolve relative to the pathing jar itself, so the layout stays
 * relocatable. Order is preserved. Sibling jars remain on disk; only the `.cfg` classpath is
 * rewritten.
 *
 * Safe to call when there is 0 or 1 classpath entry (no-op).
 */
internal object PathingJarClasspath {
    /** Default name for the generated pathing jar; intentionally unlikely to collide. */
    const val PATHING_JAR_NAME: String = "pathing-classpath.jar"

    private const val CLASSPATH_PREFIX = "app.classpath="
    private const val APPDIR_PREFIX = "\$APPDIR/"

    /**
     * Walks every launcher `.cfg` under [appJarDir] and collapses multi-entry classpaths.
     *
     * @return number of `.cfg` files rewritten
     */
    fun collapseInAppJarDir(
        appJarDir: File,
        logger: Logger,
        pathingJarName: String = PATHING_JAR_NAME,
    ): Int {
        if (!appJarDir.isDirectory) return 0

        val cfgFiles =
            appJarDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("cfg", ignoreCase = true) && it.name != "jvm.cfg" }
                .sortedBy { it.name }

        var rewritten = 0
        for (cfg in cfgFiles) {
            if (collapseCfg(cfg, appJarDir, pathingJarName, logger)) {
                rewritten++
            }
        }
        return rewritten
    }

    /**
     * Locates the Linux jpackage `lib/app` directory under a createDistributable output root
     * and collapses classpaths there.
     *
     * Layout: destinationDir/packageName/lib/app/ (launcher .cfg files)
     */
    fun collapseInLinuxAppImage(
        destinationDir: File,
        packageName: String,
        logger: Logger,
    ): Int {
        val appImageDir = resolveLinuxAppImageDir(destinationDir, packageName) ?: return 0
        val appJarDir = appImageDir.resolve("lib").resolve("app")
        if (!appJarDir.isDirectory) {
            logger.info(
                "Skipping pathing-jar classpath collapse: no lib/app under ${appImageDir.absolutePath}",
            )
            return 0
        }
        return collapseInAppJarDir(appJarDir, logger)
    }

    internal fun resolveLinuxAppImageDir(
        destinationDir: File,
        packageName: String,
    ): File? {
        val named = destinationDir.resolve(packageName)
        if (named.isDirectory) return named

        val children =
            destinationDir
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name != ".DS_Store" }
        return when (children.size) {
            1 -> children.single()
            else -> null
        }
    }

    /**
     * @return true if [cfgFile] was rewritten
     */
    internal fun collapseCfg(
        cfgFile: File,
        appJarDir: File,
        pathingJarName: String,
        logger: Logger,
    ): Boolean {
        val lines = cfgFile.readLines()
        val classpathEntries =
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(CLASSPATH_PREFIX)) {
                    trimmed.substringAfter(CLASSPATH_PREFIX).trim()
                } else {
                    null
                }
            }

        // 0 or 1 entry: either already collapsed or a single main jar — nothing to do.
        if (classpathEntries.size <= 1) return false

        val jarNames =
            classpathEntries.map { entry ->
                entry
                    .removePrefix(APPDIR_PREFIX)
                    .removePrefix("./")
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
            }

        // Never put the pathing jar on its own Class-Path (would recurse / self-ref).
        val siblingJars = jarNames.filter { it.isNotEmpty() && it != pathingJarName }
        if (siblingJars.size <= 1) return false

        val pathingJar = appJarDir.resolve(pathingJarName)
        writePathingJar(pathingJar, siblingJars)

        val rewritten =
            buildList {
                var classpathWritten = false
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith(CLASSPATH_PREFIX)) {
                        if (!classpathWritten) {
                            add("$CLASSPATH_PREFIX$APPDIR_PREFIX$pathingJarName")
                            classpathWritten = true
                        }
                    } else {
                        add(line)
                    }
                }
            }

        cfgFile.writeText(rewritten.joinToString(System.lineSeparator()) + System.lineSeparator())

        logger.lifecycle(
            "Collapsed ${siblingJars.size} classpath entries in ${cfgFile.name} into " +
                "$pathingJarName (JDK-8380085 / Linux launcher short-read workaround)",
        )
        return true
    }

    internal fun writePathingJar(
        pathingJar: File,
        siblingJarNames: List<String>,
    ) {
        pathingJar.parentFile?.mkdirs()
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                // Space-separated relative URLs; Manifest writer wraps at 72 bytes.
                mainAttributes[Attributes.Name.CLASS_PATH] =
                    siblingJarNames.joinToString(" ") { encodeClassPathEntry(it) }
                mainAttributes.putValue("Created-By", "Nucleus")
            }
        JarOutputStream(pathingJar.outputStream().buffered(), manifest).use {
            // Pathing jar: manifest only, no class entries.
        }
    }

    /**
     * Manifest Class-Path entries are space-separated URLs. Encode spaces so a jar name
     * containing whitespace does not split into multiple entries.
     */
    internal fun encodeClassPathEntry(name: String): String = name.replace(" ", "%20")
}

/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.tasks

import io.github.kdroidfilter.nucleus.desktop.application.internal.JvmRuntimeProperties
import io.github.kdroidfilter.nucleus.desktop.tasks.AbstractNucleusTask
import io.github.kdroidfilter.nucleus.internal.utils.notNullProperty
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

private const val AOT_CACHE_FILENAME = "app.aot"
private const val MIN_AOT_JDK_VERSION = 25
private const val DEFAULT_SAFETY_TIMEOUT_SECONDS = 300L

/**
 * Builds the Java launcher argument list for AOT training (excluding the java executable path).
 */
internal fun buildAotJavaArgs(
    classpath: String,
    javaOptions: List<String>,
    mainClass: String,
    aotCacheFile: File,
): List<String> =
    buildList {
        add("-XX:AOTCacheOutput=${aotCacheFile.absolutePath}")
        add("-Dnucleus.aot.mode=training")
        add("-cp")
        add(classpath)
        addAll(javaOptions)
        add(mainClass)
    }

/**
 * Writes Java launcher arguments as a UTF-8 argument file (`@argfile`), one argument per line.
 */
internal fun writeJavaArgFile(
    file: File,
    args: List<String>,
) {
    val content =
        args
            .joinToString(separator = System.lineSeparator()) { arg -> escapeArgForArgFile(arg) } +
            System.lineSeparator()
    file.writeText(content, Charsets.UTF_8)
}

/**
 * Escapes a single Java launcher argument for `@argfile`.
 *
 * The argument is quoted when it contains whitespace, quotes, backslashes, or is empty.
 * Newline characters are rejected because each argfile line encodes one argument.
 */
internal fun escapeArgForArgFile(arg: String): String {
    require('\n' !in arg && '\r' !in arg) {
        "Java @argfile argument must not contain newline characters"
    }
    val requiresQuotes = arg.isEmpty() || arg.any { it.isWhitespace() || it == '"' || it == '\\' }
    if (!requiresQuotes) return arg
    val escaped =
        arg
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    return "\"$escaped\""
}

/**
 * Builds candidate directories for AOT argument/log temp files.
 *
 * Preference order:
 * 1. `java.io.tmpdir`
 * 2. app directory
 * 3. AOT cache output directory
 */
internal fun buildAotTempFileCandidateDirs(
    appDir: File,
    aotCacheFile: File,
    tmpDirPath: String? = System.getProperty("java.io.tmpdir"),
): List<File> =
    listOfNotNull(
        tmpDirPath?.takeIf { it.isNotBlank() }?.let(::File),
        appDir,
        aotCacheFile.parentFile,
    ).map { it.absoluteFile }.distinctBy { it.path }

/**
 * Creates a temp file under candidate directories in order.
 *
 * This avoids hard dependency on `java.io.tmpdir` writability.
 */
internal fun createAotTempFileWithFallback(
    prefix: String,
    suffix: String,
    candidateDirs: List<File>,
): File {
    var firstFailure: IOException? = null
    val attemptedDirs = mutableListOf<String>()
    for (dir in candidateDirs) {
        attemptedDirs += dir.absolutePath
        try {
            return File.createTempFile(prefix, suffix, dir).also { it.deleteOnExit() }
        } catch (e: IOException) {
            if (firstFailure == null) {
                firstFailure = e
            } else {
                firstFailure.addSuppressed(e)
            }
        }
    }

    throw GradleException(
        "Failed to create temporary file '$prefix*$suffix' in candidate directories: ${attemptedDirs.joinToString(", ")}",
        firstFailure,
    )
}

/**
 * Generates a JDK 25+ AOT cache for a Compose Desktop distributable.
 *
 * This task:
 * 1. Locates the distributable app directory (platform-specific layout)
 * 2. Provisions a java launcher in the bundled runtime (if missing)
 * 3. Parses the `.cfg` file for classpath, JVM options, and main class
 * 4. Runs the app with `-XX:AOTCacheOutput` (single-step AOT, JDK 25+)
 * 5. Injects `-XX:AOTCache=$APPDIR/app.aot` into the `.cfg` file
 *
 * The application **must** self-terminate during training by calling `System.exit(0)`.
 * Use `AotRuntime.isTraining()` from the `aot-runtime` module to detect training mode.
 */
@DisableCachingByDefault(because = "AOT cache generation depends on runtime behavior")
abstract class AbstractGenerateAotCacheTask : AbstractNucleusTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributableDir: DirectoryProperty

    @get:Internal
    val javaHome: Property<String> =
        objects.notNullProperty<String>().apply {
            set(providers.systemProperty("java.home"))
        }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

    /** Runtime entitlements file for macOS. Used to re-sign jspawnhelper after AOT training. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    val macRuntimeEntitlementsFile: RegularFileProperty = objects.fileProperty()

    /** Safety timeout in seconds. The task will force-kill the app if it has not exited within this time. */
    @get:Input
    val safetyTimeoutSeconds: Property<Long> =
        objects.notNullProperty<Long>().apply {
            set(DEFAULT_SAFETY_TIMEOUT_SECONDS)
        }

    @TaskAction
    fun execute() {
        checkJdkVersion()

        val baseDir = distributableDir.get().asFile
        val appDir = findAppDir(baseDir)

        logger.lifecycle("[aotCache] Processing ${appDir.name}")

        val appJarDir = findAppJarDir(appDir)
        val (javaExe, _) = provisionJavaLauncher(appDir)

        val cfgFile =
            appJarDir.listFiles()?.firstOrNull { it.extension == "cfg" }
                ?: throw GradleException("No .cfg file found in $appJarDir")
        val (classpath, javaOptions, mainClass) = parseCfgFile(cfgFile, appJarDir)

        val aotCacheFile = File(appJarDir, AOT_CACHE_FILENAME)
        generateAotCache(javaExe, appDir, appJarDir, classpath, javaOptions, mainClass, aotCacheFile)

        injectAotCacheIntoCfg(cfgFile)

        logger.lifecycle("[aotCache] Complete: ${aotCacheFile.absolutePath} (${aotCacheFile.length() / 1024}KB)")
    }

    private fun checkJdkVersion() {
        val propsFile = javaRuntimePropertiesFile.orNull?.asFile ?: return
        if (!propsFile.exists()) return
        val props = JvmRuntimeProperties.readFromFile(propsFile)
        if (props.majorVersion < MIN_AOT_JDK_VERSION) {
            throw GradleException(
                "AOT cache generation requires JDK $MIN_AOT_JDK_VERSION or newer, " +
                    "but the configured JDK has major version ${props.majorVersion}. " +
                    "Set enableAotCache = false or configure a JDK $MIN_AOT_JDK_VERSION+ runtime.",
            )
        }
    }

    private fun findAppDir(baseDir: File): File {
        val children =
            baseDir
                .listFiles()
                ?.filter { it.isDirectory && it.name != ".DS_Store" }
                ?: emptyList()
        return when {
            children.isEmpty() -> throw GradleException("Distributable app directory not found under $baseDir")
            children.size == 1 -> children.single()
            else -> throw GradleException(
                "Expected a single app directory under $baseDir, found: ${children.joinToString { it.name }}",
            )
        }
    }

    private fun findAppJarDir(appDir: File): File =
        listOf(
            File(appDir, "Contents/app"), // macOS
            File(appDir, "app"), // Windows
            File(appDir, "lib/app"), // Linux
        ).firstOrNull { it.exists() }
            ?: throw GradleException("app/ subdirectory not found in $appDir")

    private fun provisionJavaLauncher(appDir: File): Pair<String, Boolean> {
        val toolchainJavaExe =
            File(javaHome.get()).resolve("bin").let { binDir ->
                val exeName = if (isWindows()) "java.exe" else "java"
                binDir.resolve(exeName).absolutePath
            }

        val runtimeHome =
            listOf(
                File(appDir, "Contents/runtime/Contents/Home"), // macOS
                File(appDir, "runtime"), // Windows
                File(appDir, "lib/runtime"), // Linux
            ).firstOrNull { it.exists() }

        if (runtimeHome == null) {
            logger.warn("[aotCache] Bundled runtime not found, using toolchain java")
            return toolchainJavaExe to false
        }

        val runtimeBinDir = File(runtimeHome, "bin")
        val exeName = if (isWindows()) "java.exe" else "java"
        val provisionedJava = File(runtimeBinDir, exeName)

        if (provisionedJava.exists()) {
            return provisionedJava.absolutePath to false
        }

        runtimeBinDir.mkdirs()
        File(toolchainJavaExe).copyTo(provisionedJava, overwrite = true)
        provisionedJava.setExecutable(true)

        if (isWindows()) {
            copyWindowsDlls(File(toolchainJavaExe).parentFile, runtimeBinDir)
        }

        logger.lifecycle("[aotCache] Provisioned java launcher at ${provisionedJava.absolutePath}")
        return provisionedJava.absolutePath to true
    }

    private fun copyWindowsDlls(
        toolchainBinDir: File,
        runtimeBinDir: File,
    ) {
        val essentialDlls = setOf("jli.dll", "vcruntime140.dll", "msvcp140.dll", "ucrtbase.dll")
        toolchainBinDir
            .listFiles()
            ?.filter { it.extension.lowercase() == "dll" && it.name.lowercase() in essentialDlls }
            ?.forEach { dll ->
                val target = File(runtimeBinDir, dll.name)
                if (!target.exists()) {
                    dll.copyTo(target, overwrite = false)
                }
            }
    }

    private data class CfgParseResult(
        val classpath: String,
        val javaOptions: List<String>,
        val mainClass: String,
    )

    private fun parseCfgFile(
        cfgFile: File,
        appJarDir: File,
    ): CfgParseResult {
        val cpEntries = mutableListOf<String>()
        val javaOptions = mutableListOf<String>()
        var mainClass = ""
        var inClasspath = false
        var inJavaOptions = false

        for (line in cfgFile.readLines()) {
            val trimmed = line.trim()
            when {
                trimmed == "[JavaOptions]" -> {
                    inJavaOptions = true
                    inClasspath = false
                }
                trimmed == "[ClassPath]" -> {
                    inClasspath = true
                    inJavaOptions = false
                }
                trimmed == "[Application]" || trimmed == "[ArgOptions]" -> {
                    inClasspath = false
                    inJavaOptions = false
                }
                trimmed.startsWith("app.mainclass=") -> mainClass = trimmed.substringAfter("app.mainclass=").trim()
                trimmed.startsWith("app.classpath=") -> cpEntries += trimmed.substringAfter("app.classpath=").trim()
                trimmed.startsWith("[") -> {
                    inClasspath = false
                    inJavaOptions = false
                }
                inClasspath && trimmed.isNotEmpty() -> cpEntries += trimmed
                inJavaOptions && trimmed.isNotEmpty() -> {
                    val opt =
                        if (trimmed.startsWith("java-options=")) {
                            trimmed.substringAfter("java-options=")
                        } else {
                            trimmed
                        }
                    if (!opt.contains("AOTCache")) {
                        javaOptions += opt.replace("\$APPDIR", appJarDir.absolutePath)
                    }
                }
            }
        }

        val classpath =
            cpEntries.joinToString(File.pathSeparator) { entry ->
                File(entry.replace("\$APPDIR", appJarDir.absolutePath)).absolutePath
            }

        return CfgParseResult(classpath, javaOptions, mainClass)
    }

    /**
     * Removes the `Sealed: true` manifest attribute from JARs whose sealed packages
     * conflict with JBR platform modules.
     *
     * `jbr-api` is sealed (`Sealed: true` in MANIFEST.MF) and contains `com.jetbrains`
     * classes that are also defined in the JBR platform class loader. When the AOT cache
     * loads these pre-linked classes, the JVM tries to seal the `com.jetbrains` package
     * but it is already defined by the platform → fatal `SecurityException`:
     *   "sealing violation: can't seal package com.jetbrains: already defined"
     *
     * Unsealing the JAR keeps the classes available on the classpath (needed for both
     * training and runtime) while preventing the sealing conflict.
     */
    private fun unsealConflictingJars(appJarDir: File) {
        val jars =
            appJarDir.listFiles().orEmpty().filter {
                it.extension == "jar" && it.name.lowercase().startsWith("jbr-api")
            }
        for (jar in jars) {
            logger.lifecycle("[aotCache] Unsealing ${jar.name} to prevent AOT sealing violation")
            unsealJar(jar)
        }
    }

    private fun unsealJar(jarFile: File) {
        val tmpFile = File(jarFile.parentFile, "${jarFile.name}.tmp")
        val manifest = readAndUnsealManifest(jarFile)
        writeUnsealedJar(jarFile, tmpFile, manifest)
        tmpFile.renameTo(jarFile)
    }

    private fun readAndUnsealManifest(jarFile: File): java.util.jar.Manifest =
        java.util.jar.JarFile(jarFile).use { jar ->
            java.util.jar.Manifest(jar.manifest).apply {
                mainAttributes.remove(java.util.jar.Attributes.Name.SEALED)
                entries.values.forEach { it.remove(java.util.jar.Attributes.Name.SEALED) }
            }
        }

    private fun writeUnsealedJar(
        source: File,
        target: File,
        manifest: java.util.jar.Manifest,
    ) {
        val input = java.util.jar.JarFile(source)
        val output = java.util.jar.JarOutputStream(target.outputStream(), manifest)
        try {
            for (entry in input.entries()) {
                if (entry.name == java.util.jar.JarFile.MANIFEST_NAME) continue
                output.putNextEntry(java.util.jar.JarEntry(entry.name))
                input.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        } finally {
            output.close()
            input.close()
        }
    }

    private fun generateAotCache(
        javaExe: String,
        appDir: File,
        appJarDir: File,
        classpath: String,
        javaOptions: List<String>,
        mainClass: String,
        aotCacheFile: File,
    ) {
        unsealConflictingJars(appJarDir)

        val jspawnhelper = findJspawnhelper(appDir)
        if (jspawnhelper != null) {
            unsandboxJspawnhelper(jspawnhelper)
        }

        logger.lifecycle("[aotCache] Training – waiting for the application to exit...")
        try {
            runAotCacheCreation(javaExe, appDir, classpath, javaOptions, mainClass, aotCacheFile)
        } finally {
            if (jspawnhelper != null) {
                resandboxJspawnhelper(jspawnhelper)
            }
        }

        if (!aotCacheFile.exists()) {
            throw GradleException("AOT cache file was not created at ${aotCacheFile.absolutePath}")
        }
    }

    private fun runAotCacheCreation(
        javaExe: String,
        appDir: File,
        classpath: String,
        javaOptions: List<String>,
        mainClass: String,
        aotCacheFile: File,
    ) {
        val javaArgs =
            buildAotJavaArgs(
                classpath = classpath,
                javaOptions = javaOptions,
                mainClass = mainClass,
                aotCacheFile = aotCacheFile,
            )
        val candidateDirs = buildAotTempFileCandidateDirs(appDir, aotCacheFile)
        var argFile: File? = null
        try {
            val javaLauncherArgs =
                try {
                    argFile = createAotTempFileWithFallback("nucleus-aot-", ".args", candidateDirs)
                    writeJavaArgFile(requireNotNull(argFile), javaArgs)
                    listOf(javaExe, "@${requireNotNull(argFile).absolutePath}")
                } catch (e: GradleException) {
                    if (isWindows()) {
                        throw GradleException(
                            "Failed to create AOT @argfile on Windows. " +
                                "Cannot safely fall back to command-line arguments due to CreateProcess length limits.",
                            e,
                        )
                    }
                    logger.warn("[aotCache] Failed to create @argfile, falling back to direct Java args: ${e.message}")
                    listOf(javaExe) + javaArgs
                }

            val logFile = createAotTempFileWithFallback("nucleus-aot-", ".log", candidateDirs)
            var xvfbProcess: Process? = null
            try {
                val processBuilder =
                    ProcessBuilder(javaLauncherArgs)
                        .directory(appDir)
                        .redirectErrorStream(true)
                        .redirectOutput(logFile)

                val isLinux = System.getProperty("os.name").lowercase().contains("linux")
                val needsXvfb = isLinux && System.getenv("DISPLAY").isNullOrEmpty()
                if (needsXvfb) {
                    val display = ":99"
                    xvfbProcess =
                        ProcessBuilder("Xvfb", display, "-screen", "0", "1280x1024x24")
                            .redirectErrorStream(true)
                            .start()
                    Thread.sleep(1000)
                    processBuilder.environment()["DISPLAY"] = display
                    logger.lifecycle("[aotCache] Started Xvfb on $display")
                }

                val process = processBuilder.start()

                val deadline = System.currentTimeMillis() + safetyTimeoutSeconds.get() * 1000
                while (process.isAlive && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                }
                if (process.isAlive) {
                    logger.warn("[aotCache] App did not self-terminate within safety timeout, forcing kill")
                    process.destroyForcibly()
                }

                val exitCode = process.waitFor()

                val output = logFile.readText().takeLast(3000)
                if (output.isNotBlank()) {
                    logger.lifecycle("[aotCache] Output (exit $exitCode):\n$output")
                }

                // Clean up JVM crash dumps
                appDir.listFiles()?.filter { it.name.startsWith("hs_err_pid") }?.forEach { hsErr ->
                    logger.lifecycle("[aotCache] JVM crash dump: ${hsErr.name}")
                    // Only read text-based .log files; .mdmp files are binary minidumps
                    // that can be hundreds of MB and would cause OOM with readText()
                    if (hsErr.extension == "log") {
                        logger.lifecycle(hsErr.readText().take(2000))
                    }
                    hsErr.delete()
                }
            } finally {
                xvfbProcess?.destroyForcibly()
                logFile.delete()
            }
        } finally {
            argFile?.delete()
        }
    }

    private fun injectAotCacheIntoCfg(cfgFile: File) {
        val content = cfgFile.readText()
        if (content.contains("AOTCache")) return
        val updatedContent =
            content.replace(
                "[JavaOptions]",
                "[JavaOptions]\njava-options=-XX:AOTCache=\$APPDIR/$AOT_CACHE_FILENAME",
            )
        cfgFile.writeText(updatedContent)
        logger.lifecycle("[aotCache] Injected AOTCache into ${cfgFile.name}")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun isMacOS(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Finds jspawnhelper in the bundled macOS runtime.
     * jspawnhelper is used by the JVM to fork/exec child processes (needed for AOT cache assembly).
     */
    private fun findJspawnhelper(appDir: File): File? {
        if (!isMacOS()) return null
        val runtimeHome = File(appDir, "Contents/runtime/Contents/Home")
        if (!runtimeHome.exists()) return null
        val jspawnhelper = File(runtimeHome, "lib/jspawnhelper")
        return if (jspawnhelper.exists()) jspawnhelper else null
    }

    /**
     * Ad-hoc re-signs jspawnhelper without sandbox entitlements so it can spawn
     * child processes during AOT training. Completely removing the signature
     * causes macOS to SIGKILL the binary; ad-hoc signing avoids this.
     */
    private fun unsandboxJspawnhelper(jspawnhelper: File) {
        logger.lifecycle("[aotCache] Temporarily re-signing jspawnhelper without sandbox for AOT training")
        runCodesign(listOf("codesign", "--force", "--sign", "-", jspawnhelper.absolutePath))
    }

    /**
     * Re-signs jspawnhelper with the runtime entitlements after AOT training,
     * restoring the app-sandbox entitlement required for Mac App Store.
     */
    private fun resandboxJspawnhelper(jspawnhelper: File) {
        val entitlementsFile = macRuntimeEntitlementsFile.orNull?.asFile
        val args = mutableListOf("codesign", "--force", "--sign", "-")
        if (entitlementsFile != null) {
            args += listOf("--entitlements", entitlementsFile.absolutePath)
        }
        args += jspawnhelper.absolutePath
        logger.lifecycle("[aotCache] Re-signing jspawnhelper with runtime entitlements")
        runCodesign(args)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runCodesign(args: List<String>) {
        try {
            val process =
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("[aotCache] codesign failed (exit $exitCode): $output")
            }
        } catch (e: Exception) {
            logger.warn("[aotCache] codesign failed: ${e.message}")
        }
    }
}

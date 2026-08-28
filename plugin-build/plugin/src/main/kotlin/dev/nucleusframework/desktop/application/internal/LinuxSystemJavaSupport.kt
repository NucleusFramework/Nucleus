package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.LinuxSystemJava
import dev.nucleusframework.desktop.application.dsl.PackagingBackend
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.internal.utils.OS
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Turns a jpackage app-image into a system-Java payload for deb/rpm/pacman:
 * drop `lib/runtime`, replace the ELF launcher with a script that execs the
 * host JRE, and expose the package-manager dependency strings.
 */
internal object LinuxSystemJavaSupport {
    private const val BYTES_PER_MIB = 1024L * 1024L
    private const val QUOTE_OVERHEAD = 8

    val filesystemFormats: Set<TargetFormat> =
        setOf(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Pacman)

    fun appliesTo(targetFormat: TargetFormat): Boolean = targetFormat in filesystemFormats

    /**
     * AOT caches are bound to the bundled jlink image. When every Linux package
     * produced on this OS omits that image, training the cache is wasted work
     * and the `.aot` file would be stripped anyway.
     */
    fun skipsAotCache(
        systemJava: LinuxSystemJava?,
        targetFormats: Set<TargetFormat>,
        os: OS,
    ): Boolean {
        if (systemJava == null || os != OS.Linux) return false
        val linuxPackages =
            targetFormats.filter {
                it.isCompatibleWith(os) && it.backend == PackagingBackend.ELECTRON_BUILDER
            }
        return linuxPackages.isNotEmpty() && linuxPackages.all { appliesTo(it) }
    }

    /**
     * System-Java packages install a distro JRE of [LinuxSystemJava.major]. A packaging JDK
     * newer than that can emit class files that JRE cannot load (`UnsupportedClassVersionError`).
     *
     * @return an error message when [buildJdkMajor] is too new, or `null` when the pair is safe.
     */
    fun incompatibleBuildJdkMessage(
        systemJava: LinuxSystemJava,
        buildJdkMajor: Int,
    ): String? {
        if (buildJdkMajor <= systemJava.major) return null
        val matching = LinuxSystemJava.fromMajor(buildJdkMajor)?.let { "LinuxSystemJava.${it.name}" }
            ?: "a LinuxSystemJava value of $buildJdkMajor"
        return """
            linux.systemJava = ${systemJava.name} (Java ${systemJava.major}) but the packaging JDK is $buildJdkMajor.
            A JDK $buildJdkMajor build can emit class files that a Java ${systemJava.major} JRE cannot load.
            Point JAVA_HOME / javaHome at JDK ${systemJava.major}, or set linux.systemJava = $matching.
            """.trimIndent()
    }

    fun mergeDepends(
        systemJava: LinuxSystemJava?,
        targetFormat: TargetFormat,
        userDepends: List<String>,
    ): List<String> {
        if (systemJava == null || !appliesTo(targetFormat)) return userDepends
        val system =
            when (targetFormat) {
                TargetFormat.Deb -> systemJava.debDepends
                TargetFormat.Rpm -> systemJava.rpmRequires
                TargetFormat.Pacman -> systemJava.pacmanDepends
                else -> return userDepends
            }
        return listOf(system) + userDepends
    }

    /**
     * @return `true` when the app-image was a jpackage tree and was rewritten.
     */
    fun rewriteAppImage(
        appDir: File,
        java: LinuxSystemJava,
        logger: Logger,
    ): Boolean {
        val appJarDir = appDir.resolve("lib").resolve("app")
        if (!appJarDir.isDirectory) {
            logger.lifecycle(
                "linux.systemJava = ${java.name} ignored: ${appDir.absolutePath} has no jpackage lib/app",
            )
            return false
        }

        val runtimeDir = appDir.resolve("lib").resolve("runtime")
        if (runtimeDir.isDirectory) {
            val sizeMiB =
                runtimeDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() } / BYTES_PER_MIB
            runtimeDir.deleteRecursively()
            logger.lifecycle(
                "linux.systemJava = ${java.name}: removed bundled JRE (${sizeMiB} MiB) from ${runtimeDir.absolutePath}",
            )
        }

        appDir.resolve("lib").resolve("libapplauncher.so").delete()
        val aotCache = appJarDir.resolve("app.aot")
        if (aotCache.delete()) {
            logger.warn(
                "linux.systemJava: dropped AOT cache ${aotCache.name}; " +
                    "Leyden caches are bound to the bundled JVM and cannot be reused",
            )
        }

        val cfgFiles =
            appJarDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("cfg", ignoreCase = true) && it.name != "jvm.cfg" }
                .sortedBy { it.name }
        if (cfgFiles.isEmpty()) {
            throw GradleException(
                "linux.systemJava is set but no launcher .cfg was found in ${appJarDir.absolutePath}",
            )
        }

        val binDir = appDir.resolve("bin").apply { mkdirs() }
        for (cfg in cfgFiles) {
            val spec = parseCfg(cfg)
            val launcher = binDir.resolve(cfg.nameWithoutExtension)
            launcher.writeText(renderLauncherScript(spec, java.major))
            launcher.setReadable(true, false)
            launcher.setWritable(false, false)
            launcher.setWritable(true, true)
            launcher.setExecutable(true, false)
            logger.info("Wrote system-Java launcher ${launcher.absolutePath} (min Java ${java.major})")
        }
        return true
    }

    internal data class CfgLaunchSpec(
        val mainClass: String,
        val classpath: List<String>,
        val javaOptions: List<String>,
        val arguments: List<String>,
    )

    @Suppress("CyclomaticComplexMethod") // Linear cfg-section parser; same shape as the AOT one.
    internal fun parseCfg(cfgFile: File): CfgLaunchSpec {
        val cpEntries = mutableListOf<String>()
        val javaOptions = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        var mainClass = ""
        var inClasspath = false
        var inJavaOptions = false
        var inArgOptions = false

        for (line in cfgFile.readLines()) {
            val trimmed = line.trim()
            when {
                trimmed == "[JavaOptions]" -> {
                    inJavaOptions = true
                    inClasspath = false
                    inArgOptions = false
                }
                trimmed == "[ClassPath]" -> {
                    inClasspath = true
                    inJavaOptions = false
                    inArgOptions = false
                }
                trimmed == "[ArgOptions]" -> {
                    inArgOptions = true
                    inClasspath = false
                    inJavaOptions = false
                }
                trimmed == "[Application]" -> {
                    inClasspath = false
                    inJavaOptions = false
                    inArgOptions = false
                }
                trimmed.startsWith("app.mainclass=") ->
                    mainClass = trimmed.substringAfter("app.mainclass=").trim()
                trimmed.startsWith("app.classpath=") ->
                    cpEntries += trimmed.substringAfter("app.classpath=").trim()
                trimmed.startsWith("app.arguments=") ->
                    arguments += trimmed.substringAfter("app.arguments=").trim()
                trimmed.startsWith("[") -> {
                    inClasspath = false
                    inJavaOptions = false
                    inArgOptions = false
                }
                inClasspath && trimmed.isNotEmpty() -> cpEntries += trimmed
                inJavaOptions && trimmed.isNotEmpty() -> {
                    val opt =
                        if (trimmed.startsWith("java-options=")) {
                            trimmed.substringAfter("java-options=")
                        } else {
                            trimmed
                        }
                    if (opt.isNotEmpty() && !opt.contains("AOTCache")) {
                        javaOptions += opt
                    }
                }
                inArgOptions && trimmed.isNotEmpty() -> {
                    val arg =
                        if (trimmed.startsWith("arguments=")) {
                            trimmed.substringAfter("arguments=")
                        } else {
                            trimmed
                        }
                    if (arg.isNotEmpty()) arguments += arg
                }
            }
        }

        if (mainClass.isEmpty()) {
            throw GradleException("Launcher ${cfgFile.absolutePath} has no app.mainclass")
        }
        val classpath = cpEntries.filter { it.isNotEmpty() }
        if (classpath.isEmpty()) {
            throw GradleException("Launcher ${cfgFile.absolutePath} has no app.classpath")
        }
        return CfgLaunchSpec(mainClass, classpath, javaOptions, arguments)
    }

    @Suppress("LongMethod") // The body is a POSIX launcher template plus a handful of substitutions.
    internal fun renderLauncherScript(
        spec: CfgLaunchSpec,
        minMajor: Int,
    ): String {
        val classpathLiteral = spec.classpath.joinToString(":")
        val optionLines =
            spec.javaOptions.joinToString(" \\\n") { opt ->
                "  ${quoteForDoubleQuotes(opt)}"
            }
        val bakedArgs =
            spec.arguments.joinToString(" ") { arg ->
                quoteForDoubleQuotes(arg)
            }
        val execTail =
            buildString {
                append("  ${quoteForDoubleQuotes(spec.mainClass)}")
                if (bakedArgs.isNotEmpty()) {
                    append(' ')
                    append(bakedArgs)
                }
                append(" \\\n  \"$@\"")
            }

        return $$"""
            #!/bin/sh
            # Generated by Nucleus: exec the system JRE instead of a bundled jlink runtime.
            set -eu

            SCRIPT="$0"
            while [ -L "$SCRIPT" ]; do
              TARGET="$(readlink "$SCRIPT")"
              case "$TARGET" in
                /*) SCRIPT="$TARGET" ;;
                *) SCRIPT="$(dirname "$SCRIPT")/$TARGET" ;;
              esac
            done
            BINDIR="$(CDPATH= cd -- "$(dirname "$SCRIPT")" && pwd)"
            ROOTDIR="$(CDPATH= cd -- "$BINDIR/.." && pwd)"
            APPDIR="$ROOTDIR/lib/app"

            for arg in "$@"; do
              shift
              [ "$arg" = "--no-sandbox" ] && continue
              set -- "$@" "$arg"
            done

            nucleus_java_major() {
              _ver="$("$1" -version 2>&1)" || return 1
              echo "$_ver" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
            }

            nucleus_has_desktop() {
              # Headless JREs still ship the java.desktop *module* and libawt.so.
              # The X11/Wayland peer (libawt_xawt.so) is what a Compose/AWT UI needs.
              _home="$(CDPATH= cd -- "$(dirname "$1")/.." && pwd)"
              [ -f "$_home/lib/libawt_xawt.so" ] || [ -f "$_home/lib/amd64/libawt_xawt.so" ]
            }

            nucleus_add_java() {
              _j="$1"
              [ -n "$_j" ] && [ -x "$_j" ] || return 0
              case " $NUCLEUS_JAVA_CANDIDATES " in
                *" $_j "*) return 0 ;;
              esac
              NUCLEUS_JAVA_CANDIDATES="$NUCLEUS_JAVA_CANDIDATES $_j"
            }

            NUCLEUS_JAVA_CANDIDATES=""
            if [ -n "${JAVA_HOME:-}" ]; then
              nucleus_add_java "$JAVA_HOME/bin/java"
            fi
            for _dir in \
              /usr/lib/jvm/java-@MIN@-openjdk* \
              /usr/lib/jvm/java-*-openjdk* \
              /usr/lib/jvm/java-@MIN@* \
              /usr/lib/jvm/temurin-@MIN@* \
              /usr/lib/jvm/jdk-@MIN@* \
              /usr/lib/jvm/default-java
            do
              nucleus_add_java "$_dir/bin/java"
            done
            if command -v java >/dev/null 2>&1; then
              nucleus_add_java "$(command -v java)"
            fi

            JAVA=""
            for _cand in $NUCLEUS_JAVA_CANDIDATES; do
              _major="$(nucleus_java_major "$_cand")" || continue
              [ -n "$_major" ] || continue
              [ "$_major" -ge @MIN@ ] || continue
              nucleus_has_desktop "$_cand" || continue
              JAVA="$_cand"
              break
            done

            if [ -z "$JAVA" ]; then
              echo "This application needs a Java @MIN@+ full JRE (not headless)." >&2
              echo "Install a matching package (e.g. openjdk-@MIN@-jre / java-@MIN@-openjdk) or set JAVA_HOME." >&2
              exit 1
            fi

            if [ "${NUCLEUS_PRINT_JAVA:-}" = "1" ]; then
              printf '%s\n' "$JAVA"
              exit 0
            fi

            CP="@CLASSPATH@"
            exec "$JAVA" \
            @JAVA_OPTIONS@ \
              -cp "$CP" \
            @EXEC_TAIL@
            """.trimIndent()
            .replace("@MIN@", minMajor.toString())
            .replace("@CLASSPATH@", classpathLiteral)
            .replace("@JAVA_OPTIONS@", optionLines)
            .replace("@EXEC_TAIL@", execTail) + "\n"
    }

    /**
     * Double-quote [value] for POSIX sh, leaving `$APPDIR` / `$ROOTDIR` / `$BINDIR`
     * as shell expansions and escaping every other `$`.
     */
    internal fun quoteForDoubleQuotes(value: String): String {
        val escaped =
            buildString(value.length + QUOTE_OVERHEAD) {
                var i = 0
                while (i < value.length) {
                    when (val c = value[i]) {
                        '\\', '"' -> {
                            append('\\')
                            append(c)
                            i++
                        }
                        '$' -> {
                            val rest = value.substring(i)
                            val kept =
                                when {
                                    rest.startsWith("\$APPDIR") -> "\$APPDIR"
                                    rest.startsWith("\$ROOTDIR") -> "\$ROOTDIR"
                                    rest.startsWith("\$BINDIR") -> "\$BINDIR"
                                    else -> null
                                }
                            if (kept != null) {
                                append(kept)
                                i += kept.length
                            } else {
                                append('\\')
                                append('$')
                                i++
                            }
                        }
                        else -> {
                            append(c)
                            i++
                        }
                    }
                }
            }
        return "\"$escaped\""
    }
}

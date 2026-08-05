package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.ExactReachabilityMetadata
import java.io.File

/**
 * Resolved native-image / runtime flags for exact reachability metadata.
 *
 * [buildArgs] is empty when the mode is off, the build is not a quick-build dev loop, the
 * package list could not be derived, or the toolchain is older than JDK 23 and the legacy
 * host option is also unavailable. [runtimeArgs] mirrors that decision so a binary not
 * built with exact mode never gets a useless reporting flag.
 */
internal data class ExactReachabilityResolution(
    val buildArgs: List<String>,
    val runtimeArgs: List<String>,
    val packages: List<String>,
    val warning: String?,
    val lifecycleMessage: String?,
)

/**
 * Reporting mode for missing registrations at runtime
 * (`-XX:MissingRegistrationReportingMode=`).
 *
 * - [WARN] — log every miss and keep running (default; surfaces all gaps in one run)
 * - [EXIT] — terminate the process on the first miss (catches code that swallows `Throwable`)
 * - [THROW] — throw `MissingReflectionRegistrationError` (default GraalVM behaviour under
 *   exact mode without this option)
 */
internal enum class MissingRegistrationReportingMode(
    val id: String,
) {
    WARN("Warn"),
    EXIT("Exit"),
    THROW("Throw"),
    ;

    val runtimeFlag: String get() = "-XX:MissingRegistrationReportingMode=$id"

    companion object {
        fun parse(raw: String?): MissingRegistrationReportingMode {
            if (raw.isNullOrBlank()) return WARN
            return entries.find { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: entries.find { it.id.equals(raw.trim(), ignoreCase = true) }
                ?: WARN
        }
    }
}

/** Feature version from which `--exact-reachability-metadata` is available (GraalVM for JDK 23). */
internal const val EXACT_REACHABILITY_METADATA_MIN_JAVA = 23

/**
 * Package of [mainClassName] used as the default exact-reachability scope.
 * `com.example.demo.MainKt` → `com.example.demo`. Returns `null` for the default package
 * or a blank/null main class so we never enable unscoped exact mode by accident.
 */
internal fun packagePrefixOfMainClass(mainClassName: String?): String? {
    if (mainClassName.isNullOrBlank()) return null
    val lastDot = mainClassName.lastIndexOf('.')
    if (lastDot <= 0) return null
    return mainClassName.substring(0, lastDot).trimEnd('.')
}

/**
 * JDK feature major from a GraalVM/JDK `release` file (`JAVA_VERSION="25.0.4"` → 25).
 * Returns 0 when the file is missing or unparsable.
 */
internal fun graalvmJavaFeatureVersion(javaHome: File): Int {
    val release = javaHome.resolve("release")
    if (!release.isFile) return 0
    val raw =
        release
            .readLines()
            .firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?: return 0
    return raw.substringBefore('.').toIntOrNull() ?: 0
}

/**
 * Resolves the package list the DSL wants applied on the quick-build dev loop.
 * Empty when exact mode is off or no packages can be derived.
 */
internal fun resolveExactReachabilityPackages(
    setting: ExactReachabilityMetadata,
    mainClassName: String?,
): Pair<List<String>, String?> {
    return when (setting.kind) {
        ExactReachabilityMetadata.Kind.OFF -> emptyList<String>() to null
        ExactReachabilityMetadata.Kind.PACKAGES -> setting.packages to null
        ExactReachabilityMetadata.Kind.APP_PACKAGES -> {
            val prefix = packagePrefixOfMainClass(mainClassName)
            if (prefix == null) {
                emptyList<String>() to
                    "exactReachabilityMetadata = APP_PACKAGES but mainClass " +
                        "'$mainClassName' has no package — skipping " +
                        "--exact-reachability-metadata (set ExactReachabilityMetadata.packages(...) " +
                        "or OFF explicitly)."
            } else {
                listOf(prefix) to null
            }
        }
    }
}

/**
 * Builds the native-image and runtime args for exact reachability metadata.
 *
 * Only active when [quickBuild] is true (the `runGraalvmNative` dev loop). Distributable
 * builds always get empty args so packaging is byte-for-byte unaffected.
 *
 * On JDK 23+ emits `--exact-reachability-metadata=<packages>`; on older toolchains falls
 * back to `-H:ThrowMissingRegistrationErrors=<packages>` when a feature version is known,
 * otherwise logs a warning and skips.
 */
internal fun resolveExactReachabilityMetadata(
    setting: ExactReachabilityMetadata,
    mainClassName: String?,
    quickBuild: Boolean,
    javaHome: File,
    reportingMode: MissingRegistrationReportingMode,
): ExactReachabilityResolution {
    if (!quickBuild) {
        return ExactReachabilityResolution(
            buildArgs = emptyList(),
            runtimeArgs = emptyList(),
            packages = emptyList(),
            warning = null,
            lifecycleMessage = null,
        )
    }
    val (packages, packageWarning) = resolveExactReachabilityPackages(setting, mainClassName)
    return resolveExactReachabilityMetadata(
        packages = packages,
        packageWarning = packageWarning,
        quickBuild = true,
        javaHome = javaHome,
        reportingMode = reportingMode,
    )
}

/**
 * Same as [resolveExactReachabilityMetadata] when the package list is already resolved
 * (avoids re-deriving it inside the native-image task action).
 */
internal fun resolveExactReachabilityMetadata(
    packages: List<String>,
    packageWarning: String? = null,
    quickBuild: Boolean,
    javaHome: File,
    reportingMode: MissingRegistrationReportingMode,
): ExactReachabilityResolution {
    if (!quickBuild) {
        return ExactReachabilityResolution(
            buildArgs = emptyList(),
            runtimeArgs = emptyList(),
            packages = emptyList(),
            warning = null,
            lifecycleMessage = null,
        )
    }
    if (packages.isEmpty()) {
        return ExactReachabilityResolution(
            buildArgs = emptyList(),
            runtimeArgs = emptyList(),
            packages = emptyList(),
            warning = packageWarning,
            lifecycleMessage = null,
        )
    }

    val featureVersion = graalvmJavaFeatureVersion(javaHome)
    val packageList = packages.joinToString(",")
    val buildArgs: List<String>
    val warning: String?
    when {
        featureVersion >= EXACT_REACHABILITY_METADATA_MIN_JAVA -> {
            buildArgs = listOf("--exact-reachability-metadata=$packageList")
            warning = packageWarning
        }
        featureVersion > 0 -> {
            // Pre-JDK 23 equivalent (host option). Empty value enables globally; with a
            // package list it scopes the same way as the public flag.
            buildArgs = listOf("-H:ThrowMissingRegistrationErrors=$packageList")
            warning =
                listOfNotNull(
                    packageWarning,
                    "exact-reachability-metadata: toolchain is JDK $featureVersion " +
                        "(needs $EXACT_REACHABILITY_METADATA_MIN_JAVA+ for " +
                        "--exact-reachability-metadata); using " +
                        "-H:ThrowMissingRegistrationErrors=$packageList instead.",
                ).joinToString(" ")
                    .ifBlank { null }
        }
        else -> {
            return ExactReachabilityResolution(
                buildArgs = emptyList(),
                runtimeArgs = emptyList(),
                packages = packages,
                warning =
                    listOfNotNull(
                        packageWarning,
                        "exact-reachability-metadata: could not read JAVA_VERSION from " +
                            "${javaHome.resolve("release")} — skipping " +
                            "--exact-reachability-metadata.",
                    ).joinToString(" "),
                lifecycleMessage = null,
            )
        }
    }

    return ExactReachabilityResolution(
        buildArgs = buildArgs,
        runtimeArgs = listOf(reportingMode.runtimeFlag),
        packages = packages,
        warning = warning,
        lifecycleMessage =
            "Exact reachability metadata (dev loop): ${buildArgs.single()} " +
                "(runtime ${reportingMode.runtimeFlag})",
    )
}

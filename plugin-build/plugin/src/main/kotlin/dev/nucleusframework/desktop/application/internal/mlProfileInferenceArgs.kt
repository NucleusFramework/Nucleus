package dev.nucleusframework.desktop.application.internal

/**
 * Oracle GraalVM's ML-inferred PGO profile opt-out, checked against the resolved toolchain.
 *
 * [args] is empty when ML profile inference stays at its default (enabled), or when the opt-out
 * is dropped on a community toolchain — in which case [warning] explains why. Community builds
 * have no ML profile inference in the first place, so the configuration is already a no-op there.
 */
internal data class MlProfileInferenceResolution(
    val args: List<String>,
    val warning: String?,
)

/**
 * Resolves the `-H:-MLProfileInference` flag. The option is Oracle GraalVM-only; community
 * toolchains (GraalVM CE, Liberica NIK, Mandrel) reject it as unknown and fail the build.
 */
internal fun resolveMlProfileInferenceArgs(
    mlProfileInference: Boolean,
    isOracleGraalvm: Boolean,
    graalvmHome: String,
): MlProfileInferenceResolution {
    if (mlProfileInference) {
        return MlProfileInferenceResolution(args = emptyList(), warning = null)
    }
    if (isOracleGraalvm) {
        return MlProfileInferenceResolution(
            args = listOf("-H:-MLProfileInference"),
            warning = null,
        )
    }
    return MlProfileInferenceResolution(
        args = emptyList(),
        warning =
            "mlProfileInference = false ignored — -H:-MLProfileInference requires Oracle " +
                "GraalVM (current toolchain: $graalvmHome). ML profile inference is " +
                "Oracle-only, so community builds already behave as if it were disabled.",
    )
}

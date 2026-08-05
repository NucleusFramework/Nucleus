package dev.nucleusframework.desktop.application.dsl

import java.io.Serializable

/**
 * Controls GraalVM's `--exact-reachability-metadata` on the `runGraalvmNative` (quick-build)
 * dev loop.
 *
 * When enabled, unregistered reflective lookups throw `MissingReflectionRegistrationError`
 * naming the missing element, instead of degrading into a nested `ClassNotFoundException`
 * chain. Applied **only** on the fast dev loop — never on
 * `createGraalvmNativeDistributable` / `packageGraalvmNativeDistributionForCurrentOS` —
 * because optional-dependency probes (`try { Class.forName(...) } catch`) stop taking
 * their fallback path under exact mode.
 *
 * Scoped to app packages by default so third-party / JDK probes (e.g.
 * `sun.net.www.protocol.http.HttpURLConnection`) do not false-positive. See
 * [oracle/graal#10264](https://github.com/oracle/graal/discussions/10264).
 *
 * ```kotlin
 * graalvm {
 *     // Default on the dev loop: scope to the package of mainClass
 *     exactReachabilityMetadata = ExactReachabilityMetadata.APP_PACKAGES
 *
 *     // Multiple roots:
 *     exactReachabilityMetadata = ExactReachabilityMetadata.packages(
 *         "io.github.acme",
 *         "com.acme.shared",
 *     )
 *
 *     // Opt out entirely:
 *     exactReachabilityMetadata = ExactReachabilityMetadata.OFF
 * }
 * ```
 */
class ExactReachabilityMetadata
    private constructor(
        internal val kind: Kind,
        internal val packages: List<String> = emptyList(),
    ) : Serializable {
        internal enum class Kind {
            OFF,
            APP_PACKAGES,
            PACKAGES,
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExactReachabilityMetadata) return false
            return kind == other.kind && packages == other.packages
        }

        override fun hashCode(): Int = 31 * kind.hashCode() + packages.hashCode()

        override fun toString(): String =
            when (kind) {
                Kind.OFF -> "ExactReachabilityMetadata.OFF"
                Kind.APP_PACKAGES -> "ExactReachabilityMetadata.APP_PACKAGES"
                Kind.PACKAGES -> "ExactReachabilityMetadata.packages(${packages.joinToString()})"
            }

        companion object {
            private const val serialVersionUID: Long = 1L

            /** Never emit `--exact-reachability-metadata`, even on the dev loop. */
            @JvmField
            val OFF: ExactReachabilityMetadata = ExactReachabilityMetadata(Kind.OFF)

            /**
             * On the dev loop, scope exact reachability to the package of
             * [dev.nucleusframework.desktop.application.dsl.JvmApplication.mainClass]
             * (and its subpackages). Default.
             */
            @JvmField
            val APP_PACKAGES: ExactReachabilityMetadata = ExactReachabilityMetadata(Kind.APP_PACKAGES)

            /**
             * On the dev loop, scope exact reachability to the given package prefixes
             * (comma-separated list passed to `--exact-reachability-metadata=`).
             * Subpackages are covered by prefix matching.
             */
            @JvmStatic
            fun packages(vararg packages: String): ExactReachabilityMetadata =
                packages(packages.asList())

            /**
             * On the dev loop, scope exact reachability to the given package prefixes.
             * Empty or blank entries are ignored; if none remain the mode is treated as [OFF].
             */
            @JvmStatic
            fun packages(packages: Collection<String>): ExactReachabilityMetadata {
                val cleaned =
                    packages
                        .map { it.trim().trimEnd('.') }
                        .filter { it.isNotEmpty() }
                        .distinct()
                return if (cleaned.isEmpty()) {
                    OFF
                } else {
                    ExactReachabilityMetadata(Kind.PACKAGES, cleaned)
                }
            }
        }
    }

package dev.nucleusframework.lab.core

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.summary

/** Whether a capability works here, and if not, why — never a silently empty screen. */
@Immutable
sealed interface Availability {
    data object Available : Availability

    data class Unavailable(
        val reason: String,
    ) : Availability

    /** Not determined yet (async probe in flight). */
    data object Unknown : Availability

    val isAvailable: Boolean get() = this == Available

    companion object {
        fun of(
            available: Boolean,
            reasonIfNot: () -> String,
        ): Availability = if (available) Available else Unavailable(reasonIfNot())

        /**
         * Runs [probe], turning any throwable — `UnsatisfiedLinkError` and other linkage
         * errors included — into an [Unavailable] carrying it: a broken native library must
         * make one capability unavailable, never take the probe down.
         */
        inline fun catching(
            reasonIfNot: () -> String = { "reported unsupported" },
            probe: () -> Boolean,
        ): Availability =
            runCatching(probe).fold(
                onSuccess = { if (it) Available else Unavailable(reasonIfNot()) },
                onFailure = { Unavailable(it.summary) },
            )

        /** Why a macOS capability is missing in a dev run: it needs a bundle identity. */
        const val NEEDS_APP_BUNDLE: String = "needs a packaged .app (run :examples:lab:app:runDistributable)"
    }
}

/** A named capability row of a probe's CAPABILITIES section. */
@Immutable
data class Capability(
    val name: String,
    val availability: Availability,
    val detail: String? = null,
)

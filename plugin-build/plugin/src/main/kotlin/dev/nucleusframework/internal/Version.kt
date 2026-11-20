package dev.nucleusframework.internal

import kotlin.math.min

internal data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val meta: String,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        when {
            major != other.major -> major - other.major
            minor != other.minor -> minor - other.minor
            patch != other.patch -> patch - other.patch
            else -> {
                if (meta.isEmpty()) {
                    1
                } else if (other.meta.isEmpty()) {
                    -1
                } else {
                    val metaParts = meta.split("-")
                    val otherMetaParts = other.meta.split("-")

                    var result = 0
                    for (i in 0 until min(metaParts.size, otherMetaParts.size)) {
                        val metaPart = metaParts[i]
                        val otherMetaPart = otherMetaParts[i]
                        if (metaPart != otherMetaPart) {
                            result = metaPart.compareTo(otherMetaPart)
                            break
                        }
                    }
                    if (result != 0) {
                        result
                    } else {
                        if (metaParts.size < otherMetaParts.size) {
                            1
                        } else if (metaParts.size > otherMetaParts.size) {
                            -1
                        } else {
                            0
                        }
                    }
                }
            }
        }

    companion object {
        // Capturing groups of SEMVER_REGEXP.
        private const val MAJOR_GROUP = 1
        private const val MINOR_GROUP = 2
        private const val PATCH_GROUP = 3
        private const val META_GROUP = 4

        private val SEMVER_REGEXP = """^(\d+)(?:\.(\d*))?(?:\.(\d*))?(?:-(.*))?${'$'}""".toRegex()

        fun fromString(versionString: String): Version {
            val matchResult: MatchResult = SEMVER_REGEXP.matchEntire(versionString) ?: return Version(0, 0, 0, "")
            val major: Int = matchResult.groups[MAJOR_GROUP]?.value?.toInt() ?: 0
            val minor: Int = matchResult.groups[MINOR_GROUP]?.value?.toInt() ?: 0
            val patch: Int = matchResult.groups[PATCH_GROUP]?.value?.toInt() ?: 0
            val meta: String = matchResult.groups[META_GROUP]?.value.orEmpty()
            return Version(major, minor, patch, meta)
        }
    }
}

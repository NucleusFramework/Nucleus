package dev.nucleusframework.updater

import kotlin.math.min

public data class Version(
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
            else -> compareMeta(meta, other.meta)
        }

    override fun toString(): String =
        buildString {
            append("$major.$minor.$patch")
            if (meta.isNotEmpty()) append("-$meta")
        }

    public fun levelFrom(other: Version): UpdateLevel =
        when {
            major != other.major -> UpdateLevel.MAJOR
            minor != other.minor -> UpdateLevel.MINOR
            patch != other.patch -> UpdateLevel.PATCH
            else -> UpdateLevel.PRE_RELEASE
        }

    public companion object {
        private val SEMVER_REGEXP = """^(\d+)(?:\.(\d*))?(?:\.(\d*))?(?:-(.*))?${'$'}""".toRegex()

        private const val GROUP_MAJOR = 1
        private const val GROUP_MINOR = 2
        private const val GROUP_PATCH = 3
        private const val GROUP_META = 4

        public fun fromString(versionString: String): Version {
            val matchResult =
                SEMVER_REGEXP.matchEntire(versionString.trim())
                    ?: return Version(0, 0, 0, "")
            val major = matchResult.groups[GROUP_MAJOR]?.value?.toInt() ?: 0
            val minor = matchResult.groups[GROUP_MINOR]?.value?.toInt() ?: 0
            val patch = matchResult.groups[GROUP_PATCH]?.value?.toInt() ?: 0
            val meta = matchResult.groups[GROUP_META]?.value ?: ""
            return Version(major, minor, patch, meta)
        }

        private fun compareMeta(
            a: String,
            b: String,
        ): Int {
            if (a.isEmpty() && b.isEmpty()) return 0
            if (a.isEmpty()) return 1 // No pre-release > pre-release
            if (b.isEmpty()) return -1 // Pre-release < no pre-release

            val aParts = a.split(".")
            val bParts = b.split(".")

            for (i in 0 until min(aParts.size, bParts.size)) {
                val aPart = aParts[i]
                val bPart = bParts[i]
                if (aPart == bPart) continue

                val aNum = aPart.toLongOrNull()
                val bNum = bPart.toLongOrNull()
                return when {
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    aNum != null -> -1 // Numeric < string
                    bNum != null -> 1 // String > numeric
                    else -> aPart.compareTo(bPart)
                }
            }
            return aParts.size.compareTo(bParts.size)
        }
    }
}

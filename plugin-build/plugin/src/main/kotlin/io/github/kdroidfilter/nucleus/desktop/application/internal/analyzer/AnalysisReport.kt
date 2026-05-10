package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

/**
 * Classification of an Oracle repo entry relative to what the static analyzer found.
 */
internal enum class DetectionStatus {
    /** Found by static analysis — no tracing agent needed. */
    DETECTED,

    /** Class found but not all methods/fields — agent may refine. */
    PARTIALLY_DETECTED,

    /** Present in Oracle repo but impossible to find statically — agent required. */
    NOT_DETECTABLE,

    /** Found by analyzer but not in Oracle repo — potential new discovery. */
    EXTRA,
}

/**
 * A single entry in the gap analysis report.
 */
internal data class GapReportEntry(
    val type: String,
    val status: DetectionStatus,
    val reason: String? = null,
    val missingMethods: Set<MethodSignature> = emptySet(),
    val missingFields: Set<String> = emptySet(),
)

/**
 * Gap analysis report comparing analyzer output against an Oracle repo baseline.
 */
internal data class AnalysisReport(
    val entries: List<GapReportEntry>,
) {
    val detected: List<GapReportEntry>
        get() = entries.filter { it.status == DetectionStatus.DETECTED }

    val partiallyDetected: List<GapReportEntry>
        get() = entries.filter { it.status == DetectionStatus.PARTIALLY_DETECTED }

    val notDetectable: List<GapReportEntry>
        get() = entries.filter { it.status == DetectionStatus.NOT_DETECTABLE }

    val extra: List<GapReportEntry>
        get() = entries.filter { it.status == DetectionStatus.EXTRA }

    /**
     * Formats the report as a human-readable string.
     */
    fun format(): String =
        buildString {
            appendLine("=== Static Bytecode Analysis Gap Report ===")
            appendLine()
            appendLine("Summary:")
            appendLine("  DETECTED:           ${detected.size}")
            appendLine("  PARTIALLY_DETECTED: ${partiallyDetected.size}")
            appendLine("  NOT_DETECTABLE:     ${notDetectable.size}")
            appendLine("  EXTRA:              ${extra.size}")

            if (notDetectable.isNotEmpty()) {
                appendLine()
                appendLine("--- NOT_DETECTABLE (agent required) ---")
                for (entry in notDetectable) {
                    appendLine("  ${entry.type}")
                    if (entry.reason != null) {
                        appendLine("    Reason: ${entry.reason}")
                    }
                }
            }

            if (partiallyDetected.isNotEmpty()) {
                appendLine()
                appendLine("--- PARTIALLY_DETECTED (agent may refine) ---")
                for (entry in partiallyDetected) {
                    appendLine("  ${entry.type}")
                    if (entry.missingMethods.isNotEmpty()) {
                        appendLine(
                            "    Missing methods: ${entry.missingMethods.joinToString {
                                "${it.name}(${it.parameterTypes.joinToString(
                                    ",",
                                )})"
                            }}",
                        )
                    }
                    if (entry.missingFields.isNotEmpty()) {
                        appendLine("    Missing fields: ${entry.missingFields.joinToString()}")
                    }
                }
            }

            if (extra.isNotEmpty()) {
                appendLine()
                appendLine("--- EXTRA (new discoveries) ---")
                for (entry in extra) {
                    appendLine("  ${entry.type}")
                }
            }
        }
}

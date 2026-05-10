package dev.nucleusframework.nucleus.desktop.application.internal.analyzer

/**
 * Compares analyzer output against an Oracle repo baseline and produces a gap report.
 *
 * For each entry in the Oracle baseline:
 * - DETECTED: the analyzer found the type with all required methods/fields
 * - PARTIALLY_DETECTED: the analyzer found the type but some methods/fields are missing
 * - NOT_DETECTABLE: the analyzer couldn't find the type at all
 *
 * For entries found by the analyzer but not in the baseline: EXTRA
 */
internal object ResultComparator {
    /**
     * Compares reflection entries from the analyzer against an Oracle baseline.
     */
    fun compareReflection(
        analyzerResult: AnalysisResult,
        oracleBaseline: AnalysisResult,
    ): AnalysisReport {
        val entries = mutableListOf<GapReportEntry>()
        val analyzerByType = analyzerResult.allReflectionEntries.associateBy { it.type }
        val oracleByType = oracleBaseline.reflectionEntries.associateBy { it.type }

        // Check each Oracle entry against analyzer output
        for ((type, oracleEntry) in oracleByType) {
            val analyzerEntry = analyzerByType[type]

            if (analyzerEntry == null) {
                entries.add(
                    GapReportEntry(
                        type = type,
                        status = DetectionStatus.NOT_DETECTABLE,
                        reason = inferNotDetectableReason(type),
                    ),
                )
                continue
            }

            // Check if all required methods/fields are present
            val missingMethods = oracleEntry.methods - analyzerEntry.methods
            val missingFields = oracleEntry.fields - analyzerEntry.fields

            // If Oracle has broad flags that analyzer didn't set, consider partial
            val hasMissingBroadFlags = hasMissingBroadFlags(oracleEntry, analyzerEntry)

            when {
                missingMethods.isEmpty() && missingFields.isEmpty() && !hasMissingBroadFlags -> {
                    entries.add(GapReportEntry(type = type, status = DetectionStatus.DETECTED))
                }
                else -> {
                    entries.add(
                        GapReportEntry(
                            type = type,
                            status = DetectionStatus.PARTIALLY_DETECTED,
                            missingMethods = missingMethods,
                            missingFields = missingFields,
                        ),
                    )
                }
            }
        }

        // Check for extra entries found by analyzer but not in Oracle
        for (type in analyzerByType.keys) {
            if (type !in oracleByType) {
                entries.add(GapReportEntry(type = type, status = DetectionStatus.EXTRA))
            }
        }

        return AnalysisReport(entries = entries.sortedBy { it.type })
    }

    /**
     * Compares JNI entries from the analyzer against an Oracle baseline.
     */
    fun compareJni(
        analyzerResult: AnalysisResult,
        oracleBaseline: AnalysisResult,
    ): AnalysisReport {
        val entries = mutableListOf<GapReportEntry>()
        val analyzerByType = analyzerResult.jniEntries.associateBy { it.type }
        val oracleByType = oracleBaseline.jniEntries.associateBy { it.type }

        for ((type, oracleEntry) in oracleByType) {
            val analyzerEntry = analyzerByType[type]

            if (analyzerEntry == null) {
                entries.add(
                    GapReportEntry(
                        type = type,
                        status = DetectionStatus.NOT_DETECTABLE,
                        reason = inferNotDetectableReason(type),
                    ),
                )
                continue
            }

            val missingMethods = oracleEntry.methods - analyzerEntry.methods
            val missingFields = oracleEntry.fields - analyzerEntry.fields

            when {
                missingMethods.isEmpty() && missingFields.isEmpty() -> {
                    entries.add(GapReportEntry(type = type, status = DetectionStatus.DETECTED))
                }
                else -> {
                    entries.add(
                        GapReportEntry(
                            type = type,
                            status = DetectionStatus.PARTIALLY_DETECTED,
                            missingMethods = missingMethods,
                            missingFields = missingFields,
                        ),
                    )
                }
            }
        }

        for (type in analyzerByType.keys) {
            if (type !in oracleByType) {
                entries.add(GapReportEntry(type = type, status = DetectionStatus.EXTRA))
            }
        }

        return AnalysisReport(entries = entries.sortedBy { it.type })
    }

    /**
     * Full comparison covering reflection, JNI, and resources.
     */
    fun compare(
        analyzerResult: AnalysisResult,
        oracleBaseline: AnalysisResult,
    ): AnalysisReport {
        val reflectionReport = compareReflection(analyzerResult, oracleBaseline)
        val jniReport = compareJni(analyzerResult, oracleBaseline)
        return AnalysisReport(entries = reflectionReport.entries + jniReport.entries)
    }

    private fun hasMissingBroadFlags(
        oracle: ReflectionEntry,
        analyzer: ReflectionEntry,
    ): Boolean =
        (oracle.allDeclaredFields && !analyzer.allDeclaredFields) ||
            (oracle.allDeclaredMethods && !analyzer.allDeclaredMethods) ||
            (oracle.allDeclaredConstructors && !analyzer.allDeclaredConstructors) ||
            (oracle.allPublicFields && !analyzer.allPublicFields) ||
            (oracle.allPublicMethods && !analyzer.allPublicMethods) ||
            (oracle.allPublicConstructors && !analyzer.allPublicConstructors)

    /**
     * Heuristically infers why a type couldn't be detected statically.
     */
    private fun inferNotDetectableReason(type: String): String =
        when {
            type.contains("\$\$") || type.contains("CGLIB") || type.contains("ByteBuddy") ->
                "Dynamic proxy/bytecode generation"
            type.contains("sun.") || type.contains("jdk.internal") ->
                "JDK internal class, loaded via runtime callback"
            type.endsWith("Impl") || type.endsWith("Factory") ->
                "Likely loaded via framework configuration or XML"
            else ->
                "Dynamic class loading or framework-driven instantiation"
        }
}

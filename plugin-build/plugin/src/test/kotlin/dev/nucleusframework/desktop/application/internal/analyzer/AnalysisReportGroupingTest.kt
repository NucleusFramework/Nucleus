package dev.nucleusframework.desktop.application.internal.analyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisReportGroupingTest {
    @Test
    fun `entries are grouped by detection status`() {
        val report =
            AnalysisReport(
                listOf(
                    GapReportEntry("a", DetectionStatus.DETECTED),
                    GapReportEntry("b", DetectionStatus.PARTIALLY_DETECTED),
                    GapReportEntry("c", DetectionStatus.NOT_DETECTABLE),
                    GapReportEntry("d", DetectionStatus.EXTRA),
                ),
            )

        assertEquals(listOf("a"), report.detected.map { it.type })
        assertEquals(listOf("b"), report.partiallyDetected.map { it.type })
        assertEquals(listOf("c"), report.notDetectable.map { it.type })
        assertEquals(listOf("d"), report.extra.map { it.type })
    }
}

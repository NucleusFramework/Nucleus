package dev.nucleusframework.desktop.application.internal.analyzer

import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisReportFormatTest {
    @Test
    fun `format includes status summary and reasons`() {
        val report =
            AnalysisReport(
                listOf(
                    GapReportEntry("java.lang.Runtime", DetectionStatus.NOT_DETECTABLE, reason = "runtime generated"),
                ),
            )

        val formatted = report.format()

        assertTrue(formatted.contains("NOT_DETECTABLE:     1"))
        assertTrue(formatted.contains("java.lang.Runtime"))
        assertTrue(formatted.contains("runtime generated"))
    }
}

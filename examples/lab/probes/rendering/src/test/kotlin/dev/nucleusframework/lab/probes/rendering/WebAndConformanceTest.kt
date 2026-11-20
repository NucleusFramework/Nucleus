package dev.nucleusframework.lab.probes.rendering

import dev.nucleusframework.lab.probes.rendering.common.FrameMeter
import dev.nucleusframework.lab.probes.rendering.conformance.ConformanceCatalog
import dev.nucleusframework.lab.probes.rendering.conformance.ConformanceEvent
import dev.nucleusframework.lab.probes.rendering.conformance.ConformanceReducer
import dev.nucleusframework.lab.probes.rendering.conformance.ConformanceState
import dev.nucleusframework.lab.probes.rendering.conformance.Levers
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory
import dev.nucleusframework.lab.probes.rendering.webview.WebPage
import dev.nucleusframework.lab.probes.rendering.webview.WebProbeEvent
import dev.nucleusframework.lab.probes.rendering.webview.WebProbeReducer
import dev.nucleusframework.lab.probes.rendering.webview.WebProbeState
import dev.nucleusframework.lab.probes.rendering.webview.WebReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAndConformanceTest {
    private fun report(loading: Boolean) =
        WebReport(loading, if (loading) 0.3f else 1f, "https://x.test", "X", 0, canGoBack = false, canGoForward = false)

    @Test
    fun `a navigation is timed from request to the end of loading`() {
        var state =
            WebProbeReducer.reduce(
                WebProbeState(),
                WebProbeEvent.NavigationRequested(WebPage.Remote("https://x.test"), atMillis = 1_000),
            )
        state = WebProbeReducer.reduce(state, WebProbeEvent.Reported(report(loading = true), atMillis = 1_100))
        assertTrue(state.navigations.isEmpty())
        state = WebProbeReducer.reduce(state, WebProbeEvent.Reported(report(loading = false), atMillis = 1_450))
        assertEquals(450, state.navigations.single().millis)
        assertNull(state.requestedAtMillis)
    }

    @Test
    fun `a finished report without a pending request records nothing`() {
        var state = WebProbeState(requestedAtMillis = null)
        state = WebProbeReducer.reduce(state, WebProbeEvent.Reported(report(loading = true), 10))
        state = WebProbeReducer.reduce(state, WebProbeEvent.Reported(report(loading = false), 20))
        assertTrue(state.navigations.isEmpty())
    }

    @Test
    fun `sample ids are unique and every category has samples`() {
        val ids = ConformanceCatalog.samples.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        SampleCategory.entries.forEach { category ->
            assertTrue(ConformanceCatalog.samples.any { it.category == category }, "no sample in $category")
        }
    }

    @Test
    fun `search spans categories and selecting a category clears it`() {
        val searched = ConformanceReducer.reduce(ConformanceState(), ConformanceEvent.Searched("gradient"))
        val hits = searched.visibleSamples(ConformanceCatalog.samples)
        assertTrue(hits.isNotEmpty() && hits.all { "gradient" in "${it.title} ${it.expect}".lowercase() })
        val selected = ConformanceReducer.reduce(searched, ConformanceEvent.CategorySelected(SampleCategory.Lists))
        assertEquals("", selected.query)
        assertTrue(selected.visibleSamples(ConformanceCatalog.samples).all { it.category == SampleCategory.Lists })
    }

    @Test
    fun `levers are clamped to the offered scales`() {
        val state =
            ConformanceReducer.reduce(
                ConformanceState(),
                ConformanceEvent.LeversChanged(Levers(fontScale = 9f, densityScale = 0.1f)),
            )
        assertEquals(ConformanceReducer.FontScales.last(), state.levers.fontScale)
        assertEquals(ConformanceReducer.DensityScales.first(), state.levers.densityScale)
    }

    @Test
    fun `frame meter ignores idle gaps`() {
        val meter = FrameMeter()
        meter.tick(1_000_000)
        meter.tick(17_000_000)
        meter.tick(5_017_000_000)
        val sample = meter.sample()
        assertEquals(3, sample.framesPerSecond)
        assertEquals(16.0, sample.worstFrameMillis)
    }
}

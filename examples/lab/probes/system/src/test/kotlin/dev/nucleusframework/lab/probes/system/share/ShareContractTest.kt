package dev.nucleusframework.lab.probes.system.share

import dev.nucleusframework.share.ShareError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareContractTest {
    private val invalid = ShareOutcome.Failed(ShareError.InvalidItem, "blank")

    @Test
    fun `expectations are met only by the matching outcome`() {
        assertTrue(Expectation.Presented.isMetBy(ShareOutcome.Presented))
        assertFalse(Expectation.Presented.isMetBy(invalid))
        assertTrue(Expectation.Fails(ShareError.InvalidItem).isMetBy(invalid))
        // A different error is a finding, not a pass.
        assertFalse(Expectation.Fails(ShareError.Empty).isMetBy(invalid))
        assertFalse(Expectation.Fails(ShareError.InvalidItem).isMetBy(ShareOutcome.Presented))
        assertTrue(Expectation.AnyError.isMetBy(ShareOutcome.Failed(null, "IOException")))
        assertFalse(Expectation.AnyError.isMetBy(ShareOutcome.Presented))
    }

    @Test
    fun `a finished attempt fills in its own row only`() {
        val state =
            listOf(
                ShareEvent.Started(ShareAttempt(1, 0, SharePayload.Text, ShareParentChoice.LabWindow)),
                ShareEvent.Started(ShareAttempt(2, 0, SharePayload.Blank, ShareParentChoice.Auto)),
                ShareEvent.Finished(2, invalid, 3),
            ).fold(ShareState(), ShareReducer::reduce)
        assertEquals(null, state.attempts[0].outcome)
        assertEquals(invalid, state.attempts[1].outcome)
        assertEquals(3, state.attempts[1].millis)
    }

    @Test
    fun `every malformed payload expects a failure`() {
        SharePayload.entries
            .filter {
                it in
                    setOf(
                        SharePayload.Blank,
                        SharePayload.Empty,
                        SharePayload.BadUrl,
                        SharePayload.ContentUri,
                        SharePayload.MissingFile,
                    )
            }.forEach { assertFalse(it.expectation == Expectation.Presented, it.name) }
    }
}

package dev.nucleusframework.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebSharePayloadTest {
    @Test
    fun firstUrlIsTheLinkAndTheRestStaysInTextInOrder() {
        val payload =
            shareRequest {
                text("hello")
                url("https://a.example")
                url("https://b.example")
                text("bye")
            }.toWebSharePayload()

        assertEquals("https://a.example", payload.url)
        assertEquals("hello\nhttps://b.example\nbye", payload.text)
    }

    @Test
    fun subjectWinsOverTitle() {
        assertEquals(
            "Subject",
            shareRequest {
                title = "Title"
                subject = "Subject"
                text("x")
            }.toWebSharePayload().title,
        )
        assertEquals(
            "Title",
            shareRequest {
                title = "Title"
                text("x")
            }.toWebSharePayload().title,
        )
    }

    @Test
    fun fileUrisAreFetchedAndLocalPathsRejected() {
        val payload = shareRequest { fileUri("blob:https://app/1", "image/png") }.toWebSharePayload()
        assertEquals(listOf(ShareItem.FileUri("blob:https://app/1", "image/png")), payload.files)
        assertNull(payload.text)
        assertNull(payload.url)

        val error = assertFailsWith<ShareException> { shareRequest { file("/tmp/a.txt") }.toWebSharePayload() }
        assertEquals(ShareError.UnsupportedItem, error.error)
    }
}

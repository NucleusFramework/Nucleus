package dev.nucleusframework.share

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShareRequestTest {
    @Test
    fun builderKeepsItemsInOrder() {
        val request =
            shareRequest {
                title = "Share"
                subject = "Nucleus"
                text("hello")
                url("https://nucleusframework.dev")
                file("report.pdf", "application/pdf")
                fileUri("content://app/report.txt")
            }

        assertEquals("Share", request.title)
        assertEquals("Nucleus", request.subject)
        assertEquals(
            listOf(
                ShareItem.Text("hello"),
                ShareItem.Url("https://nucleusframework.dev"),
                ShareItem.File("report.pdf", "application/pdf"),
                ShareItem.FileUri("content://app/report.txt"),
            ),
            request.items,
        )
    }

    @Test
    fun emptyRequestIsRejected() {
        val error = assertFailsWith<ShareException> { ShareRequest(emptyList()).validate() }
        assertEquals(ShareError.Empty, error.error)
    }

    @Test
    fun blankItemsAreRejected() {
        val invalid =
            listOf(
                ShareItem.Text(" "),
                ShareItem.Url(""),
                ShareItem.File(""),
                ShareItem.FileUri(""),
                ShareItem.File("share.txt", " "),
                ShareItem.FileUri("content://app/share.png", ""),
            )
        for (item in invalid) {
            val error = assertFailsWith<ShareException> { ShareRequest(listOf(item)).validate() }
            assertEquals(ShareError.InvalidItem, error.error, "$item")
        }
    }

    @Test
    fun shareValidatesBeforeReachingThePlatform() =
        runTest {
            val error = assertFailsWith<ShareException> { ShareSheet.share { } }
            assertEquals(ShareError.Empty, error.error)
        }

    @Test
    fun textsAndUrlsAreJoinedInOrder() {
        val request =
            shareRequest {
                text("hello")
                file("a.txt")
                url("https://nucleusframework.dev")
                text("goodbye")
            }
        assertEquals("hello\nhttps://nucleusframework.dev\ngoodbye", request.sharedText())
        assertNull(shareRequest { file("a.txt") }.sharedText())
    }

    @Test
    fun primaryMimeTypeFollowsAndroidSenders() {
        assertEquals("text/plain", primaryMimeType(emptyList(), hasText = true))
        assertEquals(ANY_MIME_TYPE, primaryMimeType(emptyList(), hasText = false))
        assertEquals("image/png", primaryMimeType(listOf("image/png"), hasText = true))
        assertEquals("image/*", primaryMimeType(listOf("image/png", "image/jpeg"), hasText = false))
        assertEquals(ANY_MIME_TYPE, primaryMimeType(listOf("image/png", "video/mp4"), hasText = false))
        assertEquals(ANY_MIME_TYPE, primaryMimeType(listOf("image/png", null), hasText = false))
        assertEquals(ANY_MIME_TYPE, primaryMimeType(listOf("image/"), hasText = false))
    }
}

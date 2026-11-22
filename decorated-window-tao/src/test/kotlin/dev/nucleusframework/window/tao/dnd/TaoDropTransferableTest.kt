package dev.nucleusframework.window.tao.dnd

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inbound payload → AWT flavors mapping (#930). Pure `java.datatransfer`,
 * so it runs headless, unlike [TaoSyntheticDndTest].
 */
class TaoDropTransferableTest {
    @Test
    fun `a browser image drag exposes its URL as string, uri-list and java URL`() {
        val url = "https://example.com/images/cat.png"
        val payload = TaoSceneDnD.inboundPayload(text = url, uris = arrayOf(url))
        val transferable = TaoDropTransferable(payload)

        assertFalse(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertEquals(url, transferable.getTransferData(DataFlavor.stringFlavor))
        assertEquals(url, transferable.getTransferData(TaoDropTransferable.URI_LIST_FLAVOR))
        assertEquals(URI(url).toURL(), transferable.getTransferData(TaoDropTransferable.URL_FLAVOR))
        assertContentEquals(
            arrayOf(TaoDropTransferable.URI_LIST_FLAVOR, TaoDropTransferable.URL_FLAVOR, DataFlavor.stringFlavor),
            transferable.transferDataFlavors,
        )
        assertFailsWith<UnsupportedFlavorException> {
            transferable.getTransferData(DataFlavor.javaFileListFlavor)
        }
    }

    @Test
    fun `plain text is offered as stringFlavor only`() {
        val transferable = TaoDropTransferable(TaoSceneDnD.inboundPayload(text = "hello\nworld"))

        assertEquals("hello\nworld", transferable.getTransferData(DataFlavor.stringFlavor))
        assertContentEquals(arrayOf(DataFlavor.stringFlavor), transferable.transferDataFlavors)
    }

    @Test
    fun `stringFlavor falls back to the URIs and then to the file paths`() {
        val uris = TaoDropTransferable(TaoSceneDnD.inboundPayload(uris = arrayOf("https://a.test/", "https://b.test/")))
        assertEquals("https://a.test/\nhttps://b.test/", uris.getTransferData(DataFlavor.stringFlavor))
        assertEquals("https://a.test/\r\nhttps://b.test/", uris.getTransferData(TaoDropTransferable.URI_LIST_FLAVOR))

        val path = File("report.pdf").absolutePath
        val files = TaoDropTransferable(TaoSceneDnD.inboundPayload(files = arrayOf(path)))
        assertEquals(listOf(File(path)), files.getTransferData(DataFlavor.javaFileListFlavor))
        assertEquals(path, files.getTransferData(DataFlavor.stringFlavor))
        assertFalse(files.isDataFlavorSupported(TaoDropTransferable.URI_LIST_FLAVOR))
    }

    @Test
    fun `an empty hover payload still answers an empty file list`() {
        val transferable = TaoDropTransferable(TaoSceneDnD.inboundPayload())

        assertEquals(emptyList<File>(), transferable.getTransferData(DataFlavor.javaFileListFlavor))
        assertEquals("", transferable.getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun `inboundPayload drops empty values and file URIs`() {
        val payload =
            TaoSceneDnD.inboundPayload(
                files = arrayOf("", "/tmp/a.txt"),
                text = "",
                uris = arrayOf(" https://example.com/ ", "file:///tmp/a.txt", "  "),
            )

        assertEquals(listOf("/tmp/a.txt"), payload.files)
        assertNull(payload.text)
        assertEquals(listOf("https://example.com/"), payload.uris)
        assertTrue(TaoSceneDnD.inboundPayload(text = "", uris = arrayOf("file:///x")).isEmpty)
    }

    @Test
    fun `a non-URL URI is still listed but offers no java URL`() {
        val transferable = TaoDropTransferable(TaoSceneDnD.inboundPayload(uris = arrayOf("urn:isbn:0451450523")))

        assertTrue(transferable.isDataFlavorSupported(TaoDropTransferable.URI_LIST_FLAVOR))
        assertFalse(transferable.isDataFlavorSupported(TaoDropTransferable.URL_FLAVOR))
    }
}

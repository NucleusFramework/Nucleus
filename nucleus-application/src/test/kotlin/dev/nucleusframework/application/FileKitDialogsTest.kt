package dev.nucleusframework.application

import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKitDialogsTest {
    private val windowParent = FileKitDialogParent.windows(0x42)

    @Test
    fun `parents the settings and releases the lease after the dialog`() =
        runBlocking {
            var released = false
            val settings = FileKitDialogSettings(title = "Open")
            val seen =
                withDialogParent(settings, { BorrowedDialogParent(windowParent) { released = true } }) {
                    assertFalse("lease released before the dialog finished", released)
                    it
                }
            assertSame(windowParent, seen.parent)
            assertEquals("Open", seen.title)
            assertTrue(released)
        }

    @Test
    fun `releases the lease when the dialog throws`() {
        var released = false
        runCatching {
            runBlocking {
                withDialogParent(FileKitDialogSettings(), { BorrowedDialogParent(windowParent) { released = true } }) {
                    error("picker failed")
                }
            }
        }
        assertTrue(released)
    }

    @Test
    fun `keeps a parent the caller already chose`() =
        runBlocking {
            val chosen = FileKitDialogSettings(parent = FileKitDialogParent.x11(7))
            val seen = withDialogParent(chosen, { error("must not resolve") }) { it }
            assertSame(chosen, seen)
        }

    @Test
    fun `leaves the settings unparented without a platform identity`() =
        runBlocking {
            val settings = FileKitDialogSettings()
            assertSame(settings, withDialogParent(settings, { null }) { it })
        }
}

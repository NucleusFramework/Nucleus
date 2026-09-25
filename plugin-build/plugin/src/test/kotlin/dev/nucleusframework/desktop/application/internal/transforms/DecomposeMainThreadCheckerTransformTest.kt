package dev.nucleusframework.desktop.application.internal.transforms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Swing `MainThreadChecker` provider is stripped from Decompose's service file (#513). */
class DecomposeMainThreadCheckerTransformTest {
    private val swing = "com.arkivanov.decompose.extensions.compose.mainthread.SwingMainThreadChecker"

    private fun strip(content: String): String? =
        stripSwingMainThreadChecker(content.toByteArray())?.toString(Charsets.UTF_8)

    @Test
    fun `a file listing only the Swing checker is emptied`() {
        assertEquals("", strip("$swing\n"))
        assertEquals("", strip("# Swing\n  $swing  # EDT only\n\n"))
    }

    @Test
    fun `other providers and comments are kept`() {
        assertEquals("# header\ncom.example.Checker\n", strip("# header\n$swing\ncom.example.Checker\n"))
    }

    @Test
    fun `a file without the Swing checker is left alone`() {
        assertNull(strip("com.example.Checker\n"))
    }
}

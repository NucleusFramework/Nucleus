package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NucleusWindowHostTest {
    @Test
    fun `hosted window without a host throws the documented error`() {
        val error =
            runCatching {
                runComposeUiTest {
                    setContent {
                        HostedWindow(onCloseRequest = {}) {}
                    }
                    waitForIdle()
                }
            }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("LocalNucleusWindowHost"))
    }

    @Test
    fun `hosted dialog without a host throws the documented error`() {
        val error =
            runCatching {
                runComposeUiTest {
                    setContent {
                        HostedDialog(onCloseRequest = {}) {}
                    }
                    waitForIdle()
                }
            }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("LocalNucleusDialogHost"))
    }

    @Test
    fun `hosted window and dialog forward the published arguments to the ambient host`() =
        runComposeUiTest {
            val windowHost = RecordingWindowHost()
            val dialogHost = RecordingDialogHost()
            setContent {
                CompositionLocalProvider(
                    LocalNucleusWindowHost provides windowHost,
                    LocalNucleusDialogHost provides dialogHost,
                ) {
                    HostedWindow(
                        onCloseRequest = windowHost::close,
                        title = "Editor",
                        visible = false,
                        resizable = false,
                        alwaysOnTop = true,
                        undecorated = true,
                        nativePopupLayers = true,
                        nativeContextMenu = true,
                        hiddenFromDock = true,
                        minimumSize = DpSize(120.dp, 80.dp),
                        alwaysOnBottom = true,
                    ) {}
                    HostedDialog(
                        onCloseRequest = dialogHost::close,
                        title = "Confirm",
                        visible = false,
                        resizable = true,
                        enabled = false,
                    ) {}
                }
            }
            waitForIdle()
            assertEquals("Editor", windowHost.title)
            assertFalse(windowHost.visible)
            assertFalse(windowHost.resizable)
            assertTrue(windowHost.alwaysOnTop)
            assertTrue(windowHost.undecorated)
            assertTrue(windowHost.nativePopupLayers)
            assertTrue(windowHost.nativeContextMenu)
            assertTrue(windowHost.hiddenFromDock)
            assertTrue(windowHost.alwaysOnBottom)
            assertEquals(DpSize(120.dp, 80.dp), windowHost.minimumSize)
            assertNull(windowHost.popupFor)
            windowHost.onCloseRequest()
            assertTrue(windowHost.closed)

            assertEquals("Confirm", dialogHost.title)
            assertFalse(dialogHost.visible)
            assertTrue(dialogHost.resizable)
            assertFalse(dialogHost.enabled)
            dialogHost.onCloseRequest()
            assertTrue(dialogHost.closed)
        }

    private class RecordingWindowHost : NucleusWindowHost {
        var title: String? = null
        var visible: Boolean = true
        var resizable: Boolean = true
        var alwaysOnTop: Boolean = false
        var undecorated: Boolean = false
        var nativePopupLayers: Boolean = false
        var nativeContextMenu: Boolean = false
        var hiddenFromDock: Boolean = false
        var alwaysOnBottom: Boolean = false
        var minimumSize: DpSize? = null
        var popupFor: NucleusWindow? = null
        lateinit var onCloseRequest: () -> Unit
        var closed: Boolean = false

        fun close() {
            closed = true
        }

        @Composable
        override fun Window(
            onCloseRequest: () -> Unit,
            state: WindowState,
            visible: Boolean,
            title: String,
            icon: Painter?,
            resizable: Boolean,
            enabled: Boolean,
            focusable: Boolean,
            alwaysOnTop: Boolean,
            undecorated: Boolean,
            popupFor: NucleusWindow?,
            nativePopupLayers: Boolean,
            nativeContextMenu: Boolean,
            hiddenFromDock: Boolean,
            minimumSize: DpSize?,
            onPreviewKeyEvent: (KeyEvent) -> Boolean,
            onKeyEvent: (KeyEvent) -> Boolean,
            alwaysOnBottom: Boolean,
            content: @Composable NucleusDecoratedWindowScope.() -> Unit,
        ) {
            this.onCloseRequest = onCloseRequest
            this.title = title
            this.visible = visible
            this.resizable = resizable
            this.alwaysOnTop = alwaysOnTop
            this.undecorated = undecorated
            this.popupFor = popupFor
            this.nativePopupLayers = nativePopupLayers
            this.nativeContextMenu = nativeContextMenu
            this.hiddenFromDock = hiddenFromDock
            this.minimumSize = minimumSize
            this.alwaysOnBottom = alwaysOnBottom
        }
    }

    private class RecordingDialogHost : NucleusDialogHost {
        var title: String? = null
        var visible: Boolean = true
        var resizable: Boolean = false
        var enabled: Boolean = true
        lateinit var onCloseRequest: () -> Unit
        var closed: Boolean = false

        fun close() {
            closed = true
        }

        @Composable
        override fun Dialog(
            onCloseRequest: () -> Unit,
            state: DialogState,
            visible: Boolean,
            title: String,
            icon: Painter?,
            resizable: Boolean,
            enabled: Boolean,
            focusable: Boolean,
            onPreviewKeyEvent: (KeyEvent) -> Boolean,
            onKeyEvent: (KeyEvent) -> Boolean,
            content: @Composable NucleusDecoratedDialogScope.() -> Unit,
        ) {
            this.onCloseRequest = onCloseRequest
            this.title = title
            this.visible = visible
            this.resizable = resizable
            this.enabled = enabled
        }
    }
}

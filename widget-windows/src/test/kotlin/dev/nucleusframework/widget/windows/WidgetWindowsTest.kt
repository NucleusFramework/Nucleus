package dev.nucleusframework.widget.windows

import dev.nucleusframework.core.runtime.Platform
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetWindowsTest {
    private object NoOpProvider :
        WidgetProvider,
        WidgetProvider2,
        WidgetProviderAnalytics,
        WidgetProviderErrors,
        WidgetProviderMessage,
        WidgetResourceProvider {
        override fun createWidget(widgetContext: WidgetContext) = Unit

        override fun deleteWidget(
            widgetId: String,
            customState: String,
        ) = Unit

        override fun onActionInvoked(actionInvokedArgs: WidgetActionInvokedArgs) = Unit

        override fun onWidgetContextChanged(contextChangedArgs: WidgetContextChangedArgs) = Unit

        override fun activate(widgetContext: WidgetContext) = Unit

        override fun deactivate(widgetId: String) = Unit

        override fun onCustomizationRequested(customizationRequestedArgs: WidgetCustomizationRequestedArgs) = Unit

        override fun onAnalyticsInfoReported(args: WidgetAnalyticsInfoReportedArgs) = Unit

        override fun onErrorInfoReported(args: WidgetErrorInfoReportedArgs) = Unit

        override fun onMessageReceived(args: WidgetMessageReceivedArgs) = Unit

        override fun onResourceRequested(args: WidgetResourceRequestedArgs) = Unit
    }

    @Test
    fun `registers and revokes a provider class object`() {
        if (Platform.Current != Platform.Windows) return
        // Resolves every dispatcher method id natively: a signature drift fails here.
        WidgetProviderServer.register(UUID.randomUUID(), NoOpProvider).use { }
    }

    @Test
    fun `dispatcher exposes every optional interface the provider implements`() {
        assertEquals(0b11111, WidgetProviderDispatcher(NoOpProvider).flags)
    }

    @Test
    fun `availability check does not throw without package identity`() {
        if (Platform.Current != Platform.Windows) return
        WidgetManager.isAvailable
    }

    @Test
    fun `detects a COM server launch`() {
        assertTrue(WidgetProviderServer.isComServerLaunch(arrayOf("-RegisterProcessAsComServer")))
        assertTrue(WidgetProviderServer.isComServerLaunch(arrayOf("-Embedding")))
        assertTrue(!WidgetProviderServer.isComServerLaunch(arrayOf("--foo")))
    }

    @Test
    fun `unpacks native widget info rows`() {
        val packed: Array<Any> =
            arrayOf(
                arrayOf("id", "def", "{}", "{\"a\":1}", "state"),
                longArrayOf(2, 1, 1_700_000_000_000, -1, 0, 0),
            )
        val info = WidgetInfo.unpack(packed).single()
        assertEquals(WidgetContext("id", "def", WidgetSize.Large, true), info.widgetContext)
        assertEquals("{\"a\":1}", info.data)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), info.lastUpdateTime)
        assertNull(info.isPlaceholderContent)
        assertNull(info.rank)
    }
}

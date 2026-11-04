package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.fillMaxWidth
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

private class SampleWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text("Say \"hi\"", style = TextStyle(TextSize.ExtraLarge, FontWeight.Bold, WidgetColor.Accent))
                Text("subtle", style = TextStyle(color = WidgetColor.Subtle))
                Row(modifier = WidgetModifier.background(WidgetBackground.Emphasis)) {
                    Button("+1", onClick = {}, modifier = WidgetModifier.fillMaxWidth())
                    Button("Open", onClick = actionOpenUrl("app://x"))
                }
            }
        }
    }
}

class AdaptiveCardRendererTest {
    @Test
    fun rendersAnAdaptiveCard() {
        NucleusWidgets.start { widget("Sample") { SampleWidget() } }
        val render =
            runBlocking {
                WidgetEngine.render(
                    WidgetInstance(WidgetId("w"), "Sample", WidgetSize.Medium, WidgetState.decode(null)),
                )
            }
        val json = AdaptiveCardRenderer.render(render.root)

        for (expected in listOf(
            "\"type\":\"AdaptiveCard\"",
            "\"version\":\"1.5\"",
            "\"text\":\"Say \\\"hi\\\"\"",
            "\"size\":\"extraLarge\"",
            "\"weight\":\"bolder\"",
            "\"color\":\"accent\"",
            "\"isSubtle\":true",
            "\"horizontalAlignment\":\"center\"",
            "\"type\":\"ColumnSet\"",
            "\"style\":\"emphasis\"",
            "\"width\":\"stretch\"",
            "{\"type\":\"Action.Execute\",\"verb\":\"a0\",\"title\":\"+1\"}",
            "{\"type\":\"Action.OpenUrl\",\"url\":\"app://x\",\"title\":\"Open\"}",
        )) {
            assertTrue(expected in json, "missing $expected in $json")
        }
    }
}

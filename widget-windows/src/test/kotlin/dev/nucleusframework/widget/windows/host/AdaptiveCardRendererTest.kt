package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Choice
import dev.nucleusframework.widget.ChoiceField
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.Image
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.NucleusWidgets
import dev.nucleusframework.widget.NumberField
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextField
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WebContent
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
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.fillMaxWidth
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
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

private val Name = stringStateKey("name")
private val On = booleanStateKey("on")
private val Size = intStateKey("size")

private class FormWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            Column {
                WebContent("https://example.com/w")
                Image(ImageProvider.appResource("widgets/logo.png"), "Logo")
                Image(ImageProvider.appResource("missing.png"), "Missing")
                TextField(Name, label = "Name", placeholder = "Your name")
                Switch(On, title = "Enabled")
                NumberField(Size, label = "Size", min = 1.0, max = 5.0)
                ChoiceField(Name, listOf(Choice("A", "a")), isExpanded = true)
            }
        }
    }
}

class AdaptiveCardRendererTest {
    init {
        NucleusWidgets.start {
            widget("Sample") { SampleWidget() }
            widget("Form") { FormWidget() }
        }
    }

    @Test
    fun rendersInputsWebContentAndResourceImages() {
        val state = WidgetState.decode(null).apply { this[Size] = 3 }
        val render =
            runBlocking { WidgetEngine.render(WidgetInstance(WidgetId("f"), "Form", WidgetSize.Medium, state)) }
        val json =
            AdaptiveCardRenderer.render(render.root) { path ->
                if (path == "widgets/logo.png") {
                    AdaptiveCardRenderer.ImageSource.Url("ms-appx:///app/app/resources/widgets/logo.png")
                } else {
                    AdaptiveCardRenderer.ImageSource.Missing
                }
            }

        for (expected in listOf(
            "\"metadata\":{\"webUrl\":\"https://example.com/w\"}",
            "\"url\":\"ms-appx:///app/app/resources/widgets/logo.png\"",
            "{\"type\":\"Input.Text\",\"id\":\"s:name\",\"label\":\"Name\",\"placeholder\":\"Your name\"",
            "\"type\":\"Input.Toggle\",\"id\":\"b:on\",\"title\":\"Enabled\",\"value\":\"false\"",
            "\"type\":\"Input.Number\",\"id\":\"i:size\",\"value\":3,\"label\":\"Size\",\"min\":1,\"max\":5",
            "\"type\":\"Input.ChoiceSet\",\"id\":\"s:name\",\"style\":\"expanded\"",
        )) {
            assertTrue(expected in json, "missing $expected in $json")
        }
        assertFalse("WebContent" in json || "\"Missing\"" in json, json)
    }

    @Test
    fun rendersAnAdaptiveCard() {
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

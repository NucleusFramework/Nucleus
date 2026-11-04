package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Choice
import dev.nucleusframework.widget.ChoiceField
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.Image
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.LocalSize
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.NumberField
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextField
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WebContent
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.actionFinishCustomization
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.isCustomizing
import dev.nucleusframework.widget.sendWidgetMessage
import dev.nucleusframework.widget.stringStateKey

/** Definition ids shared with `windows { widgets { widget(…) } }` in the Lab's build script. */
const val LAB_COUNTER_WIDGET: String = "LabCounter"
const val LAB_WEB_WIDGET: String = "LabWeb"

internal val CountKey = intStateKey("count")
internal val NoteKey = stringStateKey("note")

// Set from the widget's customization UI (Customize widget in its menu).
internal val TitleKey = stringStateKey("title")
internal val StepKey = intStateKey("step")
internal val ShowNoteKey = booleanStateKey("showNote")
internal val BackgroundKey = stringStateKey("background")

private val Backgrounds =
    listOf(
        Choice("Plain", WidgetBackground.Default.name),
        Choice("Emphasis", WidgetBackground.Emphasis.name),
        Choice("Accent", WidgetBackground.Accent.name),
    )

/** The Lab's widget: a counter driven from the card and from the probe, customizable. */
class LabCounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            if (isCustomizing()) Settings() else Counter()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Settings() {
        Column(spacing = Spacing.Small) {
            Text("Customize the counter", style = TextStyle(fontWeight = FontWeight.Bold))
            TextField(TitleKey, label = "Title", placeholder = "Nucleus Lab", maxLength = 24)
            NumberField(StepKey, label = "Step", min = 1.0, max = 100.0)
            Switch(ShowNoteKey, title = "Show the last change")
            ChoiceField(BackgroundKey, Backgrounds, label = "Background")
            Button("Done", onClick = actionFinishCustomization())
        }
    }

    @androidx.compose.runtime.Composable
    private fun Counter() {
        val state = currentWidgetState()
        val count = state[CountKey] ?: 0
        val step = state[StepKey] ?: 1
        val background =
            WidgetBackground.entries.firstOrNull { it.name == state[BackgroundKey] } ?: WidgetBackground.Default
        Column(
            modifier = WidgetModifier.background(background),
            spacing = Spacing.Small,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Row(spacing = Spacing.Small, verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(ImageProvider.appResource("widget/logo.png"), "Nucleus", size = ImageSize.Small)
                Text(
                    state[TitleKey]?.takeIf { it.isNotBlank() } ?: "Nucleus Lab",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
            }
            Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
            if (state[ShowNoteKey] != false) {
                Text(
                    "${LocalSize.current} · ${state[NoteKey] ?: "pinned"}",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
            }
            Row(spacing = Spacing.Small) {
                Button("+$step", onClick = {
                    state.update {
                        it[CountKey] = count + step
                        it[NoteKey] = "+$step from the widget"
                    }
                })
                Button("Reset", onClick = {
                    state.update {
                        it[CountKey] = 0
                        it[NoteKey] = "reset from the widget"
                    }
                })
                if (LocalSize.current != WidgetSize.Small) {
                    Button("Open probe", onClick = actionOpenUrl(LabCommands.deepLink(WindowsWidgetsProbe.ID)))
                }
            }
        }
    }
}

/**
 * A web widget: the page replaces the card where the host shows web content (on Windows, the
 * European Economic Area); elsewhere the fallback below is shown. Messages from the page are echoed.
 */
class LabWebWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            Column(spacing = Spacing.Small) {
                WebContent(WEB_URL)
                Text("Nucleus Lab web widget", style = TextStyle(fontWeight = FontWeight.Bold))
                Text(
                    "Web widgets show a page only in the European Economic Area; this card is the fallback.",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
                Button("Open the page", onClick = actionOpenUrl(WEB_URL))
            }
        }
    }

    override suspend fun onMessage(
        id: WidgetId,
        message: String,
    ) {
        sendWidgetMessage(id, "echo: $message")
    }

    private companion object {
        const val WEB_URL = "https://nucleusframework.dev"
    }
}

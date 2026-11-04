package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.LocalSize
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey

/** Definition id shared with `windows { widgets { widget(…) } }` in the Lab's build script. */
const val LAB_COUNTER_WIDGET: String = "LabCounter"

internal val CountKey = intStateKey("count")
internal val NoteKey = stringStateKey("note")

/** The Lab's widget: a counter driven from the card and from the probe. */
class LabCounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val count = state[CountKey] ?: 0
            Column(spacing = Spacing.Small, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text("Nucleus Lab", style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle))
                Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
                Text(
                    "${LocalSize.current} · ${state[NoteKey] ?: "pinned"}",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
                Row(spacing = Spacing.Small) {
                    Button("+1", onClick = {
                        state.update {
                            it[CountKey] = count + 1
                            it[NoteKey] = "+1 from the widget"
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
}

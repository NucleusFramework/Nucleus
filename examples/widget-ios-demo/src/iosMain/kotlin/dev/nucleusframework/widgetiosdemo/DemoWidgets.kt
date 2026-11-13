package dev.nucleusframework.widgetiosdemo

import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Column
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.LocalSize
import dev.nucleusframework.widget.NucleusWidget
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacer
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Switch
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxSize
import dev.nucleusframework.widget.getWidgetState
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.ios.startIosWidgets
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.updateWidgetState
import kotlinx.coroutines.runBlocking

const val COUNTER_WIDGET = "DemoCounter"

private val Count = intStateKey("count")
private val Note = stringStateKey("note")
private val Double = booleanStateKey("double")

/** The demo counter: +1 (or +2 with the switch on) and Reset on the widget, Push in the app. */
class DemoCounterWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val count = state[Count] ?: 0
            val step = if (state[Double] == true) 2 else 1
            val small = LocalSize.current == WidgetSize.Small
            Column(
                modifier = WidgetModifier.fillMaxSize(),
                spacing = Spacing.Small,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) {
                Spacer(WidgetModifier.fillMaxHeight())
                Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
                Text(
                    state[Note] ?: "Count",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                    maxLines = 1,
                )
                if (!small) Switch(Double, title = "Count by 2")
                Spacer(WidgetModifier.fillMaxHeight())
                Row(spacing = Spacing.Small) {
                    if (!small) {
                        Button("Reset", enabled = count != 0, onClick = {
                            state.update {
                                it[Count] = 0
                                it[Note] = "Reset from the widget"
                            }
                        })
                    }
                    Button("+$step", onClick = {
                        state.update {
                            it[Count] = count + step
                            it[Note] = "+$step from the widget"
                        }
                    })
                }
            }
        }
    }
}

/** Called first by the app and by its widget extension. */
fun startDemoWidgets() =
    startIosWidgets {
        widget(COUNTER_WIDGET) { DemoCounterWidget() }
    }

/** The app's Push button: changes the state of every family of the counter, then reloads it. */
fun pushFromApp(family: String) =
    runBlocking {
        val id = WidgetId("$COUNTER_WIDGET/$family")
        updateWidgetState(id) {
            it[Count] = (it[Count] ?: 0) + 10
            it[Note] = "+10 from the app"
        }
        DemoCounterWidget().update(id)
    }

/** What the app shows: the count of the counter at [family]. */
fun countOf(family: String): Int = runBlocking { getWidgetState(WidgetId("$COUNTER_WIDGET/$family"))[Count] ?: 0 }

/** The pinned widgets, as WidgetKit lists them (WidgetCenter through the Swift bridge). */
fun pinnedDescription(): String =
    runBlocking {
        dev.nucleusframework.widget.NucleusWidgets
            .pinnedWidgets()
            .joinToString { "${it.id} (${it.size})" }
            .ifEmpty { "none" }
    }

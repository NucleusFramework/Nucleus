package dev.nucleusframework.widgetiosdemo

import dev.nucleusframework.widget.ActionCallback
import dev.nucleusframework.widget.ActionParameters
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
import dev.nucleusframework.widget.Row
import dev.nucleusframework.widget.Spacer
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.Text
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.TextStyle
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.actionParametersOf
import dev.nucleusframework.widget.actionRunCallback
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxSize
import dev.nucleusframework.widget.fillMaxWidth
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.updateWidgetState
import dev.nucleusframework.widget.weight

const val TASKS_WIDGET = "DemoTasks"
const val DASHBOARD_WIDGET = "DemoDashboard"
const val WEATHER_WIDGET = "DemoWeather"

// -- Tasks: clickable rows with icons, a filter, a counter badge, an empty state -----------------

private val Tasks = stringStateKey("tasks") // "1Buy milk|0Ship it" (1 = done)
private val Filter = stringStateKey("filter")

private val DefaultTasks = "0Ship the widgets|1Write the docs|0Review PR 911|0Test on a device|1Fix the switch"

private fun parse(raw: String?): List<Pair<String, Boolean>> =
    (raw ?: DefaultTasks).split('|').filter { it.length > 1 }.map { it.substring(1) to (it[0] == '1') }

private fun encode(tasks: List<Pair<String, Boolean>>) =
    tasks.joinToString("|") {
        (if (it.second) "1" else "0") +
            it.first
    }

/** Flips the task at [Index]: a callback with a parameter, run on the state of the widget clicked. */
private object ToggleTask : ActionCallback {
    val Index = ActionParameters.Key<Int>("index")

    override suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    ) {
        val index = parameters[Index] ?: return
        updateWidgetState(id) { state ->
            val tasks = parse(state[Tasks]).toMutableList()
            if (index in tasks.indices) tasks[index] = tasks[index].first to !tasks[index].second
            state[Tasks] = encode(tasks)
        }
    }
}

class DemoTasksWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val tasks = parse(state[Tasks])
            val filter = state[Filter] ?: "all"
            val large = LocalSize.current == WidgetSize.Large
            val left = tasks.count { !it.second }
            val shown =
                tasks.withIndex().filter { (_, task) ->
                    when (filter) {
                        "todo" -> !task.second
                        "done" -> task.second
                        else -> true
                    }
                }
            Column(modifier = WidgetModifier.fillMaxSize(), spacing = Spacing.Small) {
                Row(modifier = WidgetModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Text(
                        "Tasks",
                        modifier = WidgetModifier.weight(1),
                        style = TextStyle(fontSize = TextSize.Medium, fontWeight = FontWeight.Bold),
                    )
                    Column(
                        modifier =
                            WidgetModifier.background(
                                if (left ==
                                    0
                                ) {
                                    WidgetBackground.Good
                                } else {
                                    WidgetBackground.Accent
                                },
                            ),
                    ) {
                        Text(
                            if (left == 0) "All done" else "$left left",
                            style = TextStyle(fontSize = TextSize.Small, fontWeight = FontWeight.Bold),
                        )
                    }
                }
                if (large) {
                    ChoiceField(
                        Filter,
                        listOf(Choice("All", "all"), Choice("To do", "todo"), Choice("Done", "done")),
                        defaultValue = "all",
                    )
                }
                if (shown.isEmpty()) {
                    Spacer(WidgetModifier.fillMaxHeight())
                    Text(
                        "Nothing here",
                        modifier = WidgetModifier.fillMaxWidth(),
                        style = TextStyle(color = WidgetColor.Subtle),
                    )
                    Spacer(WidgetModifier.fillMaxHeight())
                } else {
                    for ((index, task) in shown.take(if (large) 8 else 3)) {
                        Row(
                            modifier =
                                WidgetModifier
                                    .fillMaxWidth()
                                    .clickable(
                                        actionRunCallback(
                                            ToggleTask,
                                            actionParametersOf(
                                                ToggleTask.Index to index,
                                            ),
                                        ),
                                    ),
                            spacing = Spacing.Small,
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            Image(
                                ImageProvider.appResource(
                                    if (task.second) "widget/task-done.png" else "widget/task-open.png",
                                ),
                                contentDescription = if (task.second) "Done" else "To do",
                                size = ImageSize.Icon,
                            )
                            Text(
                                task.first,
                                modifier = WidgetModifier.weight(1),
                                style = TextStyle(color = if (task.second) WidgetColor.Subtle else WidgetColor.Default),
                                maxLines = 1,
                            )
                        }
                    }
                    if (shown.size > (if (large) 8 else 3)) {
                        Text(
                            "+ ${shown.size - (if (large) 8 else 3)} more",
                            style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                        )
                    }
                    Spacer(WidgetModifier.fillMaxHeight())
                }
                if (large && tasks.any { it.second }) {
                    Row(
                        modifier =
                            WidgetModifier.clickable {
                                state.update {
                                    it[Tasks] =
                                        encode(tasks.filterNot { t -> t.second })
                                }
                            },
                    ) {
                        Text(
                            "Clear completed",
                            style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Accent),
                        )
                    }
                }
            }
        }
    }
}

// -- Dashboard: weighted tiles on semantic backgrounds, a refresh counter ------------------------

private val Refreshes = intStateKey("refreshes")

class DemoDashboardWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val refreshes = state[Refreshes] ?: 0
            val large = LocalSize.current == WidgetSize.Large
            // Pseudo data that moves on every refresh.
            val builds = 128 + refreshes * 3
            val failing = (refreshes * 7) % 5
            val latency = 42 + (refreshes * 13) % 60
            Column(modifier = WidgetModifier.fillMaxSize(), spacing = Spacing.Small) {
                Row(modifier = WidgetModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Image(
                        ImageProvider.appResource("widget/logo.png"),
                        contentDescription = "Nucleus",
                        size = ImageSize.Icon,
                    )
                    Text(
                        "CI dashboard",
                        modifier = WidgetModifier.weight(1),
                        style = TextStyle(fontWeight = FontWeight.Bold),
                    )
                    Button("Refresh", onClick = { state.update { it[Refreshes] = refreshes + 1 } })
                }
                Row(modifier = WidgetModifier.fillMaxWidth(), spacing = Spacing.Small) {
                    Tile("Builds", "$builds", WidgetBackground.Good, Modifier1)
                    Tile(
                        "Failing",
                        "$failing",
                        if (failing ==
                            0
                        ) {
                            WidgetBackground.Good
                        } else {
                            WidgetBackground.Attention
                        },
                        Modifier1,
                    )
                    Tile(
                        "p95 ms",
                        "$latency",
                        if (latency >
                            80
                        ) {
                            WidgetBackground.Warning
                        } else {
                            WidgetBackground.Emphasis
                        },
                        Modifier1,
                    )
                }
                if (large) {
                    Text("Recent runs", style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle))
                    for (run in 0 until 4) {
                        val ok = (refreshes + run) % 4 != 0
                        Row(modifier = WidgetModifier.fillMaxWidth(), spacing = Spacing.Small) {
                            Text(
                                if (ok) "●" else "●",
                                style = TextStyle(color = if (ok) WidgetColor.Good else WidgetColor.Attention),
                            )
                            Text("preMerge #${builds - run}", modifier = WidgetModifier.weight(1), maxLines = 1)
                            Text("${3 + run}m", style = TextStyle(color = WidgetColor.Subtle))
                        }
                    }
                }
                Spacer(WidgetModifier.fillMaxHeight())
                Row(
                    modifier =
                        WidgetModifier.clickable(
                            actionOpenUrl("https://github.com/NucleusFramework/Nucleus/actions"),
                        ),
                ) {
                    Text(
                        "Refreshed $refreshes times · open in GitHub",
                        style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Accent),
                    )
                }
            }
        }
    }
}

private val Modifier1 = WidgetModifier.weight(1)

@androidx.compose.runtime.Composable
private fun Tile(
    label: String,
    value: String,
    background: WidgetBackground,
    modifier: WidgetModifier,
) {
    Column(
        modifier = modifier.background(background),
        spacing = Spacing.None,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(value, style = TextStyle(fontSize = TextSize.Large, fontWeight = FontWeight.Bold))
        Text(label, style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle), maxLines = 1)
    }
}

// -- Weather: a city choice, a hero value, a multi-column forecast ------------------------------

private val City = stringStateKey("city")

private class Forecast(
    val temp: Int,
    val condition: String,
    val days: List<Pair<String, Int>>,
)

private val Cities =
    mapOf(
        "paris" to Forecast(14, "Cloudy", listOf("Mon" to 15, "Tue" to 12, "Wed" to 17, "Thu" to 18, "Fri" to 13)),
        "jerusalem" to Forecast(22, "Sunny", listOf("Mon" to 24, "Tue" to 25, "Wed" to 23, "Thu" to 21, "Fri" to 22)),
        "oslo" to Forecast(3, "Snow", listOf("Mon" to 1, "Tue" to -2, "Wed" to 0, "Thu" to 4, "Fri" to 2)),
    )

class DemoWeatherWidget : NucleusWidget() {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            val state = currentWidgetState()
            val city = state[City] ?: "paris"
            val forecast = Cities.getValue(city)
            val size = LocalSize.current
            Column(
                modifier = WidgetModifier.fillMaxSize(),
                spacing = Spacing.Small,
                horizontalAlignment =
                    if (size ==
                        WidgetSize.Small
                    ) {
                        Alignment.Horizontal.CenterHorizontally
                    } else {
                        Alignment.Horizontal.Start
                    },
            ) {
                Text(city.replaceFirstChar { it.uppercase() }, style = TextStyle(fontWeight = FontWeight.Bold))
                Row(spacing = Spacing.Medium, verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Text(
                        "${forecast.temp}°",
                        style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Light),
                    )
                    if (size != WidgetSize.Small) {
                        Column(spacing = Spacing.None) {
                            Text(forecast.condition, style = TextStyle(fontSize = TextSize.Medium))
                            Text(
                                "H ${forecast.days.maxOf { it.second }}°  L ${forecast.days.minOf { it.second }}°",
                                style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                            )
                        }
                    }
                }
                if (size == WidgetSize.Small) {
                    Text(forecast.condition, style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle))
                } else {
                    Spacer(WidgetModifier.fillMaxHeight())
                    Row(modifier = WidgetModifier.fillMaxWidth().background(WidgetBackground.Emphasis)) {
                        for ((day, temp) in forecast.days) {
                            Column(
                                modifier = WidgetModifier.weight(1),
                                spacing = Spacing.None,
                                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                            ) {
                                Text(day, style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle))
                                Text(
                                    "$temp°",
                                    style =
                                        TextStyle(
                                            fontWeight = FontWeight.Bold,
                                            color =
                                                when {
                                                    temp >= 24 -> WidgetColor.Warning
                                                    temp <= 0 -> WidgetColor.Accent
                                                    else -> WidgetColor.Default
                                                },
                                        ),
                                )
                            }
                        }
                    }
                }
                if (size == WidgetSize.Large) {
                    ChoiceField(
                        City,
                        listOf(Choice("Paris", "paris"), Choice("Jerusalem", "jerusalem"), Choice("Oslo", "oslo")),
                        label = "City",
                        defaultValue = "paris",
                    )
                }
            }
        }
    }
}

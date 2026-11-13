package dev.nucleusframework.lab.probes.shell.widgets

import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.Button
import dev.nucleusframework.widget.Choice
import dev.nucleusframework.widget.ChoiceField
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
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetModifier
import dev.nucleusframework.widget.WidgetSize
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.actionOpenUrl
import dev.nucleusframework.widget.background
import dev.nucleusframework.widget.booleanStateKey
import dev.nucleusframework.widget.clickable
import dev.nucleusframework.widget.currentWidgetState
import dev.nucleusframework.widget.fillMaxHeight
import dev.nucleusframework.widget.fillMaxSize
import dev.nucleusframework.widget.intStateKey
import dev.nucleusframework.widget.stringStateKey
import dev.nucleusframework.widget.windows.NumberField
import dev.nucleusframework.widget.windows.TextField
import dev.nucleusframework.widget.windows.WebWidgetRequest
import dev.nucleusframework.widget.windows.WebWidgetResponse
import dev.nucleusframework.widget.windows.WindowsWebWidget
import dev.nucleusframework.widget.windows.actionFinishCustomization
import dev.nucleusframework.widget.windows.isCustomizing
import dev.nucleusframework.widget.windows.sendWebWidgetMessage

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
            Switch(ShowNoteKey, title = "Show the last change", defaultValue = true)
            ChoiceField(BackgroundKey, Backgrounds, label = "Background")
            Button("Done", onClick = actionFinishCustomization())
        }
    }

    // The board's header already shows the app and widget names: the card opens on the value,
    // centered in the free height, with its actions along the bottom edge.
    @androidx.compose.runtime.Composable
    private fun Counter() {
        val state = currentWidgetState()
        val count = state[CountKey] ?: 0
        val step = state[StepKey] ?: 1
        val background =
            WidgetBackground.entries.firstOrNull { it.name == state[BackgroundKey] } ?: WidgetBackground.Default
        val isSmall = LocalSize.current == WidgetSize.Small
        Column(
            modifier = WidgetModifier.fillMaxSize().background(background),
            spacing = Spacing.Small,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Spacer(WidgetModifier.fillMaxHeight())
            Text("$count", style = TextStyle(fontSize = TextSize.ExtraLarge, fontWeight = FontWeight.Bold))
            Text(
                state[TitleKey]?.takeIf { it.isNotBlank() } ?: "Count",
                style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                maxLines = 1,
            )
            if (!isSmall && state[ShowNoteKey] != false) {
                state[NoteKey]?.let {
                    Text(it, style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle), maxLines = 1)
                }
            }
            Spacer(WidgetModifier.fillMaxHeight())
            Row(spacing = Spacing.Small) {
                if (!isSmall) {
                    Button("Reset", enabled = count != 0, onClick = {
                        state.update {
                            it[CountKey] = 0
                            it[NoteKey] = "Reset from the widget"
                        }
                    })
                }
                Button("+$step", onClick = {
                    state.update {
                        it[CountKey] = count + step
                        it[NoteKey] = "+$step from the widget"
                    }
                })
            }
            if (!isSmall) {
                Row(modifier = WidgetModifier.clickable(actionOpenUrl(LabCommands.deepLink(widgetsProbeId)))) {
                    Text("Open in the Lab", style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Accent))
                }
            }
        }
    }
}

/**
 * A Windows web widget ([WindowsWebWidget], not part of the common API): the page replaces the
 * card where the board shows web content (documented for the European Economic Area); elsewhere
 * the card below is shown. The page is served by [onWebRequest] (its URL matches the definition's
 * webRequestFilter, so it never reaches the network), posts a message when it loads, and shows the
 * echo [onWebMessage] sends back.
 */
class LabWebWidget :
    NucleusWidget(),
    WindowsWebWidget {
    override suspend fun provideWidget(id: WidgetId) {
        provideContent {
            Column(spacing = Spacing.Small) {
                Text("Nucleus Lab web widget", style = TextStyle(fontWeight = FontWeight.Bold))
                Text(
                    "Web widgets show a page only in the European Economic Area; this card is the fallback.",
                    style = TextStyle(fontSize = TextSize.Small, color = WidgetColor.Subtle),
                )
                Button("Open the Lab", onClick = actionOpenUrl(LabCommands.deepLink(widgetsProbeId)))
            }
        }
    }

    override fun webUrl(
        id: WidgetId,
        state: WidgetState,
    ): String = WEB_URL

    override suspend fun onWebMessage(
        id: WidgetId,
        message: String,
    ) {
        sendWebWidgetMessage(id, "echo from the app: $message")
    }

    override suspend fun onWebRequest(
        id: WidgetId,
        request: WebWidgetRequest,
    ): WebWidgetResponse? =
        if (request.uri.startsWith(WEB_URL)) {
            WebWidgetResponse(PAGE.encodeToByteArray(), headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
        } else {
            null
        }

    private companion object {
        // Matched by the LabWeb webRequestFilter in the Lab's build script: served by onWebRequest.
        const val WEB_URL = "https://lab.nucleusframework.dev/widget.html"

        val PAGE =
            """
            <!doctype html><html><body style="font-family:Segoe UI;color:#ddd;background:#202020;margin:12px">
            <h3>Served by the app</h3><div id="log">Posting a message…</div>
            <script>
              const log = document.getElementById('log');
              window.chrome.webview.addEventListener('message', e => { log.textContent = e.data; });
              window.chrome.webview.postMessage('hello from the page');
            </script></body></html>
            """.trimIndent()
    }
}

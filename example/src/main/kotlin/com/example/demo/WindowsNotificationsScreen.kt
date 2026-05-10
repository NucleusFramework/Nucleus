package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.notification.windows.ActivationType
import dev.nucleusframework.nucleus.notification.windows.AdaptiveProgressBar
import dev.nucleusframework.nucleus.notification.windows.AdaptiveText
import dev.nucleusframework.nucleus.notification.windows.DismissalReason
import dev.nucleusframework.nucleus.notification.windows.ToastActions
import dev.nucleusframework.nucleus.notification.windows.ToastAudio
import dev.nucleusframework.nucleus.notification.windows.ToastAudioSource
import dev.nucleusframework.nucleus.notification.windows.ToastBindingGeneric
import dev.nucleusframework.nucleus.notification.windows.ToastButton
import dev.nucleusframework.nucleus.notification.windows.ToastContent
import dev.nucleusframework.nucleus.notification.windows.ToastGenericAttributionText
import dev.nucleusframework.nucleus.notification.windows.ToastHeader
import dev.nucleusframework.nucleus.notification.windows.ToastNotificationData
import dev.nucleusframework.nucleus.notification.windows.ToastNotificationListener
import dev.nucleusframework.nucleus.notification.windows.ToastScenario
import dev.nucleusframework.nucleus.notification.windows.ToastSelectionBox
import dev.nucleusframework.nucleus.notification.windows.ToastSelectionBoxItem
import dev.nucleusframework.nucleus.notification.windows.ToastTextBox
import dev.nucleusframework.nucleus.notification.windows.ToastVisual
import dev.nucleusframework.nucleus.notification.windows.WindowsNotificationCenter
import dev.nucleusframework.nucleus.notification.windows.toast

private const val EVENT_LOG_MAX = 20
private const val MAX_PROGRESS_VALUE = 100f

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun WindowsNotificationsScreen() {
    val events = remember { mutableStateListOf<String>() }
    var statusText by remember { mutableStateOf("") }

    // Fields
    var title by remember { mutableStateOf("Hello from Nucleus") }
    var body by remember { mutableStateOf("Windows toast notification via JNI") }
    var body2 by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("demo") }
    var group by remember { mutableStateOf("") }
    var attribution by remember { mutableStateOf("") }
    var useButtons by remember { mutableStateOf(false) }
    var useTextInput by remember { mutableStateOf(false) }
    var useSelection by remember { mutableStateOf(false) }
    var useHeader by remember { mutableStateOf(false) }
    var useProgressBar by remember { mutableStateOf(false) }
    var progressValue by remember { mutableStateOf(50f) }
    var scenario by remember { mutableStateOf(ToastScenario.DEFAULT) }
    var silent by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    // Initialize on first composition
    DisposableEffect(Unit) {
        if (WindowsNotificationCenter.isAvailable) {
            initialized = WindowsNotificationCenter.initialize()
            statusText = if (initialized) "Initialized" else "Failed to initialize"

            WindowsNotificationCenter.addListener(
                object : ToastNotificationListener {
                    override fun onActivated(
                        tag: String,
                        group: String,
                        arguments: String,
                        userInputs: Map<String, String>,
                    ) {
                        val msg =
                            buildString {
                                append("Activated: tag=$tag args=$arguments")
                                if (userInputs.isNotEmpty()) append(" inputs=$userInputs")
                            }
                        events.add(0, msg)
                        if (events.size > EVENT_LOG_MAX) events.removeLast()
                    }

                    override fun onDismissed(
                        tag: String,
                        group: String,
                        reason: DismissalReason,
                    ) {
                        events.add(0, "Dismissed: tag=$tag reason=$reason")
                        if (events.size > EVENT_LOG_MAX) events.removeLast()
                    }

                    override fun onFailed(
                        tag: String,
                        group: String,
                        errorCode: Int,
                    ) {
                        events.add(0, "Failed: tag=$tag error=0x${errorCode.toString(16)}")
                        if (events.size > EVENT_LOG_MAX) events.removeLast()
                    }
                },
            )
        } else {
            statusText = "Native library not available"
        }

        onDispose {
            WindowsNotificationCenter.uninitialize()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Windows Toast Notifications", style = MaterialTheme.typography.headlineSmall)
            Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()

            // -- Content fields --
            SectionTitle("Content")
            OutlinedTextField(
                title,
                { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                body,
                { body = it },
                label = { Text("Body") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                body2,
                { body2 = it },
                label = { Text("Body line 2") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                attribution,
                { attribution = it },
                label = { Text("Attribution") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag, { tag = it }, label = { Text("Tag") }, modifier = Modifier.weight(1f))
                OutlinedTextField(group, { group = it }, label = { Text("Group") }, modifier = Modifier.weight(1f))
            }

            HorizontalDivider()

            // -- Options --
            SectionTitle("Options")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledCheckbox("Buttons", useButtons) { useButtons = it }
                LabeledCheckbox("Text input", useTextInput) { useTextInput = it }
                LabeledCheckbox("Selection box", useSelection) { useSelection = it }
                LabeledCheckbox("Header", useHeader) { useHeader = it }
                LabeledCheckbox("Progress bar", useProgressBar) { useProgressBar = it }
                LabeledCheckbox("Silent", silent) { silent = it }
            }

            if (useProgressBar) {
                Text("Progress: ${progressValue.toInt()}%")
                Slider(
                    value = progressValue,
                    onValueChange = { progressValue = it },
                    valueRange = 0f..MAX_PROGRESS_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            // -- Actions --
            SectionTitle("Send")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        sendToast(
                            title,
                            body,
                            body2,
                            tag,
                            group,
                            attribution,
                            useButtons,
                            useTextInput,
                            useSelection,
                            useHeader,
                            useProgressBar,
                            progressValue,
                            scenario,
                            silent,
                            events,
                        )
                    },
                    enabled = initialized,
                ) {
                    Text("Show Toast")
                }

                Button(
                    onClick = { WindowsNotificationCenter.showSimple(title, body, body2, tag, group) },
                    enabled = initialized,
                ) {
                    Text("Simple Toast")
                }

                Button(
                    onClick = { sendDslToast(events) },
                    enabled = initialized,
                ) {
                    Text("DSL Toast")
                }

                if (useProgressBar) {
                    Button(
                        onClick = {
                            val pv = progressValue / MAX_PROGRESS_VALUE
                            WindowsNotificationCenter.update(
                                tag = tag,
                                group = group,
                                data =
                                    ToastNotificationData(
                                        sequenceNumber = 1,
                                        values =
                                            mapOf(
                                                "progressTitle" to "Downloading",
                                                "progressValue" to pv.toString(),
                                                "progressStringOverride" to "${progressValue.toInt()}%",
                                                "progressStatus" to if (pv >= 1f) "Complete!" else "In progress...",
                                            ),
                                    ),
                            ) { err ->
                                if (err != null) {
                                    events.add(0, "Update error: $err")
                                } else {
                                    events.add(0, "Progress updated to ${progressValue.toInt()}%")
                                }
                            }
                        },
                        enabled = initialized,
                    ) {
                        Text("Update Progress")
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { WindowsNotificationCenter.remove(tag, group) },
                    enabled = initialized,
                ) {
                    Text("Remove")
                }
                OutlinedButton(
                    onClick = { WindowsNotificationCenter.clearAll() },
                    enabled = initialized,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Clear All")
                }
                OutlinedButton(
                    onClick = {
                        WindowsNotificationCenter.getHistory { history, err ->
                            if (err != null) {
                                events.add(0, "History error: $err")
                            } else {
                                events.add(0, "History: ${history.size} items")
                                history.forEach { events.add(0, "  tag=${it.tag} group=${it.group}") }
                            }
                        }
                    },
                    enabled = initialized,
                ) {
                    Text("Get History")
                }
            }

            HorizontalDivider()

            // -- Event log --
            SectionTitle("Event Log")
            Card(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (events.isEmpty()) {
                        Text("No events yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.forEach { event ->
                            Text(event, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Suppress("LongParameterList")
private fun sendToast(
    title: String,
    body: String,
    body2: String,
    tag: String,
    group: String,
    attribution: String,
    useButtons: Boolean,
    useTextInput: Boolean,
    useSelection: Boolean,
    useHeader: Boolean,
    useProgressBar: Boolean,
    progressValue: Float,
    scenario: ToastScenario,
    silent: Boolean,
    events: MutableList<String>,
) {
    val children = mutableListOf<dev.nucleusframework.nucleus.notification.windows.ToastVisualChild>()
    children.add(AdaptiveText(title))
    if (body.isNotEmpty()) children.add(AdaptiveText(body))
    if (body2.isNotEmpty()) children.add(AdaptiveText(body2))
    if (useProgressBar) {
        children.add(
            AdaptiveProgressBar(
                title = "{progressTitle}",
                valueBind = "progressValue",
                valueStringOverride = "{progressStringOverride}",
                status = "{progressStatus}",
            ),
        )
    }

    val inputs = mutableListOf<dev.nucleusframework.nucleus.notification.windows.ToastInput>()
    if (useTextInput) {
        inputs.add(ToastTextBox(id = "reply", title = "Reply", placeholderContent = "Type here..."))
    }
    if (useSelection) {
        inputs.add(
            ToastSelectionBox(
                id = "snooze",
                title = "Snooze for",
                defaultSelectionBoxItemId = "15",
                items =
                    listOf(
                        ToastSelectionBoxItem("5", "5 minutes"),
                        ToastSelectionBoxItem("15", "15 minutes"),
                        ToastSelectionBoxItem("60", "1 hour"),
                    ),
            ),
        )
    }

    val buttons = mutableListOf<ToastButton>()
    if (useButtons) {
        buttons.add(ToastButton(content = "Accept", arguments = "action=accept"))
        buttons.add(
            ToastButton(
                content = "Dismiss",
                arguments = "action=dismiss",
                activationType = ActivationType.BACKGROUND,
            ),
        )
    }
    if (useTextInput) {
        buttons.add(ToastButton(content = "Send", arguments = "action=reply", inputId = "reply"))
    }

    val actions =
        if (inputs.isNotEmpty() || buttons.isNotEmpty()) {
            ToastActions(inputs = inputs, buttons = buttons)
        } else {
            null
        }

    val content =
        ToastContent(
            visual =
                ToastVisual(
                    binding =
                        ToastBindingGeneric(
                            children = children,
                            attribution =
                                if (attribution.isNotEmpty()) {
                                    ToastGenericAttributionText(attribution)
                                } else {
                                    null
                                },
                        ),
                ),
            actions = actions,
            audio = if (silent) ToastAudio(silent = true) else null,
            header = if (useHeader) ToastHeader("demo-header", "Demo Group", "action=header") else null,
            scenario = scenario,
        )

    val initialData =
        if (useProgressBar) {
            val pv = (progressValue / MAX_PROGRESS_VALUE)
            ToastNotificationData(
                sequenceNumber = 0,
                values =
                    mapOf(
                        "progressTitle" to "Downloading",
                        "progressValue" to pv.toString(),
                        "progressStringOverride" to "${progressValue.toInt()}%",
                        "progressStatus" to "In progress...",
                    ),
            )
        } else {
            null
        }

    WindowsNotificationCenter.show(content, tag = tag, group = group, initialData = initialData) { err ->
        if (err != null) {
            events.add(0, "Show error: $err")
        } else {
            events.add(0, "Toast shown: tag=$tag")
        }
    }
}

private fun sendDslToast(events: MutableList<String>) {
    val content =
        toast {
            launch = "action=dslDemo"

            visual {
                text("DSL Toast")
                text("Built with the Kotlin DSL builder")
                attribution("via Nucleus")

                progressBar(
                    status = "Processing...",
                    title = "File upload",
                    value = 0.75,
                    valueStringOverride = "3/4 files",
                )
            }

            actions {
                textBox("comment", title = "Comment", placeholder = "Add a comment...")
                button("Send", arguments = "action=send", inputId = "comment")
                button("Later", arguments = "action=later", activationType = ActivationType.BACKGROUND)
                contextMenuItem("Open settings", arguments = "action=settings")
            }

            audio(ToastAudioSource.REMINDER)
        }

    WindowsNotificationCenter.show(content, tag = "dsl-demo") { err ->
        if (err != null) {
            events.add(0, "DSL toast error: $err")
        } else {
            events.add(0, "DSL toast shown")
        }
    }
}

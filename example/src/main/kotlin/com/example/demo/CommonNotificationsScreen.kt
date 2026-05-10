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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.notification.common.NotificationHandle
import dev.nucleusframework.nucleus.notification.common.NotificationManager
import dev.nucleusframework.nucleus.notification.common.NotificationResult
import dev.nucleusframework.nucleus.notification.common.notification

private const val EVENT_LOG_MAX = 20

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun CommonNotificationsScreen() {
    val events = remember { mutableStateListOf<String>() }
    var lastHandle by remember { mutableStateOf<NotificationHandle?>(null) }

    // Custom notification fields
    var title by remember { mutableStateOf("Hello from Nucleus") }
    var message by remember { mutableStateOf("This is a cross-platform notification.") }
    var largeImage by remember { mutableStateOf("") }
    var smallIcon by remember { mutableStateOf("") }

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeLast()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -- Status --
            SectionCard("Status") {
                val available = NotificationManager.isAvailable()
                Text(
                    text = if (available) "Notifications available on this platform" else "Notifications NOT available",
                    color =
                        if (available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }

            // -- Quick Send --
            SectionCard("Quick Send") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val result =
                            notification(
                                title = "Simple Notification",
                                message = "Hello from the common notification API!",
                                onActivated = { log("Simple: activated") },
                                onDismissed = { reason -> log("Simple: dismissed ($reason)") },
                            ).send()
                        logResult("Simple", result, events = events, onHandle = { lastHandle = it })
                    }) {
                        Text("Simple")
                    }

                    Button(onClick = {
                        val result =
                            notification(
                                title = "With Buttons",
                                message = "Choose an action below",
                                onActivated = { log("Buttons: body clicked") },
                                onDismissed = { reason -> log("Buttons: dismissed ($reason)") },
                                onFailed = { log("Buttons: FAILED") },
                            ) {
                                button("Accept") { log("Buttons: Accept clicked") }
                                button("Decline") { log("Buttons: Decline clicked") }
                            }.send()
                        logResult("Buttons", result, events = events, onHandle = { lastHandle = it })
                    }) {
                        Text("With Buttons")
                    }

                    Button(onClick = {
                        val result =
                            notification(
                                title = "Multi-Action",
                                message = "A notification with many buttons",
                                onActivated = { log("Multi: body clicked") },
                                onDismissed = { reason -> log("Multi: dismissed ($reason)") },
                            ) {
                                button("Reply") { log("Multi: Reply") }
                                button("Archive") { log("Multi: Archive") }
                                button("Snooze") { log("Multi: Snooze") }
                                button("Mark Read") { log("Multi: Mark Read") }
                                button("Delete") { log("Multi: Delete") }
                            }.send()
                        logResult("Multi", result, events = events, onHandle = { lastHandle = it })
                    }) {
                        Text("5 Buttons (max)")
                    }

                    OutlinedButton(
                        onClick = {
                            lastHandle?.dismiss()
                            log("Dismissed last notification")
                        },
                        enabled = lastHandle != null,
                    ) {
                        Text("Dismiss Last")
                    }
                }
            }

            // -- Custom Notification --
            SectionCard("Custom Notification") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = largeImage,
                    onValueChange = { largeImage = it },
                    label = { Text("Large Image (URI or path)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = smallIcon,
                    onValueChange = { smallIcon = it },
                    label = { Text("Small Icon (URI or path)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val result =
                            notification(
                                title = title,
                                message = message,
                                largeImage = largeImage.ifBlank { null },
                                smallIcon = smallIcon.ifBlank { null },
                                onActivated = { log("Custom: activated") },
                                onDismissed = { reason -> log("Custom: dismissed ($reason)") },
                                onFailed = { log("Custom: FAILED") },
                            ) {
                                button("Action 1") { log("Custom: Action 1") }
                                button("Action 2") { log("Custom: Action 2") }
                            }.send()
                        logResult("Custom", result, events = events, onHandle = { lastHandle = it })
                    }) {
                        Text("Send Custom")
                    }

                    Button(onClick = {
                        val result =
                            notification(
                                title = title,
                                message = message,
                                largeImage = largeImage.ifBlank { null },
                                smallIcon = smallIcon.ifBlank { null },
                                onActivated = { log("Custom (no buttons): activated") },
                                onDismissed = { reason -> log("Custom (no buttons): dismissed ($reason)") },
                            ).send()
                        logResult("Custom (no buttons)", result, events = events, onHandle = { lastHandle = it })
                    }) {
                        Text("Send Without Buttons")
                    }
                }
            }

            // -- Event Log --
            SectionCard("Event Log") {
                if (events.isEmpty()) {
                    Text("No events yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedButton(
                        onClick = { events.clear() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Clear Log")
                    }
                    Spacer(Modifier.height(8.dp))
                    events.forEachIndexed { index, event ->
                        Text(
                            text = event,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (index < events.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun logResult(
    label: String,
    result: NotificationResult,
    events: MutableList<String>,
    onHandle: (NotificationHandle?) -> Unit,
) {
    when (result) {
        is NotificationResult.Success -> {
            events.add(0, "$label: sent (${result.handle})")
            onHandle(result.handle)
        }
        is NotificationResult.Failure -> {
            events.add(0, "$label: FAILED - ${result.reason}")
            onHandle(null)
        }
    }
    if (events.size > EVENT_LOG_MAX) events.removeLast()
}

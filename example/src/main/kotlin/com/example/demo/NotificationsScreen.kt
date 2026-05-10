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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.notification.ActionOption
import dev.nucleusframework.nucleus.notification.AuthorizationOption
import dev.nucleusframework.nucleus.notification.CategoryOption
import dev.nucleusframework.nucleus.notification.DateComponents
import dev.nucleusframework.nucleus.notification.DeliveredNotification
import dev.nucleusframework.nucleus.notification.InterruptionLevel
import dev.nucleusframework.nucleus.notification.NotificationAction
import dev.nucleusframework.nucleus.notification.NotificationAttachment
import dev.nucleusframework.nucleus.notification.NotificationCategory
import dev.nucleusframework.nucleus.notification.NotificationCenter
import dev.nucleusframework.nucleus.notification.NotificationCenterDelegate
import dev.nucleusframework.nucleus.notification.NotificationContent
import dev.nucleusframework.nucleus.notification.NotificationRequest
import dev.nucleusframework.nucleus.notification.NotificationResponse
import dev.nucleusframework.nucleus.notification.NotificationSettings
import dev.nucleusframework.nucleus.notification.NotificationSound
import dev.nucleusframework.nucleus.notification.NotificationTrigger
import dev.nucleusframework.nucleus.notification.PendingNotificationInfo
import dev.nucleusframework.nucleus.notification.PresentationOption
import dev.nucleusframework.nucleus.notification.TextInputNotificationAction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val IMMEDIATE_DELAY = 1.0
private const val DEFAULT_DELAY = 5.0
private const val MAX_DELAY = 60f
private const val MAX_BADGE = 99f
private const val EVENT_LOG_MAX = 15
private const val SAMPLE_IMAGE_SIZE = 200

private const val CATEGORY_MESSAGE = "message"
private const val CATEGORY_REMINDER = "reminder"
private const val ACTION_REPLY = "reply"
private const val ACTION_MARK_READ = "mark-read"
private const val ACTION_DELETE = "delete"
private const val ACTION_SNOOZE = "snooze"
private const val ACTION_DONE = "done"

/**
 * Creates a sample PNG in a temp file for testing image attachments.
 * Returns the absolute path.
 */
private fun createSampleImage(): String {
    val img = BufferedImage(SAMPLE_IMAGE_SIZE, SAMPLE_IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0x44, 0x88, 0xFF)
    g.fillOval(0, 0, SAMPLE_IMAGE_SIZE, SAMPLE_IMAGE_SIZE)
    g.color = Color.WHITE
    g.font = g.font.deriveFont(48f)
    val fm = g.fontMetrics
    val text = "N"
    g.drawString(text, (SAMPLE_IMAGE_SIZE - fm.stringWidth(text)) / 2, (SAMPLE_IMAGE_SIZE + fm.ascent - fm.descent) / 2)
    g.dispose()
    val file = File.createTempFile("nucleus-notif-", ".png")
    file.deleteOnExit()
    ImageIO.write(img, "png", file)
    return file.absolutePath
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun NotificationsScreen() {
    // -- State --
    var setupDone by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf<NotificationSettings?>(null) }
    val events = remember { mutableStateListOf<String>() }
    var notifCounter by remember { mutableStateOf(0) }

    // Notification customization
    var title by remember { mutableStateOf("New message from Alice") }
    var subtitle by remember { mutableStateOf("Project Nucleus") }
    var body by remember { mutableStateOf("Hey! Have you seen the latest build?") }
    var useSound by remember { mutableStateOf(true) }
    var useBadge by remember { mutableStateOf(false) }
    var badgeCount by remember { mutableStateOf(1f) }
    var delay by remember { mutableStateOf(DEFAULT_DELAY.toFloat()) }
    var useCalendar by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(InterruptionLevel.ACTIVE) }

    // Pending / Delivered
    val pending = remember { mutableStateListOf<PendingNotificationInfo>() }
    val delivered = remember { mutableStateListOf<DeliveredNotification>() }

    // Sample image for attachments
    val sampleImagePath = remember { createSampleImage() }

    fun log(msg: String) {
        events.add(0, msg)
        statusText = msg
    }

    fun nextId(): String {
        notifCounter++
        return "notif-$notifCounter"
    }

    fun makeTrigger(): NotificationTrigger =
        if (useCalendar) {
            val now = java.util.Calendar.getInstance()
            now.add(java.util.Calendar.MINUTE, 1)
            NotificationTrigger.Calendar(
                dateComponents =
                    DateComponents(
                        hour = now.get(java.util.Calendar.HOUR_OF_DAY),
                        minute = now.get(java.util.Calendar.MINUTE),
                        second = 0,
                    ),
            )
        } else {
            NotificationTrigger.TimeInterval(
                interval = if (delay > 0) delay.toDouble() else IMMEDIATE_DELAY,
            )
        }

    // -- Setup --
    fun setup() {
        if (!NotificationCenter.isAvailable) {
            log("Notifications unavailable - use ./gradlew runDistributable")
            return
        }
        NotificationCenter.requestAuthorization(
            setOf(
                AuthorizationOption.ALERT,
                AuthorizationOption.SOUND,
                AuthorizationOption.BADGE,
            ),
        ) { granted, error ->
            if (!granted) {
                log("Authorization denied: ${error ?: "user declined"}")
                return@requestAuthorization
            }

            // Register two categories
            NotificationCenter.setNotificationCategories(
                setOf(
                    NotificationCategory(
                        identifier = CATEGORY_MESSAGE,
                        actions =
                            listOf(
                                TextInputNotificationAction(
                                    identifier = ACTION_REPLY,
                                    title = "Reply",
                                    options = setOf(ActionOption.FOREGROUND),
                                    textInputButtonTitle = "Send",
                                    textInputPlaceholder = "Type your reply...",
                                ),
                                NotificationAction(identifier = ACTION_MARK_READ, title = "Mark as Read"),
                                NotificationAction(
                                    identifier = ACTION_DELETE,
                                    title = "Delete",
                                    options = setOf(ActionOption.DESTRUCTIVE),
                                ),
                            ),
                        options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                    ),
                    NotificationCategory(
                        identifier = CATEGORY_REMINDER,
                        actions =
                            listOf(
                                NotificationAction(identifier = ACTION_SNOOZE, title = "Snooze 5 min"),
                                NotificationAction(
                                    identifier = ACTION_DONE,
                                    title = "Done",
                                    options = setOf(ActionOption.FOREGROUND),
                                ),
                            ),
                    ),
                ),
            )

            // Delegate
            NotificationCenter.setDelegate(
                object : NotificationCenterDelegate {
                    override fun willPresent(notification: DeliveredNotification) =
                        setOf(PresentationOption.BANNER, PresentationOption.SOUND, PresentationOption.LIST)

                    override fun didReceive(response: NotificationResponse) {
                        val desc =
                            when (response.actionIdentifier) {
                                ACTION_REPLY -> "Reply: \"${response.userText}\""
                                ACTION_MARK_READ -> "Marked as read"
                                ACTION_DELETE -> "Deleted"
                                ACTION_SNOOZE -> "Snoozed"
                                ACTION_DONE -> "Done"
                                NotificationAction.DEFAULT_ACTION_IDENTIFIER -> "Tapped notification"
                                NotificationAction.DISMISS_ACTION_IDENTIFIER -> "Dismissed"
                                else -> "Action: ${response.actionIdentifier}"
                            }
                        log("[${response.notification.identifier}] $desc")
                    }

                    override fun openSettings(notification: DeliveredNotification?) {
                        log("Open settings requested")
                    }
                },
            )

            setupDone = true
            log("Ready! 2 categories registered (message: 3 actions, reminder: 2 actions)")
        }
    }

    // -- UI --
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Notifications", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = if (NotificationCenter.isAvailable) "Native library loaded" else "Not available",
                color =
                    if (NotificationCenter.isAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                style = MaterialTheme.typography.bodyMedium,
            )

            // ================================================================
            // STEP 1: Setup
            // ================================================================
            SectionTitle("1. Setup")
            if (!setupDone) {
                Text(
                    "Requests authorization, registers 2 categories (message + reminder), and installs delegate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Button(onClick = { setup() }) { Text("Initialize Notifications") }
            } else {
                Text(
                    "Authorized + delegate active + 2 categories (message, reminder)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        NotificationCenter.getNotificationSettings { s ->
                            settings = s
                            log("Settings fetched")
                        }
                    }) { Text("Inspect Settings") }

                    OutlinedButton(onClick = {
                        NotificationCenter.setDelegate(null)
                        setupDone = false
                        settings = null
                        log("Reset")
                    }) { Text("Reset") }
                }
            }

            settings?.let { s -> SettingsCard(s) }

            HorizontalDivider()

            // ================================================================
            // STEP 2: Quick send (pre-built scenarios)
            // ================================================================
            SectionTitle("2. Quick Send")

            Text("Message actions (Reply, Mark Read, Delete):", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Alice",
                                        subtitle = "Project Nucleus",
                                        body = "Hey! Have you seen the latest build?",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_MESSAGE,
                                        threadIdentifier = "conversation-alice",
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - message with actions") }
                    },
                    enabled = setupDone,
                ) { Text("Message") }

                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Bob",
                                        subtitle = "Project Nucleus",
                                        body = "Looks great! Merging now.",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_MESSAGE,
                                        threadIdentifier = "conversation-bob",
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - grouped in bob thread") }
                    },
                    enabled = setupDone,
                ) { Text("Grouped (Bob)") }
            }

            Spacer(Modifier.height(4.dp))
            Text("Reminder actions (Snooze, Done):", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Reminder",
                                        body = "Team standup in 5 minutes",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_REMINDER,
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - reminder") }
                    },
                    enabled = setupDone,
                ) { Text("Reminder") }
            }

            Spacer(Modifier.height(4.dp))
            Text("Image & media attachments:", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Photo from Alice",
                                        body = "Check out this screenshot!",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_MESSAGE,
                                        threadIdentifier = "conversation-alice",
                                        attachments =
                                            listOf(
                                                NotificationAttachment(
                                                    identifier = "image-$id",
                                                    url = sampleImagePath,
                                                ),
                                            ),
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - with image attachment") }
                    },
                    enabled = setupDone,
                ) { Text("With Image") }
            }

            Spacer(Modifier.height(4.dp))
            Text("Interruption levels & special sounds:", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Background sync complete",
                                        body = "12 items updated",
                                        interruptionLevel = InterruptionLevel.PASSIVE,
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - passive (no sound, no wake)") }
                    },
                    enabled = setupDone,
                ) { Text("Passive") }

                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Deployment finishing",
                                        body = "Production deploy requires approval",
                                        sound = NotificationSound.Default,
                                        interruptionLevel = InterruptionLevel.TIME_SENSITIVE,
                                        categoryIdentifier = CATEGORY_REMINDER,
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - time sensitive") }
                    },
                    enabled = setupDone,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) { Text("Time Sensitive") }

                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Critical Alert!",
                                        body = "Server is down - immediate action required",
                                        sound = NotificationSound.DefaultCritical,
                                        interruptionLevel = InterruptionLevel.CRITICAL,
                                        categoryIdentifier = CATEGORY_REMINDER,
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - critical") }
                    },
                    enabled = setupDone,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Critical") }
            }

            Spacer(Modifier.height(4.dp))
            Text("Scheduling & replacement:", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val now = java.util.Calendar.getInstance()
                        now.add(java.util.Calendar.MINUTE, 1)
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "Calendar reminder",
                                        body =
                                            "Fires at ${now.get(java.util.Calendar.HOUR_OF_DAY)}:" +
                                                "${"%02d".format(now.get(java.util.Calendar.MINUTE))}",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_REMINDER,
                                    ),
                                trigger =
                                    NotificationTrigger.Calendar(
                                        dateComponents =
                                            DateComponents(
                                                hour = now.get(java.util.Calendar.HOUR_OF_DAY),
                                                minute = now.get(java.util.Calendar.MINUTE),
                                                second = 0,
                                            ),
                                    ),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - calendar trigger (next minute)") }
                    },
                    enabled = setupDone,
                ) { Text("Calendar Trigger") }

                Button(
                    onClick = {
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = "replaceable",
                                content =
                                    NotificationContent(
                                        title = "Download progress",
                                        body = "Updated at ${java.time.LocalTime.now().withNano(0)}",
                                        sound = NotificationSound.Default,
                                        categoryIdentifier = CATEGORY_REMINDER,
                                    ),
                                trigger = NotificationTrigger.TimeInterval(interval = IMMEDIATE_DELAY),
                            ),
                        ) { error -> log(error ?: "Sent 'replaceable' - same ID replaces previous") }
                    },
                    enabled = setupDone,
                ) { Text("Replace (same ID)") }

                Button(
                    onClick = {
                        val id = nextId()
                        NotificationCenter.add(
                            NotificationRequest(
                                identifier = id,
                                content =
                                    NotificationContent(
                                        title = "UserInfo demo",
                                        body = "Check event log for data",
                                        sound = NotificationSound.Default,
                                        userInfo =
                                            mapOf(
                                                "userId" to "42",
                                                "action" to "purchase",
                                                "amount" to "19.99",
                                                "currency" to "USD",
                                            ),
                                    ),
                                trigger = makeTrigger(),
                            ),
                        ) { error -> log(error ?: "Sent '$id' - with userInfo {userId=42, action=purchase, ...}") }
                    },
                    enabled = setupDone,
                ) { Text("With UserInfo") }
            }

            HorizontalDivider()

            // ================================================================
            // STEP 3: Custom notification
            // ================================================================
            SectionTitle("3. Custom Notification")

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            OutlinedTextField(
                value = subtitle,
                onValueChange = { subtitle = it },
                label = { Text("Subtitle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Body") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.7f),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useSound, onCheckedChange = { useSound = it })
                    Text("Sound")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useBadge, onCheckedChange = { useBadge = it })
                    Text("Badge")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCalendar, onCheckedChange = { useCalendar = it })
                    Text("Calendar (next minute)")
                }
            }

            if (useBadge) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Badge: ${badgeCount.toInt()}")
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = badgeCount,
                        onValueChange = { badgeCount = it },
                        valueRange = 0f..MAX_BADGE,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }

            if (!useCalendar) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delay: ${delay.toInt()}s")
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = delay,
                        onValueChange = { delay = it },
                        valueRange = 0f..MAX_DELAY,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }

            Text("Interruption:", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InterruptionLevel.entries.forEach { l ->
                    OutlinedButton(
                        onClick = { level = l },
                        colors =
                            if (l == level) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                    ) { Text(l.name) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val id = nextId()
                    NotificationCenter.add(
                        NotificationRequest(
                            identifier = id,
                            content =
                                NotificationContent(
                                    title = title,
                                    subtitle = subtitle,
                                    body = body,
                                    sound = if (useSound) NotificationSound.Default else null,
                                    badge = if (useBadge) badgeCount.toInt() else null,
                                    categoryIdentifier = CATEGORY_MESSAGE,
                                    interruptionLevel = level,
                                    threadIdentifier = "custom",
                                    userInfo = mapOf("counter" to notifCounter.toString()),
                                ),
                            trigger = makeTrigger(),
                        ),
                    ) { error ->
                        if (error != null) log("Error: $error") else log("Sent '$id'")
                    }
                },
                enabled = setupDone,
            ) { Text("Send Custom Notification") }

            HorizontalDivider()

            // ================================================================
            // STEP 4: Manage
            // ================================================================
            SectionTitle("4. Manage")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    NotificationCenter.getPendingNotifications { list ->
                        pending.clear()
                        pending.addAll(list)
                        log("${list.size} pending")
                    }
                }) { Text("Get Pending") }

                OutlinedButton(onClick = {
                    NotificationCenter.getDeliveredNotifications { list ->
                        delivered.clear()
                        delivered.addAll(list)
                        log("${list.size} delivered")
                    }
                }) { Text("Get Delivered") }

                OutlinedButton(onClick = {
                    NotificationCenter.removeAllPendingNotifications()
                    pending.clear()
                    log("Pending cleared")
                }) { Text("Clear Pending") }

                OutlinedButton(onClick = {
                    NotificationCenter.removeAllDeliveredNotifications()
                    delivered.clear()
                    log("Delivered cleared")
                }) { Text("Clear Delivered") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    NotificationCenter.setBadgeCount(badgeCount.toInt()) { err ->
                        log(err?.let { "Badge error: $it" } ?: "Badge set to ${badgeCount.toInt()}")
                    }
                }) { Text("Set Badge (${badgeCount.toInt()})") }

                OutlinedButton(onClick = {
                    NotificationCenter.setBadgeCount(0) { err ->
                        log(err?.let { "Badge error: $it" } ?: "Badge cleared")
                    }
                }) { Text("Clear Badge") }
            }

            if (pending.isNotEmpty()) {
                NotificationListCard("Pending (${pending.size})") {
                    pending.forEach { p ->
                        Text("${p.identifier}: ${p.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (delivered.isNotEmpty()) {
                NotificationListCard("Delivered (${delivered.size})") {
                    delivered.forEach { d ->
                        Text(
                            "${d.identifier}: ${d.title} (${java.time.Instant.ofEpochMilli(d.date)})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            HorizontalDivider()

            // ================================================================
            // Event log
            // ================================================================
            SectionTitle("Event Log")
            if (events.isEmpty()) {
                Text(
                    "No events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                OutlinedButton(onClick = { events.clear() }) { Text("Clear") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        events.take(EVENT_LOG_MAX).forEach { event ->
                            Text(event, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        if (events.size > EVENT_LOG_MAX) {
                            Text(
                                "... +${events.size - EVENT_LOG_MAX} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingsCard(s: NotificationSettings) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Notification Settings", style = MaterialTheme.typography.titleSmall)
            Text("Authorization: ${s.authorizationStatus}")
            Text("Alert: ${s.alertSetting}  |  Sound: ${s.soundSetting}  |  Badge: ${s.badgeSetting}")
            Text("NotifCenter: ${s.notificationCenterSetting}  |  LockScreen: ${s.lockScreenSetting}")
            Text("AlertStyle: ${s.alertStyle}  |  Previews: ${s.showPreviewsSetting}")
            Text("CriticalAlert: ${s.criticalAlertSetting}  |  TimeSensitive: ${s.timeSensitiveSetting}")
        }
    }
}

@Composable
private fun NotificationListCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

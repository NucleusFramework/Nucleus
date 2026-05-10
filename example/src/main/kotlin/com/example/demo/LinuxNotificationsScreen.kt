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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.nucleus.notification.linux.CloseReason
import dev.nucleusframework.nucleus.notification.linux.LinuxNotificationCenter
import dev.nucleusframework.nucleus.notification.linux.LinuxNotificationListener
import dev.nucleusframework.nucleus.notification.linux.Notification
import dev.nucleusframework.nucleus.notification.linux.NotificationAction
import dev.nucleusframework.nucleus.notification.linux.NotificationHints
import dev.nucleusframework.nucleus.notification.linux.NotificationSound
import dev.nucleusframework.nucleus.notification.linux.ServerInformation
import dev.nucleusframework.nucleus.notification.linux.Urgency
import java.awt.Color
import java.awt.image.BufferedImage

private const val EVENT_LOG_MAX = 20
private const val SAMPLE_IMAGE_SIZE = 64
private const val MAX_TIMEOUT = 30f

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun LinuxNotificationsScreen() {
    val events = remember { mutableStateListOf<String>() }
    var statusText by remember { mutableStateOf("") }
    var serverInfo by remember { mutableStateOf<ServerInformation?>(null) }
    var capabilities by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastNotifId by remember { mutableStateOf(0) }

    // Custom notification fields
    var appName by remember { mutableStateOf("Nucleus Demo") }
    var summary by remember { mutableStateOf("Hello from Nucleus") }
    var body by remember { mutableStateOf("This is a <b>desktop notification</b> sent via JNI.") }
    var selectedIcon by remember { mutableStateOf<FreedesktopIcon>(FreedesktopIcon.Status.DIALOG_INFORMATION) }
    var urgency by remember { mutableStateOf(Urgency.NORMAL) }
    var useActions by remember { mutableStateOf(true) }
    var useSound by remember { mutableStateOf(false) }
    var selectedSound by remember {
        mutableStateOf<NotificationSound>(NotificationSound.Notification.MESSAGE_NEW_INSTANT)
    }
    var category by remember { mutableStateOf("") }
    var expireTimeout by remember { mutableStateOf(0f) }
    var replacesPrevious by remember { mutableStateOf(false) }

    // All icon and sound options from the freedesktop specs
    val iconOptions: List<FreedesktopIcon> =
        remember {
            buildList {
                addAll(FreedesktopIcon.Status.entries)
                addAll(FreedesktopIcon.Emblem.entries)
                addAll(FreedesktopIcon.Device.entries)
                addAll(FreedesktopIcon.Emote.entries)
                addAll(FreedesktopIcon.Action.entries)
                addAll(FreedesktopIcon.Application.entries)
                addAll(FreedesktopIcon.Category.entries)
                addAll(FreedesktopIcon.MimeType.entries)
                addAll(FreedesktopIcon.Place.entries)
                addAll(FreedesktopIcon.Animation.entries)
            }
        }

    val soundOptions: List<NotificationSound> =
        remember {
            buildList {
                addAll(NotificationSound.Notification.entries)
                addAll(NotificationSound.Alert.entries)
                addAll(NotificationSound.Action.entries)
                addAll(NotificationSound.InputFeedback.entries)
                addAll(NotificationSound.Game.entries)
            }
        }

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeRange(EVENT_LOG_MAX, events.size)
        statusText = msg
    }

    // Register signal listener
    DisposableEffect(Unit) {
        val listener =
            object : LinuxNotificationListener {
                override fun onClosed(
                    notificationId: Int,
                    reason: CloseReason,
                ) {
                    log("Closed #$notificationId — reason: ${reason.name}")
                }

                override fun onActionInvoked(
                    notificationId: Int,
                    actionKey: String,
                ) {
                    log("Action #$notificationId — key: \"$actionKey\"")
                }

                override fun onActivationToken(
                    notificationId: Int,
                    token: String,
                ) {
                    log("Token #$notificationId — $token")
                }
            }
        LinuxNotificationCenter.addListener(listener)
        onDispose { LinuxNotificationCenter.removeListener(listener) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Linux Notifications", style = MaterialTheme.typography.headlineSmall)

            if (!LinuxNotificationCenter.isAvailable) {
                Text(
                    "Native library not available.",
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            // ── Server Info ──────────────────────────────────────────
            SectionCard("Server Information") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        serverInfo = LinuxNotificationCenter.getServerInformation()
                        capabilities = LinuxNotificationCenter.getCapabilities()
                        log("Fetched server info & capabilities")
                    }) { Text("Query Server") }
                }

                if (serverInfo != null) {
                    Spacer(Modifier.height(8.dp))
                    val info = serverInfo!!
                    Text("Name: ${info.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("Vendor: ${info.vendor}", style = MaterialTheme.typography.bodyMedium)
                    Text("Version: ${info.version}", style = MaterialTheme.typography.bodyMedium)
                    Text("Spec: ${info.specVersion}", style = MaterialTheme.typography.bodyMedium)
                }

                if (capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Capabilities:", style = MaterialTheme.typography.labelLarge)
                    Text(
                        capabilities.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // ── Quick Send ───────────────────────────────────────────
            SectionCard("Quick Send") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Simple notification
                    Button(onClick = {
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    summary = "Simple Notification",
                                    body = "This is a basic notification.",
                                    appIcon = FreedesktopIcon.Status.DIALOG_INFORMATION,
                                    hints = NotificationHints(imagePath = FreedesktopIcon.Status.DIALOG_INFORMATION),
                                ),
                            )
                        lastNotifId = id
                        log("Sent simple notification #$id")
                    }) { Text("Simple") }

                    // With actions
                    Button(onClick = {
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    summary = "Message from Alice",
                                    body = "Hey! Have you seen the latest build?",
                                    appIcon = FreedesktopIcon.Status.MAIL_UNREAD,
                                    actions =
                                        listOf(
                                            NotificationAction(NotificationAction.DEFAULT_KEY, "Open"),
                                            NotificationAction("reply", "Reply"),
                                            NotificationAction("archive", "Archive"),
                                        ),
                                    hints =
                                        NotificationHints(
                                            urgency = Urgency.NORMAL,
                                            category = "im.received",
                                            imagePath = FreedesktopIcon.Status.MAIL_UNREAD,
                                        ),
                                ),
                            )
                        lastNotifId = id
                        log("Sent message notification #$id with actions")
                    }) { Text("With Actions") }

                    // Critical urgency
                    Button(
                        onClick = {
                            val id =
                                LinuxNotificationCenter.notify(
                                    Notification(
                                        appName = "Nucleus Demo",
                                        summary = "Critical Alert",
                                        body = "Disk usage is above 95%!",
                                        appIcon = FreedesktopIcon.Status.DIALOG_WARNING,
                                        hints =
                                            NotificationHints(
                                                urgency = Urgency.CRITICAL,
                                                category = "device.error",
                                                imagePath = FreedesktopIcon.Status.DIALOG_WARNING,
                                            ),
                                        expireTimeout = 0,
                                    ),
                                )
                            lastNotifId = id
                            log("Sent critical notification #$id")
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                    ) { Text("Critical") }

                    // Low urgency
                    Button(onClick = {
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    summary = "Download Complete",
                                    body = "nucleus-1.3.0.tar.gz has finished downloading.",
                                    appIcon = FreedesktopIcon.Emblem.DOWNLOADS,
                                    hints =
                                        NotificationHints(
                                            urgency = Urgency.LOW,
                                            category = "transfer.complete",
                                            imagePath = FreedesktopIcon.Emblem.DOWNLOADS,
                                        ),
                                ),
                            )
                        lastNotifId = id
                        log("Sent low-urgency notification #$id")
                    }) { Text("Low Urgency") }

                    // With markup
                    Button(onClick = {
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    summary = "Markup Demo",
                                    body =
                                        "<b>Bold</b>, <i>italic</i>, <u>underline</u> " +
                                            "and <a href=\"https://github.com\">a link</a>.",
                                    appIcon = FreedesktopIcon.Status.DIALOG_INFORMATION,
                                    hints = NotificationHints(imagePath = FreedesktopIcon.Status.DIALOG_INFORMATION),
                                ),
                            )
                        lastNotifId = id
                        log("Sent markup notification #$id")
                    }) { Text("Markup") }

                    // With image data
                    Button(onClick = {
                        val imageData = createSampleImageData()
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    summary = "Image Data",
                                    body = "Notification with embedded pixel data.",
                                    hints = NotificationHints(imageData = imageData),
                                ),
                            )
                        lastNotifId = id
                        log("Sent image-data notification #$id")
                    }) { Text("Image Data") }

                    // Replace last
                    OutlinedButton(onClick = {
                        if (lastNotifId == 0) {
                            log("No notification to replace — send one first")
                            return@OutlinedButton
                        }
                        val id =
                            LinuxNotificationCenter.notify(
                                Notification(
                                    appName = "Nucleus Demo",
                                    replacesId = lastNotifId,
                                    summary = "Replaced!",
                                    body = "This notification replaced #$lastNotifId.",
                                    appIcon = FreedesktopIcon.Status.DIALOG_INFORMATION,
                                    hints = NotificationHints(imagePath = FreedesktopIcon.Status.DIALOG_INFORMATION),
                                ),
                            )
                        log("Replaced #$lastNotifId → #$id")
                        lastNotifId = id
                    }) { Text("Replace Last") }

                    // Close last
                    OutlinedButton(onClick = {
                        if (lastNotifId == 0) {
                            log("No notification to close")
                            return@OutlinedButton
                        }
                        LinuxNotificationCenter.closeNotification(lastNotifId)
                        log("Closed #$lastNotifId")
                    }) { Text("Close Last") }
                }
            }

            // ── Custom Builder ───────────────────────────────────────
            SectionCard("Custom Notification") {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body (supports <b>, <i>, <u>, <a>)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (e.g. im.received, email.arrived)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                // Icon selector (dropdown)
                Text("Icon (image-path)", style = MaterialTheme.typography.labelLarge)
                IconDropdown(
                    options = iconOptions,
                    selected = selectedIcon,
                    onSelect = { selectedIcon = it },
                )

                Spacer(Modifier.height(8.dp))

                // Urgency selector
                Text("Urgency", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Urgency.entries.forEach { u ->
                        val selected = urgency == u
                        if (selected) {
                            Button(onClick = {}) { Text(u.name) }
                        } else {
                            OutlinedButton(onClick = { urgency = u }) { Text(u.name) }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Expire timeout
                Text(
                    "Expire timeout: ${
                        when {
                            expireTimeout < 0.5f -> "server default (-1)"
                            expireTimeout < 1.5f -> "never (0)"
                            else -> "${(expireTimeout * 1000).toInt()} ms"
                        }
                    }",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = expireTimeout,
                    onValueChange = { expireTimeout = it },
                    valueRange = 0f..MAX_TIMEOUT,
                )

                // Checkboxes
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useActions, onCheckedChange = { useActions = it })
                    Text("Include actions (Open / Dismiss)")
                    Spacer(Modifier.width(16.dp))
                    Checkbox(checked = replacesPrevious, onCheckedChange = { replacesPrevious = it })
                    Text("Replace previous")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useSound, onCheckedChange = { useSound = it })
                    Text("Sound")
                }
                if (useSound) {
                    SoundDropdown(
                        options = soundOptions,
                        selected = selectedSound,
                        onSelect = { selectedSound = it },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val actions =
                        if (useActions) {
                            listOf(
                                NotificationAction(NotificationAction.DEFAULT_KEY, "Open"),
                                NotificationAction("dismiss", "Dismiss"),
                            )
                        } else {
                            emptyList()
                        }

                    val expireMs =
                        when {
                            expireTimeout < 0.5f -> -1 // server default
                            expireTimeout < 1.5f -> 0 // never expire
                            else -> (expireTimeout * 1000).toInt()
                        }

                    val id =
                        LinuxNotificationCenter.notify(
                            Notification(
                                appName = appName,
                                replacesId = if (replacesPrevious) lastNotifId else 0,
                                appIcon = selectedIcon,
                                summary = summary,
                                body = body,
                                actions = actions,
                                hints =
                                    NotificationHints(
                                        urgency = urgency,
                                        category = category.ifBlank { null },
                                        soundName = if (useSound) selectedSound else null,
                                        imagePath = selectedIcon,
                                    ),
                                expireTimeout = expireMs,
                            ),
                        )
                    lastNotifId = id
                    log("Sent custom notification #$id")
                }) { Text("Send") }
            }

            // ── Event Log ────────────────────────────────────────────
            SectionCard("Event Log") {
                if (events.isEmpty()) {
                    Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    events.forEachIndexed { index, event ->
                        Text(event, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (index < events.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun IconDropdown(
    options: List<FreedesktopIcon>,
    selected: FreedesktopIcon,
    onSelect: (FreedesktopIcon) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { icon ->
                DropdownMenuItem(
                    text = { Text(icon.value) },
                    onClick = {
                        onSelect(icon)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SoundDropdown(
    options: List<NotificationSound>,
    selected: NotificationSound,
    onSelect: (NotificationSound) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { sound ->
                DropdownMenuItem(
                    text = { Text(sound.value) },
                    onClick = {
                        onSelect(sound)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Creates a small sample RGBA image for the image-data hint.
 */
private fun createSampleImageData(): dev.nucleusframework.nucleus.notification.linux.ImageData {
    val size = SAMPLE_IMAGE_SIZE
    val channels = 4
    val rowstride = size * channels
    val pixels = ByteArray(size * rowstride)

    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0x44, 0x88, 0xFF)
    g.fillOval(0, 0, size, size)
    g.color = Color.WHITE
    g.font = g.font.deriveFont(32f)
    val fm = g.fontMetrics
    val text = "N"
    g.drawString(text, (size - fm.stringWidth(text)) / 2, (size + fm.ascent - fm.descent) / 2)
    g.dispose()

    // Extract RGBA bytes
    for (y in 0 until size) {
        for (x in 0 until size) {
            val argb = img.getRGB(x, y)
            val offset = y * rowstride + x * channels
            pixels[offset] = ((argb shr 16) and 0xFF).toByte() // R
            pixels[offset + 1] = ((argb shr 8) and 0xFF).toByte() // G
            pixels[offset + 2] = (argb and 0xFF).toByte() // B
            pixels[offset + 3] = ((argb shr 24) and 0xFF).toByte() // A
        }
    }

    return dev.nucleusframework.nucleus.notification.linux.ImageData(
        width = size,
        height = size,
        rowstride = rowstride,
        hasAlpha = true,
        data = pixels,
    )
}

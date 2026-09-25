package servicemanagementdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.servicemanagement.AppService
import dev.nucleusframework.servicemanagement.AppServiceManager
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val AGENT_LABEL = "dev.nucleusframework.servicemanagement.demo.notifier"

fun main(args: Array<String>) {
    if ("--notify" in args) {
        runBackgroundTask()
        return
    }
    launchUi()
}

/**
 * Called by the launch agent every 15 minutes (app closed).
 * Sends a notification and exits immediately.
 */
private fun runBackgroundTask() {
    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    notification(
        title = "SMAppService Demo",
        message = "Background task ran at $time",
    ).send()
}

private fun launchUi() =
    nucleusApplication {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "SMAppService Demo",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }

@Composable
fun App() {
    var log by remember { mutableStateOf("") }
    val logScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun appendLog(message: String) {
        log = "$message\n$log"
    }

    // SMAppService completion handlers call back on a private queue; hop to the
    // composition's dispatcher (the Tao main thread) before touching state.
    fun appendLogSafe(message: String) {
        scope.launch { appendLog(message) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SMAppService Demo", style = MaterialTheme.typography.headlineMedium)

        // Availability
        Card(modifier = Modifier.fillMaxWidth()) {
            val available = AppServiceManager.isAvailable
            Text(
                text = if (available) "SMAppService: Available" else "SMAppService: Not available",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }

        // Launch at Login — registers the app itself as a login item
        ServiceSection(
            title = "Launch at Login",
            description = "Registers this app to start automatically at user login (SMAppService.mainApp).",
            service = AppService.MainApp,
            onLog = ::appendLog,
            onLogSafe = ::appendLogSafe,
        )

        HorizontalDivider()

        // Background Agent — runs every 15 min even when app is closed
        ServiceSection(
            title = "Background Notification Agent",
            description =
                "Registers a launch agent that sends a notification every 15 minutes, " +
                    "even when the app is closed.",
            service = AppService.Agent(AGENT_LABEL),
            onLog = ::appendLog,
            onLogSafe = ::appendLogSafe,
        )

        // Test + Settings
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runBackgroundTask()
                appendLog("Test notification sent")
            }) { Text("Test Notification") }

            OutlinedButton(onClick = {
                val opened = AppServiceManager.openSystemSettingsLoginItems()
                appendLog(if (opened) "Opened Login Items settings" else "Failed to open settings")
            }) { Text("System Settings") }
        }

        // Log
        Text("Log:", style = MaterialTheme.typography.titleSmall)
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = log.ifEmpty { "(press a button)" },
                modifier = Modifier.padding(12.dp).verticalScroll(logScrollState),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ServiceSection(
    title: String,
    description: String,
    service: AppService,
    onLog: (String) -> Unit,
    onLogSafe: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val result = AppServiceManager.register(service)
                    onLog(
                        if (result.isSuccess) {
                            "[$title] Registered"
                        } else {
                            "[$title] Failed: ${result.exceptionOrNull()?.message}"
                        },
                    )
                }) { Text("Register") }

                Button(onClick = {
                    AppServiceManager.unregister(service) { error ->
                        onLogSafe(
                            if (error == null) "[$title] Unregistered" else "[$title] Error: $error",
                        )
                    }
                }) { Text("Unregister") }

                OutlinedButton(onClick = {
                    val status = AppServiceManager.status(service)
                    onLog("[$title] Status: $status")
                }) { Text("Status") }
            }
        }
    }
}

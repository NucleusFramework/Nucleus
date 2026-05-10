package schedulerdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.scheduler.CronExpression
import dev.nucleusframework.nucleus.scheduler.DesktopTaskScheduler
import dev.nucleusframework.nucleus.scheduler.TaskInfo
import dev.nucleusframework.nucleus.scheduler.TaskRequest
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import schedulerdemo.task.BackupInput
import schedulerdemo.task.BackupTaskId
import schedulerdemo.task.NotificationInput
import schedulerdemo.task.NotificationTaskId
import schedulerdemo.task.ReportTaskId
import schedulerdemo.task.SyncTaskId
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Suppress("LongMethod")
@Composable
fun SchedulerDemoView(openedByScheduler: Boolean = false) {
    val scheduler = DesktopTaskScheduler
    val logs = remember { mutableStateListOf<String>() }
    var tasks by remember { mutableStateOf(emptyList<TaskInfo>()) }

    fun log(message: String) {
        logs.add(0, message)
    }

    fun refreshTasks() {
        tasks = scheduler.getAllTasks()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (openedByScheduler) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2E7D32), RoundedCornerShape(8.dp))
                        .padding(12.dp),
            ) {
                Text("Opened by scheduler", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
        }

        GroupHeader("Platform")
        Text(
            if (scheduler.isAvailable()) {
                "Scheduler available"
            } else {
                "Scheduler not available on this platform"
            },
        )

        Spacer(Modifier.height(8.dp))
        GroupHeader("Enqueue tasks")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = {
                val ok = scheduler.enqueue(TaskRequest.periodic(SyncTaskId, 1.hours))
                log(if (ok) "Enqueued 'sync' (every 1h)" else "Failed to enqueue 'sync'")
                refreshTasks()
            }) {
                Text("Sync (1h periodic)")
            }

            DefaultButton(onClick = {
                val ok =
                    scheduler.enqueue(
                        TaskRequest.periodic(BackupTaskId, 30.minutes) {
                            inputData(BackupInput(target = "/tmp/backup"))
                        },
                    )
                log(if (ok) "Enqueued 'backup' (every 30min)" else "Failed to enqueue 'backup'")
                refreshTasks()
            }) {
                Text("Backup (30min periodic)")
            }

            DefaultButton(onClick = {
                val ok =
                    scheduler.enqueue(
                        TaskRequest.calendar(ReportTaskId, CronExpression.everyDayAt(LocalTime.of(9, 0))),
                    )
                log(if (ok) "Enqueued 'report' (daily at 9h)" else "Failed to enqueue 'report'")
                refreshTasks()
            }) {
                Text("Report (daily 9h)")
            }

            DefaultButton(onClick = {
                val ok =
                    scheduler.enqueue(
                        TaskRequest.periodic(NotificationTaskId, 15.minutes) {
                            inputData(
                                NotificationInput(
                                    title = "Scheduled Notification",
                                    message = "This notification was scheduled 15 minutes ago!",
                                ),
                            )
                        },
                    )
                log(if (ok) "Enqueued 'notification' (every 15min)" else "Failed to enqueue 'notification'")
                refreshTasks()
            }) {
                Text("Notify (15min)")
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupHeader("Manage tasks")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                refreshTasks()
                log("Refreshed task list (${tasks.size} tasks)")
            }) {
                Text("Refresh")
            }

            OutlinedButton(onClick = {
                scheduler.cancelAll()
                log("Cancelled all tasks")
                refreshTasks()
            }) {
                Text("Cancel all")
            }
        }

        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GroupHeader("Scheduled tasks (${tasks.size})")

            tasks.forEach { info ->
                TaskInfoRow(info, onCancel = {
                    scheduler.cancel(info.taskId)
                    log("Cancelled '${info.taskId}'")
                    refreshTasks()
                })
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupHeader("Log")

        if (logs.isEmpty()) {
            Text("No activity yet. Enqueue a task to get started.")
        } else {
            logs.forEach { entry ->
                Text(entry)
            }
        }
    }
}

@Composable
private fun TaskInfoRow(
    info: TaskInfo,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${info.taskId} — ${info.state}")
            val details =
                buildString {
                    append("Runs: ${info.runCount}")
                    info.lastRunMs?.let { append(" | Last: ${formatTimestamp(it)}") }
                    info.nextRunMs?.let { append(" | Next: ${formatTimestamp(it)}") }
                    info.lastResult?.let { append(" | Result: $it") }
                }
            Text(details)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(epochMs: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMs))

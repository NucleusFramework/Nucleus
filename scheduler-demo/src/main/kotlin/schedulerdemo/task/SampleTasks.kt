package schedulerdemo.task

import dev.nucleusframework.nucleus.scheduler.DesktopTask
import dev.nucleusframework.nucleus.scheduler.TaskContext
import dev.nucleusframework.nucleus.scheduler.TaskId
import dev.nucleusframework.nucleus.scheduler.TaskResult
import dev.nucleusframework.nucleus.scheduler.inputData
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

val SyncTaskId: TaskId = TaskId("sync")
val BackupTaskId: TaskId = TaskId("backup")
val NotificationTaskId: TaskId = TaskId("notification")
val ReportTaskId: TaskId = TaskId("report")

@Serializable
data class BackupInput(
    val target: String,
)

@Serializable
data class NotificationInput(
    val title: String,
    val message: String,
)

class SyncTask : DesktopTask {
    private val logger = Logger.getLogger(SyncTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logger.info("SyncTask executed at $timestamp (attempt ${context.runAttemptCount})")
        return TaskResult.Success
    }
}

class BackupTask : DesktopTask {
    private val logger = Logger.getLogger(BackupTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val target = context.inputData<BackupInput>()?.target ?: "default"
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logger.info("BackupTask to '$target' executed at $timestamp")
        return TaskResult.Success
    }
}

class NotificationTask : DesktopTask {
    private val logger = Logger.getLogger(NotificationTask::class.java.name)

    override suspend fun doWork(context: TaskContext): TaskResult {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logger.info("NotificationTask executed at $timestamp")
        return TaskResult.Success
    }
}

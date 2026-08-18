package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.TaskId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskWrapperScriptTest {
    private val appId = "nucleus-kover-wrapper-scripts"
    private val taskId = TaskId("wrap-task")

    @AfterTest
    fun cleanup() {
        TaskWrapperScript.deleteAllScripts(appId)
        TaskWrapperScript.scriptFile(appId, taskId).parentFile?.delete()
    }

    @Test
    fun `scriptFile uses a platform-specific extension`() {
        val file = TaskWrapperScript.scriptFile(appId, taskId)
        val expectedExt = if (Platform.Current == Platform.Windows) "vbs" else "sh"
        assertEquals(expectedExt, file.extension)
        assertEquals("${taskId.value}.$expectedExt", file.name)
        assertTrue(file.path.contains(appId))
    }

    @Test
    fun `windows script quotes the executable and deletes orphans`() {
        val file =
            TaskWrapperScript.generateWindowsScript(
                appId = appId,
                taskId = taskId,
                execPath = """C:\Program Files\App\App.exe""",
                taskFolder = """\Nucleus\demo""",
                metadataDir = """C:\Users\me\AppData\Local\nucleus\scheduler\demo""",
            )
        val text = file.readText()
        assertTrue(file.isFile)
        assertTrue(text.contains("Schedule.Service"))
        assertTrue(text.contains("\"\"C:\\Program Files\\App\\App.exe\"\""))
        assertTrue(text.contains("folder.DeleteTask \"wrap-task\", 0"))
        assertTrue(text.contains("folder.DeleteTask \"wrap-task-retry\", 0"))
        assertTrue(text.contains("--nucleus-scheduler-run wrap-task"))
        assertTrue(text.contains("wrap-task.properties"))
    }

    @Test
    fun `windows script doubles embedded quotes`() {
        val file =
            TaskWrapperScript.generateWindowsScript(
                appId = appId,
                taskId = taskId,
                execPath = """C:\Path\"quoted"\app.exe""",
                taskFolder = "\\Nucleus",
                metadataDir = "C:\\meta",
            )
        assertTrue(file.readText().contains("\"\"C:\\Path\\\"\"quoted\"\"\\app.exe\"\""))
    }

    @Test
    fun `linux script disables units when the executable is missing`() {
        val file =
            TaskWrapperScript.generateLinuxScript(
                appId = appId,
                taskId = taskId,
                execPath = "/opt/App/bin/App",
                timerUnit = "nucleus-demo-wrap-task.timer",
                serviceUnit = "nucleus-demo-wrap-task.service",
                serviceFilePath = "/home/user/.config/systemd/user/nucleus-demo-wrap-task.service",
                timerFilePath = "/home/user/.config/systemd/user/nucleus-demo-wrap-task.timer",
                metadataDir = "/home/user/.local/share/nucleus/scheduler/demo",
            )
        val text = file.readText()
        assertTrue(file.isFile)
        assertTrue(text.startsWith("#!/bin/bash"))
        assertTrue(text.contains("EXEC=\"/opt/App/bin/App\""))
        assertTrue(text.contains("systemctl --user disable --now \"nucleus-demo-wrap-task.timer\""))
        assertTrue(text.contains("systemctl --user disable \"nucleus-demo-wrap-task.service\""))
        assertTrue(text.contains("\"\$EXEC\" --nucleus-scheduler-run wrap-task"))
        if (Platform.Current != Platform.Windows) {
            assertTrue(file.canExecute())
        }
    }

    @Test
    fun `deleteScript removes a generated file`() {
        val file =
            TaskWrapperScript.generateLinuxScript(
                appId = appId,
                taskId = taskId,
                execPath = "/bin/true",
                timerUnit = "t.timer",
                serviceUnit = "t.service",
                serviceFilePath = "/tmp/t.service",
                timerFilePath = "/tmp/t.timer",
                metadataDir = "/tmp",
            )
        assertTrue(file.exists())
        TaskWrapperScript.deleteScript(appId, taskId)
        assertFalse(file.exists())
    }

    @Test
    fun `deleteAllScripts is safe when the directory is missing`() {
        TaskWrapperScript.deleteAllScripts("nucleus-kover-wrapper-missing")
        val leftover = TaskWrapperScript.scriptFile("nucleus-kover-wrapper-missing", TaskId("x"))
        assertFalse(leftover.exists())
    }

    @Test
    fun `deleteAllScripts removes generated files for the app`() {
        val first =
            TaskWrapperScript.generateLinuxScript(
                appId = appId,
                taskId = taskId,
                execPath = "/bin/true",
                timerUnit = "t.timer",
                serviceUnit = "t.service",
                serviceFilePath = "/tmp/t.service",
                timerFilePath = "/tmp/t.timer",
                metadataDir = "/tmp",
            )
        val second =
            TaskWrapperScript.generateWindowsScript(
                appId = appId,
                taskId = TaskId("wrap-other"),
                execPath = "C:\\App.exe",
                taskFolder = "\\Nucleus",
                metadataDir = "C:\\meta",
            )
        assertTrue(first.exists() || second.exists())
        TaskWrapperScript.deleteAllScripts(appId)
        assertFalse(TaskWrapperScript.scriptFile(appId, taskId).exists())
        assertFalse(TaskWrapperScript.scriptFile(appId, TaskId("wrap-other")).exists())
    }

    @Test
    fun `linux script quotes paths that contain spaces`() {
        val file =
            TaskWrapperScript.generateLinuxScript(
                appId = appId,
                taskId = taskId,
                execPath = "/opt/My App/bin/App",
                timerUnit = "my app.timer",
                serviceUnit = "my app.service",
                serviceFilePath = "/home/user/.config/systemd/user/my app.service",
                timerFilePath = "/home/user/.config/systemd/user/my app.timer",
                metadataDir = "/home/user/.local/share/nucleus/scheduler/my app",
            )
        val text = file.readText()
        assertTrue(text.contains("EXEC=\"/opt/My App/bin/App\""))
        assertTrue(text.contains("systemctl --user disable --now \"my app.timer\""))
        assertTrue(text.contains("rm -f \"/home/user/.config/systemd/user/my app.service\""))
    }

    @Test
    fun `unitBaseName includes the prefix app id and task id`() {
        val name = LinuxSystemdScheduler.unitBaseName(TaskId("nightly"))
        assertTrue(name.startsWith("nucleus-"))
        assertTrue(name.endsWith("-nightly"))
    }
}

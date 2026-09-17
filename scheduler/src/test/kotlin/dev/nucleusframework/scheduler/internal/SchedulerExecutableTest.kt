package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.SchedulerConfig
import dev.nucleusframework.scheduler.TaskId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SchedulerExecutableTest {
    @AfterTest
    fun reset() {
        SchedulerConfig.executablePath = null
        SchedulerConfig.executableArguments = emptyList()
    }

    private fun runningExecutable(): String? =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)

    @Test
    fun `defaults to the running executable`() {
        assertEquals(runningExecutable(), SchedulerExecutable.path)
    }

    @Test
    fun `custom executable path wins`() {
        SchedulerConfig.executablePath = "/opt/myapp/launcher"

        assertEquals("/opt/myapp/launcher", SchedulerExecutable.path)
    }

    @Test
    fun `blank executable path falls back to the running executable`() {
        SchedulerConfig.executablePath = "   "

        assertEquals(runningExecutable(), SchedulerExecutable.path)
    }

    @Test
    fun `extra arguments precede the scheduler flag`() {
        SchedulerConfig.executableArguments = listOf("--background", "--quiet")

        assertEquals(
            listOf("--background", "--quiet", "--nucleus-scheduler-run", "sync"),
            SchedulerExecutable.argumentsFor(TaskId("sync")),
        )
    }

    @Test
    fun `command line starts with the executable`() {
        SchedulerConfig.executablePath = "/opt/myapp/launcher"
        SchedulerConfig.executableArguments = listOf("--background")

        assertEquals(
            listOf("/opt/myapp/launcher", "--background", "--nucleus-scheduler-run", "sync"),
            SchedulerExecutable.commandLine(TaskId("sync")),
        )
    }
}

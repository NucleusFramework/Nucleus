package dev.nucleusframework.application

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.SingleInstanceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class SingleInstanceWatcherTest {
    @Test
    fun restoreRequestReachesTheFirstInstanceWithoutPolling() {
        assumeTrue(Platform.Current == Platform.MacOS)
        SingleInstanceManager.configuration =
            SingleInstanceManager.Configuration(
                lockFilesDir = Files.createTempDirectory("nucleus-single-instance"),
                lockIdentifier = "watcher-test",
            )
        acquireSingleInstanceLock(emptyArray())

        Files.createFile(SingleInstanceManager.configuration.restoreRequestFilePath)

        val deadline = System.currentTimeMillis() + 5_000
        while (SingleInstanceRestoreBus.signal.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("restore request not delivered", SingleInstanceRestoreBus.signal.value > 0)
        // The JDK WatchService polls on macOS from its "FileSystemWatcher" thread: it must not run.
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == "FileSystemWatcher" })
    }
}

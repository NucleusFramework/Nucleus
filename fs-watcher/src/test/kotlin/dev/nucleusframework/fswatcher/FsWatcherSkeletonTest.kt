package dev.nucleusframework.fswatcher

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FsWatcherSkeletonTest {
    @Test
    fun watcherConfigUsesCurrentDefaultBufferCapacities() {
        val config = FsWatcherConfig()

        assertEquals(64, config.eventBufferCapacity)
        assertEquals(32, config.errorBufferCapacity)
    }

    @Test
    fun watcherConfigRejectsNonPositiveEventBufferCapacity() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                FsWatcherConfig(eventBufferCapacity = 0)
            }

        assertTrue(error.message!!.contains("eventBufferCapacity"))
    }

    @Test
    fun watcherConfigRejectsNonPositiveErrorBufferCapacity() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                FsWatcherConfig(errorBufferCapacity = 0)
            }

        assertTrue(error.message!!.contains("errorBufferCapacity"))
    }

    @Test
    fun debouncedDeliveryRejectsNonPositiveWindow() {
        assertFailsWith<IllegalArgumentException> {
            FsWatchDeliveryMode.Debounced(window = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            FsWatchDeliveryMode.Debounced(window = Duration.ofMillis(-1))
        }
    }

    @Test
    fun watchEventVariantsExposeSourceAndRescanFlags() {
        val root = Files.createTempDirectory("fs-watcher-event")
        val source = FsWatchSource(root, recursive = false, name = "tmp")
        val created = FsWatchEvent.Created(root.resolve("a"), source, isDirectory = false)
        val overflow = FsWatchEvent.Overflow(source)
        val other = FsWatchEvent.Other(listOf(root), source)
        assertEquals(source, created.source)
        assertFalse(created.needsRescan)
        assertTrue(overflow.needsRescan)
        assertEquals(1, other.paths.size)
        val error = FsWatchError("boom", source, recoverable = true)
        assertTrue(error.recoverable)
        assertEquals("boom", error.message)
    }

    @Test
    fun pollingStrategyHasReasonableDefaultInterval() {
        val strategy = FsWatchBackendStrategy.Polling()

        assertTrue(strategy.interval.seconds >= 1)
    }

    @Test
    fun pollingBackendReportsSupportedOnlyWhenNativeBridgeCanCreatePollingWatcher() {
        val config =
            FsWatcherConfig(
                backend = FsWatchBackendStrategy.Polling(interval = Duration.ofMillis(50)),
            )

        try {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting { _, backendMode, _, _, _, _ ->
                if (backendMode == BACKEND_MODE_POLLING) 77L else 0L
            }
            assertTrue(FsWatchers.isSupported(config))

            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting { _, backendMode, _, _, _, _ ->
                if (backendMode == BACKEND_MODE_POLLING) 0L else 0L
            }
            assertFalse(FsWatchers.isSupported(config))
        } finally {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
        }
    }

    @Test
    fun nativeBootstrapReportsSupportedOnHostPlatform() {
        assertTrue(FsWatchers.isSupported())
    }

    @Test
    fun watchRegistersSourceUntilClosed() {
        if (!FsWatchers.isSupported()) return

        val root = Files.createTempDirectory("fs-watcher-test")
        FsWatchers.create().use { watcher ->
            val registration = watcher.watch(root)
            assertEquals(setOf(FsWatchSource(root, true, null)), watcher.registrations)
            assertTrue(registration.active)
            registration.close()
            assertFalse(registration.active)
            assertTrue(watcher.registrations.isEmpty())
        }
    }
}

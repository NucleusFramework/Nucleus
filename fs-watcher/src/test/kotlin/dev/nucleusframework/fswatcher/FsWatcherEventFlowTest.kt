package dev.nucleusframework.fswatcher

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class FsWatcherEventFlowTest {
    @Test
    fun fsWatcherConfigDefaultsToDebouncedDelivery() {
        val config = FsWatcherConfig()

        assertIs<FsWatchDeliveryMode.Debounced>(config.deliveryMode)
        assertEquals(Duration.ofMillis(150), config.deliveryMode.window)
    }

    @Test
    fun fsWatcherConfigRejectsInvalidDebounceWindows() {
        assertFailsWith<IllegalArgumentException> {
            FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ZERO))
        }

        assertFailsWith<IllegalArgumentException> {
            FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(-1)))
        }

        assertFailsWith<IllegalArgumentException> {
            FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofNanos(1)))
        }
    }

    @Test
    fun createMapsDeliveryModeToNativeCreateParameters() {
        val rawMode = AtomicInteger(-1)
        val rawWindowMillis = AtomicLong(-1L)
        val debouncedMode = AtomicInteger(-1)
        val debouncedWindowMillis = AtomicLong(-1L)
        val nextHandle = AtomicLong(10L)

        NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
            followSymlinks,
            backendMode,
            deliveryMode,
            debounceWindowMillis,
            pollIntervalMillis,
            compareContents,
            ->
            when (nextHandle.get()) {
                10L -> {
                    assertEquals(BACKEND_MODE_NATIVE, backendMode)
                    rawMode.set(deliveryMode)
                    rawWindowMillis.set(debounceWindowMillis)
                    assertTrue(followSymlinks)
                    assertEquals(0L, pollIntervalMillis)
                    assertEquals(false, compareContents)
                }
                11L -> {
                    assertEquals(BACKEND_MODE_NATIVE, backendMode)
                    debouncedMode.set(deliveryMode)
                    debouncedWindowMillis.set(debounceWindowMillis)
                    assertFalse(followSymlinks)
                    assertEquals(0L, pollIntervalMillis)
                    assertEquals(false, compareContents)
                }
            }
            nextHandle.getAndIncrement()
        }

        try {
            FsWatchers
                .create(
                    FsWatcherConfig(
                        followSymlinks = true,
                        deliveryMode = FsWatchDeliveryMode.Raw,
                    ),
                ).close()

            FsWatchers
                .create(
                    FsWatcherConfig(
                        deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(275)),
                    ),
                ).close()
        } finally {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
        }

        assertEquals(DELIVERY_MODE_RAW, rawMode.get())
        assertEquals(0L, rawWindowMillis.get())
        assertEquals(DELIVERY_MODE_DEBOUNCED, debouncedMode.get())
        assertEquals(275L, debouncedWindowMillis.get())
    }

    @Test
    fun createDefaultsToDebouncedNativeCreateParameters() {
        val deliveryMode = AtomicInteger(-1)
        val debounceWindowMillis = AtomicLong(-1L)

        NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
            followSymlinks,
            backendMode,
            mode,
            windowMillis,
            pollIntervalMillis,
            compareContents,
            ->
            assertFalse(followSymlinks)
            assertEquals(BACKEND_MODE_NATIVE, backendMode)
            deliveryMode.set(mode)
            debounceWindowMillis.set(windowMillis)
            assertEquals(0L, pollIntervalMillis)
            assertEquals(false, compareContents)
            91L
        }

        try {
            FsWatchers.create().close()
        } finally {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
        }

        assertEquals(DELIVERY_MODE_DEBOUNCED, deliveryMode.get())
        assertEquals(150L, debounceWindowMillis.get())
    }

    @Test
    fun createWatcherPassesPollingModeIntervalAndCompareContentsToNativeCreate() {
        val backendMode = AtomicInteger(-1)
        val deliveryMode = AtomicInteger(-1)
        val debounceWindowMillis = AtomicLong(-1L)
        val pollIntervalMillis = AtomicLong(-1L)
        val compareContents = AtomicReference<Boolean?>(null)

        NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
            followSymlinks,
            nativeBackendMode,
            nativeDeliveryMode,
            nativeDebounceWindowMillis,
            nativePollIntervalMillis,
            nativeCompareContents,
            ->
            assertFalse(followSymlinks)
            backendMode.set(nativeBackendMode)
            deliveryMode.set(nativeDeliveryMode)
            debounceWindowMillis.set(nativeDebounceWindowMillis)
            pollIntervalMillis.set(nativePollIntervalMillis)
            compareContents.set(nativeCompareContents)
            101L
        }

        try {
            NativeFsWatcherBridge.createWatcher(
                followSymlinks = false,
                backendMode = BACKEND_MODE_POLLING,
                deliveryMode = DELIVERY_MODE_RAW,
                debounceWindowMillis = 0L,
                pollIntervalMillis = 2_000L,
                compareContents = true,
            )
        } finally {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
        }

        assertEquals(BACKEND_MODE_POLLING, backendMode.get())
        assertEquals(DELIVERY_MODE_RAW, deliveryMode.get())
        assertEquals(0L, debounceWindowMillis.get())
        assertEquals(2_000L, pollIntervalMillis.get())
        assertEquals(true, compareContents.get())
    }

    @Test
    fun createWatcherPassesPollingDebouncedModeToNativeCreate() {
        val backendMode = AtomicInteger(-1)
        val deliveryMode = AtomicInteger(-1)
        val debounceWindowMillis = AtomicLong(-1L)
        val pollIntervalMillis = AtomicLong(-1L)
        val compareContents = AtomicReference<Boolean?>(null)

        NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
            followSymlinks,
            nativeBackendMode,
            nativeDeliveryMode,
            nativeDebounceWindowMillis,
            nativePollIntervalMillis,
            nativeCompareContents,
            ->
            assertFalse(followSymlinks)
            backendMode.set(nativeBackendMode)
            deliveryMode.set(nativeDeliveryMode)
            debounceWindowMillis.set(nativeDebounceWindowMillis)
            pollIntervalMillis.set(nativePollIntervalMillis)
            compareContents.set(nativeCompareContents)
            102L
        }

        try {
            FsWatchers
                .create(
                    FsWatcherConfig(
                        backend =
                            FsWatchBackendStrategy.Polling(
                                interval = Duration.ofMillis(250),
                                compareContents = true,
                            ),
                        deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(175)),
                    ),
                ).close()
        } finally {
            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
        }

        assertEquals(BACKEND_MODE_POLLING, backendMode.get())
        assertEquals(DELIVERY_MODE_DEBOUNCED, deliveryMode.get())
        assertEquals(175L, debounceWindowMillis.get())
        assertEquals(250L, pollIntervalMillis.get())
        assertEquals(true, compareContents.get())
    }

    @Test
    fun rawAndDebouncedConfigsCanWatchDirectoryAndClose() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectoryForEventFlowTest("fs-watcher-create-modes")

            listOf(
                FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(150))),
            ).forEach { config ->
                FsWatchers.create(config).use { watcher ->
                    watcher.watch(root).close()
                }
            }
        }

    @Test
    fun pollingWatcherCanRegisterAndReceiveScopedEvent() =
        runBlocking {
            val root = Files.createTempDirectory("fs-watcher-polling-scoped")
            val eventPath = root.resolve("alpha.txt")
            val createCalls = AtomicInteger(0)

            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
                _,
                backendMode,
                _,
                _,
                pollIntervalMillis,
                compareContents,
                ->
                assertEquals(BACKEND_MODE_POLLING, backendMode)
                assertEquals(50L, pollIntervalMillis)
                assertEquals(true, compareContents)
                createCalls.incrementAndGet()
                601L
            }
            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, _ ->
                assertEquals(root.toString(), path)
                true
            }

            try {
                val watcher =
                    FsWatchers.create(
                        FsWatcherConfig(
                            backend =
                                FsWatchBackendStrategy.Polling(
                                    interval = Duration.ofMillis(50),
                                    compareContents = true,
                                ),
                            deliveryMode = FsWatchDeliveryMode.Raw,
                        ),
                    ) as NativeBackedFsWatcher

                watcher.use {
                    val registration = watcher.watch(root)
                    val registrationId = watcher.registrationIdFor(registration.source)!!

                    val createdDeferred =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(5_000) {
                                watcher.events.first {
                                    it is FsWatchEvent.Created &&
                                        it.path == eventPath &&
                                        it.source == registration.source
                                }
                            }
                        }

                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        registrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    assertEquals(
                        FsWatchEvent.Created(
                            path = eventPath,
                            source = registration.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        createdDeferred.await(),
                    )
                }
            } finally {
                NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
                NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
            }

            assertEquals(1, createCalls.get())
        }

    @Test
    fun pollingDebouncedWatcherCanRegisterAndReceiveScopedEvent() =
        runBlocking {
            val root = Files.createTempDirectory("fs-watcher-polling-debounced-scoped")
            val eventPath = root.resolve("alpha.txt")
            val createCalls = AtomicInteger(0)

            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting {
                _,
                backendMode,
                deliveryMode,
                debounceWindowMillis,
                pollIntervalMillis,
                compareContents,
                ->
                assertEquals(BACKEND_MODE_POLLING, backendMode)
                assertEquals(DELIVERY_MODE_DEBOUNCED, deliveryMode)
                assertEquals(125L, debounceWindowMillis)
                assertEquals(50L, pollIntervalMillis)
                assertEquals(false, compareContents)
                createCalls.incrementAndGet()
                603L
            }
            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, _ ->
                assertEquals(root.toString(), path)
                true
            }

            try {
                val watcher =
                    FsWatchers.create(
                        FsWatcherConfig(
                            backend = FsWatchBackendStrategy.Polling(interval = Duration.ofMillis(50)),
                            deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(125)),
                        ),
                    ) as NativeBackedFsWatcher

                watcher.use {
                    val registration = watcher.watch(root)
                    val registrationId = watcher.registrationIdFor(registration.source)!!

                    val createdDeferred =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(5_000) {
                                watcher.events.first {
                                    it is FsWatchEvent.Created &&
                                        it.path == eventPath &&
                                        it.source == registration.source
                                }
                            }
                        }

                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        registrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    assertEquals(
                        FsWatchEvent.Created(
                            path = eventPath,
                            source = registration.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        createdDeferred.await(),
                    )
                }
            } finally {
                NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
                NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
            }

            assertEquals(1, createCalls.get())
        }

    @Test
    fun pollingRegistrationCloseDropsLateEvent() =
        runBlocking {
            val root = Files.createTempDirectory("fs-watcher-polling-close")
            val latePath = root.resolve("late.txt")

            NativeFsWatcherBridge.setNativeCreateInterceptorForTesting { _, backendMode, _, _, _, _ ->
                assertEquals(BACKEND_MODE_POLLING, backendMode)
                602L
            }
            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, _ ->
                assertEquals(root.toString(), path)
                true
            }

            try {
                val watcher =
                    FsWatchers.create(
                        FsWatcherConfig(
                            backend = FsWatchBackendStrategy.Polling(interval = Duration.ofMillis(50)),
                            deliveryMode = FsWatchDeliveryMode.Raw,
                        ),
                    ) as NativeBackedFsWatcher

                watcher.use {
                    val registration = watcher.watch(root)
                    val registrationId = watcher.registrationIdFor(registration.source)!!

                    val maybeLateEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(300) {
                                watcher.events.first {
                                    it is FsWatchEvent.Created &&
                                        it.path == latePath &&
                                        it.source == registration.source
                                }
                            }
                        }

                    registration.close()

                    val emitted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = registrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = latePath,
                            isDirectory = 0,
                        )

                    assertFalse(emitted)
                    assertNull(maybeLateEvent.await())
                }
            } finally {
                NativeFsWatcherBridge.setNativeCreateInterceptorForTesting(null)
                NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
            }
        }

    @Test
    fun defaultDebouncedWatcherProducesAcceptedNativeCallbackForRealFileChange() {
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectoryForEventFlowTest("fs-watcher-default-debounced-callback")
            val target = root.resolve("callback.txt")

            (FsWatchers.create() as NativeBackedFsWatcher).use { watcher ->
                val accepted = AtomicReference<NativeFsWatchEventPayload?>()
                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {
                            if (payload.path == target) {
                                accepted.compareAndSet(null, payload)
                            }
                        }
                    },
                )

                try {
                    watcher.watch(root)
                    Files.writeString(target, "one")

                    withTimeout(5_000) {
                        while (accepted.get() == null) {
                            delay(25)
                        }
                    }

                    assertEquals(target, accepted.get()?.path)
                    assertEquals(WATCHER_LEVEL_REGISTRATION_ID, accepted.get()?.registrationId)
                    assertNotNull(accepted.get()?.originNativeRegistrationId)
                } finally {
                    NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
                    deleteRecursivelyForEventFlowTest(root)
                }
            }
        }
    }

    @Test
    fun fileOperationsEmitCoreEventsFromNotify() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectoryForEventFlowTest("fs-watcher-native-events")
            val target = root.resolve("demo.txt")

            FsWatchers
                .create(
                    FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                ).use { watcher ->
                    watcher.watch(root)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    Files.writeString(target, "one")
                    Files.writeString(target, "two")
                    Files.delete(target)

                    withTimeout(10_000) {
                        while (
                            !hasObservedCoreEvents(
                                seen = seen,
                                target = target,
                                requireModified = !isWindowsHost(),
                            )
                        ) {
                            delay(25)
                        }
                    }

                    collector.cancel()

                    assertTrue(seen.any { it is FsWatchEvent.Created && it.path == target })
                    if (!isWindowsHost()) {
                        assertTrue(seen.any { it is FsWatchEvent.Modified && it.path == target })
                    }
                    assertTrue(seen.any { it is FsWatchEvent.Removed && it.path == target })
                }
        }

    @Test
    fun injectedCallbacksFeedWatcherFlows() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-callback")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val createdPath = root.resolve("alpha.txt")

                val createdDeferred =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Created && it.path == createdPath
                            }
                        }
                    }
                val overflowDeferred =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first { it is FsWatchEvent.Overflow }
                        }
                    }
                val errorDeferred =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.errors.first { it.message == "native boom" }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    registrationId,
                    registrationId,
                    EVENT_KIND_CREATED,
                    createdPath.toString(),
                    null,
                    false,
                    0,
                )
                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    EVENT_KIND_OVERFLOW,
                    null,
                    null,
                    true,
                    -1,
                )
                NativeFsWatcherBridge.onNativeError(
                    watcher.nativeHandle,
                    registrationId,
                    registrationId,
                    "native boom",
                    true,
                    root.toString(),
                )

                assertEquals(
                    FsWatchEvent.Created(
                        path = createdPath,
                        source = registration.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    createdDeferred.await(),
                )
                assertEquals(
                    FsWatchEvent.Overflow(source = null),
                    overflowDeferred.await(),
                )
                assertEquals(
                    FsWatchError(
                        message = "native boom",
                        source = registration.source,
                        recoverable = true,
                        cause = null,
                    ),
                    errorDeferred.await(),
                )
            }
        }

    @Test
    fun closeDropsLateNativeCallbacks() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-close")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val registration = watcher.watch(root)
            val registrationId = watcher.registrationIdFor(registration.source)!!
            val latePath = root.resolve("late.txt")

            val maybeLateEvent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(300) {
                        watcher.events.first {
                            it is FsWatchEvent.Created && it.path == latePath
                        }
                    }
                }

            watcher.close()

            val emitted =
                NativeFsWatcherBridge.debugEmitPathEventForTesting(
                    watcherHandle = watcher.nativeHandle,
                    originNativeRegistrationId = registrationId,
                    eventKind = EVENT_KIND_CREATED,
                    path = latePath,
                    isDirectory = 0,
                )

            assertFalse(emitted)
            assertNull(maybeLateEvent.await())
        }

    @Test
    fun latePathEventAfterRegistrationCloseIsDroppedAfterRoutingExtraction() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-close-routing-late-path")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val registration = watcher.watch(root)
            val registrationId = watcher.registrationIdFor(registration.source)!!
            val latePath = root.resolve("late.txt")

            val maybeLateEvent =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(300) {
                        watcher.events.first {
                            it is FsWatchEvent.Created && it.path == latePath
                        }
                    }
                }

            registration.close()

            val emitted =
                NativeFsWatcherBridge.debugEmitPathEventForTesting(
                    watcherHandle = watcher.nativeHandle,
                    originNativeRegistrationId = registrationId,
                    eventKind = EVENT_KIND_CREATED,
                    path = latePath,
                    isDirectory = 0,
                )

            watcher.close()

            assertFalse(emitted)
            assertNull(maybeLateEvent.await())
        }

    @Test
    fun unwatchDropsLateNativeCallbacks() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-unwatch")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val latePath = root.resolve("late.txt")

                val maybeLateEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified && it.path == latePath
                            }
                        }
                    }

                registration.close()

                val emitted =
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = registrationId,
                        eventKind = EVENT_KIND_MODIFIED,
                        path = latePath,
                        isDirectory = 0,
                    )

                assertFalse(emitted)
                assertNull(maybeLateEvent.await())
            }
        }

    @Test
    fun staleNeedsRescanPathEventAfterUnwatchDoesNotEscalateToWatcherOverflow() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-stale-needs-rescan")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val stalePath = root.resolve("late-overflow.txt")

                val maybeOverflow =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first { it is FsWatchEvent.Overflow }
                        }
                    }

                registration.close()

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    registrationId,
                    EVENT_KIND_CREATED,
                    stalePath.toString(),
                    null,
                    true,
                    0,
                )

                assertNull(maybeOverflow.await())
            }
        }

    @Test
    fun closingOneDuplicateWatchKeepsOtherRegistrationReceivingEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-duplicate-watch")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root)
                val second = watcher.watch(root)
                val sharedRegistrationId = watcher.registrationIdFor(first.source)
                val eventPath = root.resolve("still-active.txt")

                assertNotNull(sharedRegistrationId)

                first.close()

                val survivingEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == second.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    sharedRegistrationId,
                    sharedRegistrationId,
                    EVENT_KIND_MODIFIED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Modified(
                        path = eventPath,
                        source = second.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    survivingEvent.await(),
                )
                assertFalse(first.active)
                assertTrue(second.active)
            }
        }

    @Test
    fun samePathDifferentNamesShareNativeWatchAndKeepSurvivorActive() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-shared-native-key")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, name = "alpha")
                val second = watcher.watch(root, name = "beta")
                val firstRegistrationId = watcher.registrationIdFor(first.source)
                val secondRegistrationId = watcher.registrationIdFor(second.source)
                val eventPath = root.resolve("shared.txt")

                assertEquals(firstRegistrationId, secondRegistrationId)
                assertNotNull(firstRegistrationId)

                first.close()

                val survivingEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == second.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    firstRegistrationId,
                    firstRegistrationId,
                    EVENT_KIND_MODIFIED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Modified(
                        path = eventPath,
                        source = second.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    survivingEvent.await(),
                )
                assertFalse(first.active)
                assertTrue(second.active)
            }
        }

    @Test
    fun samePathSameRecursiveDifferentNamesFanOutOnePathEventToBothRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-shared-native-key-ambiguous")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, name = "alpha")
                val second = watcher.watch(root, name = "beta")
                val sharedRegistrationId = watcher.registrationIdFor(first.source)
                val eventPath = root.resolve("ambiguous.txt")

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(second.source))
                assertNotNull(sharedRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Modified>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Modified && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        sharedRegistrationId,
                        sharedRegistrationId,
                        EVENT_KIND_MODIFIED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingModifiedEvents(
                                seen = seen,
                                expectedPath = eventPath,
                                expectedSources = setOf(first.source, second.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                } finally {
                    collector.cancel()
                }
                assertTrue(first.active)
                assertTrue(second.active)
            }
        }

    @Test
    fun samePathDifferentRecursiveRegistrationsCanCoexist() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-recursive-conflict-active")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val existing = watcher.watch(root, recursive = true)
                val overlapping = watcher.watch(root, recursive = false)
                assertTrue(existing.active)
                assertTrue(overlapping.active)
                assertEquals(setOf(existing.source, overlapping.source), watcher.registrations)
            }
        }

    @Test
    fun samePathDifferentRecursiveRegistrationsCanCoexistWhileOriginalRegistrationIsPending() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-recursive-conflict-pending")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val nativeWatchEntered = CountDownLatch(1)
            val releaseNativeWatch = CountDownLatch(1)

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, recursive, _ ->
                assertEquals(root.toString(), path)
                if (recursive) {
                    nativeWatchEntered.countDown()
                    releaseNativeWatch.await()
                }
                true
            }

            watcher.use {
                try {
                    val pendingRegistration =
                        async(Dispatchers.Default) {
                            watcher.watch(root, recursive = true)
                        }

                    try {
                        assertTrue(nativeWatchEntered.awaitWithTimeout(), "first watch never reached nativeWatch")

                        val overlappingRegistration =
                            async(Dispatchers.Default) {
                                watcher.watch(root, recursive = false)
                            }
                        assertFalse(
                            pendingRegistration.isCompleted,
                            "pending watch should remain blocked until native setup resolves",
                        )
                        assertTrue(watcher.registrations.isEmpty())
                        releaseNativeWatch.countDown()

                        val existing = pendingRegistration.await()
                        val overlapping = overlappingRegistration.await()
                        assertTrue(existing.active)
                        assertTrue(overlapping.active)
                        assertEquals(setOf(existing.source, overlapping.source), watcher.registrations)
                        existing.close()
                        overlapping.close()
                    } finally {
                        releaseNativeWatch.countDown()
                    }
                } finally {
                    NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
                }
            }
        }

    @Test
    fun samePathMixedRecursiveFanOutDirectChildEventToBothRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-mixed-recursive-direct-child")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val recursive = watcher.watch(root, recursive = true, name = "recursive")
                val nonRecursive = watcher.watch(root, recursive = false, name = "flat")
                val recursiveRegistrationId = watcher.registrationIdFor(recursive.source)
                val eventPath = root.resolve("child.txt")

                assertNotNull(recursiveRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Created>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Created && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        recursiveRegistrationId,
                        recursiveRegistrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingCreatedEvents(
                                seen = seen,
                                expectedPath = eventPath,
                                expectedSources = setOf(recursive.source, nonRecursive.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun samePathMixedRecursiveDeepEventOnlyReachesRecursiveRegistration() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-mixed-recursive-deep")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val recursive = watcher.watch(root, recursive = true, name = "recursive")
                val nonRecursive = watcher.watch(root, recursive = false, name = "flat")
                val recursiveRegistrationId = watcher.registrationIdFor(recursive.source)
                val eventPath = root.resolve("child").resolve("deep.txt")

                assertNotNull(recursiveRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Modified>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Modified && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        recursiveRegistrationId,
                        recursiveRegistrationId,
                        EVENT_KIND_MODIFIED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    withTimeout(5_000) {
                        while (true) {
                            val current = synchronized(seen) { seen.toList() }
                            if (current.any { it.source == recursive.source }) {
                                break
                            }
                            delay(25)
                        }
                    }

                    delay(150)
                    assertEquals(
                        listOf(
                            FsWatchEvent.Modified(
                                path = eventPath,
                                source = recursive.source,
                                isDirectory = false,
                                needsRescan = false,
                            ),
                        ),
                        synchronized(seen) { seen.toList() },
                    )
                    assertFalse(synchronized(seen) { seen.any { it.source == nonRecursive.source } })
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun samePathDifferentNamesWatcherLevelPathCallbackStillNeedsKotlinFanOut() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-shared-watch-fanout")
            val eventPath = root.resolve("shared.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, recursive = true, name = "alpha")
                val second = watcher.watch(root, recursive = true, name = "beta")
                val sharedRegistrationId = watcher.registrationIdFor(first.source)

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(second.source))
                assertNotNull(sharedRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Created>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Created && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        WATCHER_LEVEL_REGISTRATION_ID,
                        sharedRegistrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    withTimeout(5_000) {
                        while (
                            synchronized(seen) { seen.map { it.source }.toSet() } !=
                            setOf(first.source, second.source)
                        ) {
                            delay(25)
                        }
                    }
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun watcherLevelPathCallbackWithoutOriginIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-missing-origin")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                watcher.watch(root, recursive = true)
                val eventPath = root.resolve("ignored.txt")
                val rejectedPayload = AtomicReference<NativeFsWatchEventPayload?>()
                val acceptedPayload = AtomicReference<NativeFsWatchEventPayload?>()

                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {
                            acceptedPayload.set(payload)
                        }

                        override fun onRejectedEvent(payload: NativeFsWatchEventPayload) {
                            rejectedPayload.set(payload)
                        }
                    },
                )

                val maybeEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Created && it.path == eventPath
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    EVENT_KIND_CREATED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertNull(maybeEvent.await())
                assertNotNull(rejectedPayload.get())
                assertEquals(WATCHER_LEVEL_REGISTRATION_ID, rejectedPayload.get()!!.registrationId)
                assertNull(rejectedPayload.get()!!.originNativeRegistrationId)
                assertEquals(eventPath, rejectedPayload.get()!!.path)
                assertFalse(
                    acceptedPayload.get()?.registrationId == WATCHER_LEVEL_REGISTRATION_ID &&
                        acceptedPayload.get()?.originNativeRegistrationId == null &&
                        acceptedPayload.get()?.path == eventPath,
                )
                NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
            }
        }

    @Test
    fun movedCallbackWithoutSecondaryPathIsDroppedAtBridgeBoundary() =
        runBlocking {
            val watcherHandle = 9_001L
            val registrationId = nextNativeFsWatcherRegistrationId()
            val eventPath = Path.of("/tmp/from.txt")
            val rejectedPayload = AtomicReference<NativeFsWatchEventPayload?>()
            val acceptedPayload = AtomicReference<NativeFsWatchEventPayload?>()
            val emittedPayload = AtomicReference<NativeFsWatchEventPayload?>()

            NativeFsWatcherBridge.registerSink(
                watcherHandle,
                object : NativeFsWatcherSink {
                    override fun emitEvent(payload: NativeFsWatchEventPayload) {
                        emittedPayload.set(payload)
                    }

                    override fun emitError(payload: NativeFsWatchErrorPayload) = Unit
                },
            )
            NativeFsWatcherBridge.setCallbackObserverForTesting(
                watcherHandle,
                object : NativeFsWatcherCallbackObserver {
                    override fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {
                        acceptedPayload.set(payload)
                    }

                    override fun onRejectedEvent(payload: NativeFsWatchEventPayload) {
                        rejectedPayload.set(payload)
                    }
                },
            )

            try {
                NativeFsWatcherBridge.onNativeEvent(
                    watcherHandle,
                    registrationId,
                    101L,
                    EVENT_KIND_MOVED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertNotNull(rejectedPayload.get())
                assertEquals(registrationId, rejectedPayload.get()!!.registrationId)
                assertEquals(EVENT_KIND_MOVED, rejectedPayload.get()!!.eventKind)
                assertEquals(eventPath, rejectedPayload.get()!!.path)
                assertNull(rejectedPayload.get()!!.secondaryPath)
                assertNull(acceptedPayload.get())
                assertNull(emittedPayload.get())
            } finally {
                NativeFsWatcherBridge.setCallbackObserverForTesting(watcherHandle, null)
                NativeFsWatcherBridge.unregisterSink(watcherHandle)
            }
        }

    @Test
    fun movedEventFansOutPerMatchingSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-moved-fanout")
            val child = root.resolve("child")
            Files.createDirectories(child)
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parentRegistration = watcher.watch(root, recursive = true, name = "parent")
                val childRegistration = watcher.watch(child, recursive = true, name = "child")
                val registrationId = watcher.registrationIdFor(parentRegistration.source)
                val from = child.resolve("before.txt")
                val to = child.resolve("after.txt")

                assertEquals(registrationId, watcher.registrationIdFor(childRegistration.source))
                assertNotNull(registrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Moved>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Moved && event.from == from && event.to == to) {
                                seen.add(event)
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        201L,
                        EVENT_KIND_MOVED,
                        from.toString(),
                        to.toString(),
                        false,
                        0,
                    )

                    val events =
                        awaitMatchingEvents(seen) { snapshot ->
                            snapshot.takeIf {
                                it.map { moved -> moved.source }.toSet() ==
                                    setOf(parentRegistration.source, childRegistration.source)
                            }
                        }

                    assertEquals(2, events.size)
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun movedEventIsDeliveredWhenOnlyDestinationMatchesSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-moved-in")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root, recursive = true, name = "target")
                val registrationId = watcher.registrationIdFor(registration.source)
                val from = root.parent.resolve("outside-before.txt")
                val to = root.resolve("inside-after.txt")

                assertNotNull(registrationId)

                val event =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.first {
                            it is FsWatchEvent.Moved &&
                                it.source == registration.source &&
                                it.from == from &&
                                it.to == to
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        202L,
                        EVENT_KIND_MOVED,
                        from.toString(),
                        to.toString(),
                        false,
                        0,
                    )

                    assertEquals(
                        FsWatchEvent.Moved(
                            from = from,
                            to = to,
                            source = registration.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        event.await(),
                    )
                } finally {
                    event.cancel()
                }
            }
        }

    @Test
    fun movedEventIsDeliveredWhenOnlySourceMatchesSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-moved-out")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root, recursive = true, name = "origin")
                val registrationId = watcher.registrationIdFor(registration.source)
                val from = root.resolve("inside-before.txt")
                val to = root.parent.resolve("outside-after.txt")

                assertNotNull(registrationId)

                val event =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.first {
                            it is FsWatchEvent.Moved &&
                                it.source == registration.source &&
                                it.from == from &&
                                it.to == to
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        203L,
                        EVENT_KIND_MOVED,
                        from.toString(),
                        to.toString(),
                        false,
                        0,
                    )

                    assertEquals(
                        FsWatchEvent.Moved(
                            from = from,
                            to = to,
                            source = registration.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        event.await(),
                    )
                } finally {
                    event.cancel()
                }
            }
        }

    @Test
    fun movedEventOnlyProjectsMatchingEndpointForLogicalSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            // The symlink target has to be canonical for realTo below to be the path form a
            // backend would actually report: on macOS a temp dir is reached through /var -> /private/var.
            val realRoot = Files.createTempDirectory("fs-watcher-moved-endpoint-projection-real").toRealPath()
            val lexicalParent = Files.createTempDirectory("fs-watcher-moved-endpoint-projection-lexical")
            val lexicalRoot = lexicalParent.resolve("alias")
            try {
                Files.createSymbolicLink(lexicalRoot, realRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }
            val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = true)) as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(lexicalRoot, recursive = true, name = "alias")
                val registrationId = watcher.registrationIdFor(registration.source)
                val from = lexicalParent.resolve("outside-before.txt")
                val realTo = realRoot.resolve("inside-after.txt")
                val lexicalTo = lexicalRoot.resolve("inside-after.txt")

                assertNotNull(registrationId)

                val event =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Moved &&
                                    it.source == registration.source &&
                                    it.from == from &&
                                    it.to == lexicalTo
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        204L,
                        EVENT_KIND_MOVED,
                        from.toString(),
                        realTo.toString(),
                        false,
                        0,
                    )

                    assertEquals(
                        FsWatchEvent.Moved(
                            from = from,
                            to = lexicalTo,
                            source = registration.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        event.await(),
                    )
                } finally {
                    event.cancel()
                }
            }
        }

    @Test
    fun movedEventDoesNotFanOutAcrossIndependentInstallations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val rootA = Files.createTempDirectory("fs-watcher-moved-installation-a")
            val rootB = Files.createTempDirectory("fs-watcher-moved-installation-b")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val sourceA = watcher.watch(rootA, recursive = true, name = "a")
                val sourceB = watcher.watch(rootB, recursive = true, name = "b")
                val registrationIdA = watcher.registrationIdFor(sourceA.source)!!
                val registrationIdB = watcher.registrationIdFor(sourceB.source)!!
                val from = rootB.resolve("before.txt")
                val to = rootB.resolve("after.txt")

                assertNotEquals(registrationIdA, registrationIdB)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event -> seen += event }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationIdA,
                        registrationIdA,
                        EVENT_KIND_MOVED,
                        from.toString(),
                        to.toString(),
                        false,
                        0,
                    )

                    delay(250)

                    val movedForB =
                        synchronized(seen) {
                            seen.filterIsInstance<FsWatchEvent.Moved>().any { it.source == sourceB.source }
                        }

                    assertFalse(movedForB)
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun samePathSameRecursiveDifferentNamesFanOutPathScopedErrorsToBothRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-path-error-fanout")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, recursive = true, name = "alpha")
                val second = watcher.watch(root, recursive = true, name = "beta")
                val sharedRegistrationId = watcher.registrationIdFor(first.source)
                val eventPath = root.resolve("broken.txt")

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(second.source))
                assertNotNull(sharedRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchError>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.errors.collect { error ->
                            if (error.message == "shared path error") {
                                seen += error
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeError(
                        watcher.nativeHandle,
                        sharedRegistrationId,
                        sharedRegistrationId,
                        "shared path error",
                        true,
                        eventPath.toString(),
                    )

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingErrors(
                                seen = seen,
                                expectedMessage = "shared path error",
                                expectedSources = setOf(first.source, second.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                    assertTrue(snapshot.all { it.recoverable })
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun pathlessSharedWatchErrorFansOutToLiveLogicalRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-pathless-error-fanout")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, recursive = true, name = "alpha")
                val second = watcher.watch(root, recursive = true, name = "beta")
                val sharedRegistrationId = watcher.registrationIdFor(first.source)

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(second.source))
                assertNotNull(sharedRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchError>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.errors.collect { error ->
                            if (error.message == "shared pathless error") {
                                seen += error
                            }
                        }
                    }

                try {
                    val acceptedPayload = AtomicReference<NativeFsWatchErrorPayload?>()
                    NativeFsWatcherBridge.setCallbackObserverForTesting(
                        watcher.nativeHandle,
                        object : NativeFsWatcherCallbackObserver {
                            override fun onAcceptedError(payload: NativeFsWatchErrorPayload) {
                                acceptedPayload.set(payload)
                            }
                        },
                    )

                    val emitted =
                        NativeFsWatcherBridge.debugEmitPathlessErrorForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = sharedRegistrationId,
                            message = "shared pathless error",
                            recoverable = false,
                        )

                    assertTrue(emitted)

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingErrors(
                                seen = seen,
                                expectedMessage = "shared pathless error",
                                expectedSources = setOf(first.source, second.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                    assertTrue(snapshot.none { it.recoverable })
                    assertNotNull(acceptedPayload.get())
                    assertEquals(sharedRegistrationId, acceptedPayload.get()!!.registrationId)
                    assertEquals(sharedRegistrationId, acceptedPayload.get()!!.originNativeRegistrationId)
                    assertNull(acceptedPayload.get()!!.path)
                } finally {
                    NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
                    collector.cancel()
                }
            }
        }

    @Test
    fun parentAndChildOverlapFanOutToBothRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-parent-child-fanout")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested").resolve("file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val nested = watcher.watch(child, recursive = true, name = "child")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)

                assertNotNull(parentRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Created>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Created && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        parentRegistrationId,
                        parentRegistrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingCreatedEvents(
                                seen = seen,
                                expectedPath = eventPath,
                                expectedSources = setOf(parent.source, nested.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun overlapFanOutRemainsPerDistinctSourceAfterRoutingExtraction() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-routing-distinct-source")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested").resolve("file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val nested = watcher.watch(child, recursive = true, name = "child")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)

                assertNotNull(parentRegistrationId)

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Created>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event is FsWatchEvent.Created && event.path == eventPath) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        parentRegistrationId,
                        parentRegistrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    val snapshot =
                        withTimeout(5_000) {
                            awaitMatchingCreatedEvents(
                                seen = seen,
                                expectedPath = eventPath,
                                expectedSources = setOf(parent.source, nested.source),
                            )
                        }

                    assertEquals(2, snapshot.size)
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun overlappingOriginsDeliverEachLogicalSourceOnlyOncePerChange() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-dedup")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val nested = watcher.watch(child, recursive = true, name = "child")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!
                val childRegistrationId = watcher.registrationIdFor(nested.source)!!

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        parentRegistrationId,
                        parentRegistrationId,
                        EVENT_KIND_CREATED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )
                    if (childRegistrationId != parentRegistrationId) {
                        NativeFsWatcherBridge.onNativeEvent(
                            watcher.nativeHandle,
                            childRegistrationId,
                            childRegistrationId,
                            EVENT_KIND_CREATED,
                            eventPath.toString(),
                            null,
                            false,
                            0,
                        )
                    }

                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)

                    delay(150)

                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, parent.source)
                        },
                    )
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, nested.source)
                        },
                    )
                    assertEquals(
                        2,
                        synchronized(seen) {
                            seen.countEventsAtPath(EventFlowEventKind.CREATED, eventPath)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun watchingChildThenParentMigratesCoverageWithoutStaleChildOrigin() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-child-then-parent-migration")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val nested = watcher.watch(child, recursive = true, name = "child")
                val originalChildRegistrationId = watcher.registrationIdFor(nested.source)!!
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!
                val nestedRegistrationId = watcher.registrationIdFor(nested.source)!!

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                seen += event
                            }
                        }
                    }

                try {
                    assertEquals(parentRegistrationId, nestedRegistrationId)

                    val staleChildAccepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = originalChildRegistrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = eventPath,
                            isDirectory = 0,
                        )

                    val survivorAccepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = parentRegistrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = eventPath,
                            isDirectory = 0,
                        )

                    assertFalse(staleChildAccepted)
                    assertTrue(survivorAccepted)

                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                    delay(150)
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, parent.source)
                        },
                    )
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, nested.source)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun watchingParentThenChildReusesBroaderCoverageWithoutStaleChildOrigin() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-parent-then-child-reuse")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!
                val nested = watcher.watch(child, recursive = true, name = "child")

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                seen += event
                            }
                        }
                    }

                try {
                    assertEquals(parentRegistrationId, watcher.registrationIdFor(nested.source))

                    val accepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = parentRegistrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = eventPath,
                            isDirectory = 0,
                        )

                    assertTrue(accepted)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                    delay(150)
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, parent.source)
                        },
                    )
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, nested.source)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun deeperMatchingSourceIsDeliveredBeforeParentSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-deeper-before-parent")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val nested = watcher.watch(child, recursive = true, name = "child")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                seen += event
                            }
                        }
                    }

                try {
                    val accepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = parentRegistrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = eventPath,
                            isDirectory = 0,
                        )

                    assertTrue(accepted)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                    delay(150)

                    assertEquals(
                        listOf(nested.source, parent.source),
                        synchronized(seen) {
                            seen.sourcesForPath(EventFlowEventKind.CREATED, eventPath)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun sourceOrderRemainsStableAfterCoverageMigration() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-migration-order")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val nested = watcher.watch(child, recursive = true, name = "child")
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                seen += event
                            }
                        }
                    }

                try {
                    val accepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = parentRegistrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = eventPath,
                            isDirectory = 0,
                        )

                    assertTrue(accepted)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                    delay(150)

                    assertEquals(
                        listOf(nested.source, parent.source),
                        synchronized(seen) {
                            seen.sourcesForPath(EventFlowEventKind.CREATED, eventPath)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun samePathRecursiveAndNonRecursiveShareRecursiveCoverageButKeepLogicalFiltering() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-same-path-recursive-non-recursive")
            val directPath = root.resolve("child.txt")
            val deepPath = root.resolve("nested/deep.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val recursive = watcher.watch(root, recursive = true, name = "recursive")
                val nonRecursive = watcher.watch(root, recursive = false, name = "flat")
                val recursiveRegistrationId = watcher.registrationIdFor(recursive.source)!!

                assertEquals(recursiveRegistrationId, watcher.registrationIdFor(nonRecursive.source))

                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            if (
                                event.matchesPathAndKind(EventFlowEventKind.CREATED, directPath) ||
                                event.matchesPathAndKind(EventFlowEventKind.CREATED, deepPath)
                            ) {
                                seen += event
                            }
                        }
                    }

                try {
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = recursiveRegistrationId,
                        eventKind = EVENT_KIND_CREATED,
                        path = directPath,
                        isDirectory = 0,
                    )
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = recursiveRegistrationId,
                        eventKind = EVENT_KIND_CREATED,
                        path = deepPath,
                        isDirectory = 0,
                    )

                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, directPath, recursive.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, directPath, nonRecursive.source)
                    awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, deepPath, recursive.source)
                    delay(150)

                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, directPath, recursive.source)
                        },
                    )
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, directPath, nonRecursive.source)
                        },
                    )
                    assertEquals(
                        1,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, deepPath, recursive.source)
                        },
                    )
                    assertEquals(
                        0,
                        synchronized(seen) {
                            seen.countEventTuple(EventFlowEventKind.CREATED, deepPath, nonRecursive.source)
                        },
                    )
                } finally {
                    collector.cancel()
                }
            }
        }

    @Test
    fun lateCallbackFromClosedLogicalRegistrationIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-closed-logical-late-callback")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val closedRegistration = watcher.watch(root, recursive = true, name = "closed")
                val liveSibling = watcher.watch(root, recursive = true, name = "live")
                val sharedRegistrationId = watcher.registrationIdFor(closedRegistration.source)
                val eventPath = root.resolve("late-logical.txt")

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(liveSibling.source))
                assertNotNull(sharedRegistrationId)

                closedRegistration.close()

                val survivingEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == liveSibling.source
                            }
                        }
                    }
                val droppedClosedEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == closedRegistration.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    sharedRegistrationId,
                    sharedRegistrationId,
                    EVENT_KIND_MODIFIED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Modified(
                        path = eventPath,
                        source = liveSibling.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    survivingEvent.await(),
                )
                assertNull(droppedClosedEvent.await())
                assertFalse(closedRegistration.active)
                assertTrue(liveSibling.active)
            }
        }

    @Test
    fun lateCallbackFromClosedNativeInstallationIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-closed-native-late-callback")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root, recursive = true, name = "solo")
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val eventPath = root.resolve("late-native.txt")

                registration.close()

                val maybeEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Created &&
                                    it.path == eventPath &&
                                    it.source == registration.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    registrationId,
                    registrationId,
                    EVENT_KIND_CREATED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertNull(maybeEvent.await())
                assertFalse(registration.active)
            }
        }

    @Test
    fun staleParentOriginOverlappingLiveChildRegistrationEmitsNothing() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-stale-parent-origin")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested").resolve("ignored.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val liveChild = watcher.watch(child, recursive = true, name = "child")
                val parentRegistrationId = watcher.registrationIdFor(parent.source)!!
                val acceptedPayloads =
                    java.util.Collections.synchronizedList(mutableListOf<NativeFsWatchEventPayload>())

                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {
                            acceptedPayloads += payload
                        }
                    },
                )

                parent.close()

                val childEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Created &&
                                    it.path == eventPath &&
                                    it.source == liveChild.source
                            }
                        }
                    }
                val droppedParentEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Created &&
                                    it.path == eventPath &&
                                    it.source == parent.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    parentRegistrationId,
                    EVENT_KIND_CREATED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Created(
                        path = eventPath,
                        source = liveChild.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    childEvent.await(),
                )
                assertNull(droppedParentEvent.await())
                assertTrue(
                    synchronized(acceptedPayloads) {
                        acceptedPayloads.any { payload ->
                            payload.registrationId == WATCHER_LEVEL_REGISTRATION_ID &&
                                payload.originNativeRegistrationId == parentRegistrationId &&
                                payload.path == eventPath
                        }
                    },
                )
                assertFalse(parent.active)
                assertTrue(liveChild.active)
                NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
            }
        }

    @Test
    fun closingParentRegistrationDoesNotBreakChildRegistrationRealSource() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-close-parent-keep-child")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested").resolve("live.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val parent = watcher.watch(root, recursive = true, name = "parent")
                val liveChild = watcher.watch(child, recursive = true, name = "child")
                val childRegistrationId = watcher.registrationIdFor(liveChild.source)!!

                parent.close()

                val childEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == liveChild.source
                            }
                        }
                    }
                val droppedParentEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == parent.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    childRegistrationId,
                    childRegistrationId,
                    EVENT_KIND_MODIFIED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Modified(
                        path = eventPath,
                        source = liveChild.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    childEvent.await(),
                )
                assertNull(droppedParentEvent.await())
                assertFalse(parent.active)
                assertTrue(liveChild.active)
            }
        }

    @Test
    fun closingOneSharedLogicalRegistrationStillLetsSiblingSharedRegistrationReceiveEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-close-shared-logical")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(root, recursive = true, name = "alpha")
                val sibling = watcher.watch(root, recursive = true, name = "beta")
                val sharedRegistrationId = watcher.registrationIdFor(first.source)
                val eventPath = root.resolve("still-live.txt")

                assertEquals(sharedRegistrationId, watcher.registrationIdFor(sibling.source))
                assertNotNull(sharedRegistrationId)

                first.close()

                val survivingEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first {
                                it is FsWatchEvent.Modified &&
                                    it.path == eventPath &&
                                    it.source == sibling.source
                            }
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    sharedRegistrationId,
                    sharedRegistrationId,
                    EVENT_KIND_MODIFIED,
                    eventPath.toString(),
                    null,
                    false,
                    0,
                )

                assertEquals(
                    FsWatchEvent.Modified(
                        path = eventPath,
                        source = sibling.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    survivingEvent.await(),
                )
                assertFalse(first.active)
                assertTrue(sibling.active)
            }
        }

    @Test
    fun canonicalAndSymlinkRegistrationsKeepPathScopedEventBoundToOriginInstallation() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-alias-fanout-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-alias-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = true)) as NativeBackedFsWatcher

                watcher.use {
                    val canonical = watcher.watch(canonicalRoot, recursive = true, name = "canonical")
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val canonicalRegistrationId = watcher.registrationIdFor(canonical.source)
                    val canonicalPath = canonicalRoot.resolve("alpha.txt")
                    val observedRealPath = canonicalRoot.toRealPath().resolve("alpha.txt")
                    val symlinkPath = symlinkRoot.resolve("alpha.txt")

                    assertNotNull(canonicalRegistrationId)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent.Created>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { event ->
                                if (event is FsWatchEvent.Created && event.path in setOf(canonicalPath, symlinkPath)) {
                                    seen += event
                                }
                            }
                        }

                    try {
                        val emitted =
                            NativeFsWatcherBridge.debugEmitPathEventForTesting(
                                watcherHandle = watcher.nativeHandle,
                                originNativeRegistrationId = canonicalRegistrationId,
                                eventKind = EVENT_KIND_CREATED,
                                path = observedRealPath,
                                isDirectory = 0,
                            )

                        assertTrue(emitted)

                        awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, canonicalPath, canonical.source)
                        delay(150)

                        assertEquals(
                            1,
                            synchronized(seen) {
                                seen.countEventTuple(EventFlowEventKind.CREATED, canonicalPath, canonical.source)
                            },
                        )
                        assertEquals(
                            0,
                            synchronized(seen) {
                                seen.countEventTuple(EventFlowEventKind.CREATED, symlinkPath, symlink.source)
                            },
                        )
                    } finally {
                        collector.cancel()
                    }
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun canonicalAndSymlinkRegistrationsKeepPathScopedErrorsBoundToOriginInstallation() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-alias-error-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-alias-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = true)) as NativeBackedFsWatcher

                watcher.use {
                    val canonical = watcher.watch(canonicalRoot, recursive = true, name = "canonical")
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val canonicalRegistrationId = watcher.registrationIdFor(canonical.source)
                    val observedRealPath = canonicalRoot.toRealPath().resolve("alpha.txt")

                    assertNotNull(canonicalRegistrationId)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchError>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.errors.collect { error ->
                                if (
                                    error.message == "alias path error" &&
                                    error.source in setOf(canonical.source, symlink.source)
                                ) {
                                    seen += error
                                }
                            }
                        }

                    try {
                        NativeFsWatcherBridge.onNativeError(
                            watcher.nativeHandle,
                            canonicalRegistrationId,
                            canonicalRegistrationId,
                            "alias path error",
                            true,
                            observedRealPath.toString(),
                        )

                        withTimeout(5_000) {
                            while (true) {
                                val snapshot = synchronized(seen) { seen.toList() }
                                if (snapshot.any { it.source == canonical.source }) {
                                    break
                                }
                                delay(25)
                            }
                        }
                        delay(150)

                        assertEquals(
                            listOf(
                                FsWatchError(
                                    message = "alias path error",
                                    source = canonical.source,
                                    recoverable = true,
                                    cause = null,
                                ),
                            ),
                            synchronized(seen) { seen.toList() },
                        )
                    } finally {
                        collector.cancel()
                    }
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun symlinkRegistrationDoesNotRemapResolvedPathScopedEventWhenFollowSymlinksDisabled() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-no-follow-event-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-alias-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = false)) as NativeBackedFsWatcher

                watcher.use {
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val symlinkRegistrationId = watcher.registrationIdFor(symlink.source)
                    val observedRealPath = canonicalRoot.toRealPath().resolve("alpha.txt")
                    val lexicalPath = symlinkRoot.resolve("alpha.txt")

                    assertNotNull(symlinkRegistrationId)

                    val maybeEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(300) {
                                watcher.events.first {
                                    it is FsWatchEvent.Created && it.path == lexicalPath
                                }
                            }
                        }

                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        symlinkRegistrationId,
                        symlinkRegistrationId,
                        EVENT_KIND_CREATED,
                        observedRealPath.toString(),
                        null,
                        false,
                        0,
                    )

                    assertNull(maybeEvent.await())
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun followSymlinksFalseStillRejectsResolvedRootRemapAfterRoutingExtraction() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-no-follow-routing-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-alias-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = false)) as NativeBackedFsWatcher

                watcher.use {
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val symlinkRegistrationId = watcher.registrationIdFor(symlink.source)
                    val observedRealPath = canonicalRoot.toRealPath().resolve("alpha.txt")
                    val lexicalPath = symlinkRoot.resolve("alpha.txt")

                    assertNotNull(symlinkRegistrationId)

                    val maybeEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(300) {
                                watcher.events.first {
                                    it is FsWatchEvent.Created && it.path == lexicalPath
                                }
                            }
                        }

                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        symlinkRegistrationId,
                        symlinkRegistrationId,
                        EVENT_KIND_CREATED,
                        observedRealPath.toString(),
                        null,
                        false,
                        0,
                    )

                    assertNull(maybeEvent.await())
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun symlinkRegistrationDoesNotRouteResolvedPathScopedErrorWhenFollowSymlinksDisabled() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-no-follow-error-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-alias-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                val watcher = FsWatchers.create(FsWatcherConfig(followSymlinks = false)) as NativeBackedFsWatcher

                watcher.use {
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val symlinkRegistrationId = watcher.registrationIdFor(symlink.source)
                    val observedRealPath = canonicalRoot.toRealPath().resolve("alpha.txt")

                    assertNotNull(symlinkRegistrationId)

                    val maybeError =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(300) {
                                watcher.errors.first { it.message == "resolved path error" }
                            }
                        }

                    NativeFsWatcherBridge.onNativeError(
                        watcher.nativeHandle,
                        symlinkRegistrationId,
                        symlinkRegistrationId,
                        "resolved path error",
                        true,
                        observedRealPath.toString(),
                    )

                    assertNull(maybeError.await())
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun stalePathScopedErrorAfterUnwatchIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-stale-path-error")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val stalePath = root.resolve("late-error.txt")
                val acceptedPayload = AtomicReference<NativeFsWatchErrorPayload?>()

                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedError(payload: NativeFsWatchErrorPayload) {
                            acceptedPayload.set(payload)
                        }
                    },
                )

                val maybeError =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.errors.first { it.message == "stale path error" }
                        }
                    }

                registration.close()

                val emitted =
                    NativeFsWatcherBridge.debugEmitPathErrorForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = registrationId,
                        message = "stale path error",
                        recoverable = true,
                        path = stalePath,
                    )

                assertFalse(emitted)
                assertNull(maybeError.await())
                assertNull(acceptedPayload.get())
                NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
            }
        }

    @Test
    fun stalePathlessSharedWatchErrorAfterLastUnwatchIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val sharedRoot = Files.createTempDirectory("fs-watcher-stale-pathless-shared-error")
            val unrelatedRoot = Files.createTempDirectory("fs-watcher-stale-pathless-shared-error-live")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val first = watcher.watch(sharedRoot, recursive = true, name = "alpha")
                val second = watcher.watch(sharedRoot, recursive = true, name = "beta")
                val survivor = watcher.watch(unrelatedRoot, recursive = true, name = "survivor")
                val staleRegistrationId = watcher.registrationIdFor(first.source)!!
                val acceptedPayloads =
                    java.util.Collections.synchronizedList(mutableListOf<NativeFsWatchErrorPayload>())

                assertEquals(staleRegistrationId, watcher.registrationIdFor(second.source))

                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedError(payload: NativeFsWatchErrorPayload) {
                            acceptedPayloads += payload
                        }
                    },
                )

                val maybeError =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(300) {
                            watcher.errors.first { it.message == "stale pathless shared error" }
                        }
                    }

                first.close()
                second.close()

                NativeFsWatcherBridge.onNativeError(
                    watcher.nativeHandle,
                    staleRegistrationId,
                    staleRegistrationId,
                    "stale pathless shared error",
                    false,
                    null,
                )

                assertNull(maybeError.await())
                assertTrue(
                    synchronized(acceptedPayloads) {
                        acceptedPayloads.any { payload ->
                            payload.registrationId == staleRegistrationId &&
                                payload.originNativeRegistrationId == staleRegistrationId &&
                                payload.path == null &&
                                payload.message == "stale pathless shared error"
                        }
                    },
                )
                assertFalse(first.active)
                assertFalse(second.active)
                assertTrue(survivor.active)
                NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
            }
        }

    @Test
    fun overlappingRecursiveParentAndChildRegistrationsCanCoexist() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-parent-child")
            val child = Files.createDirectories(root.resolve("child"))

            FsWatchers.create().use { watcher ->
                val parent = watcher.watch(root, recursive = true)
                val nested = watcher.watch(child, recursive = true)
                assertTrue(parent.active)
                assertTrue(nested.active)
                assertEquals(setOf(parent.source, nested.source), watcher.registrations)
            }
        }

    @Test
    fun nonRecursiveParentAndDirectChildRecursiveRegistrationsCanCoexist() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-mixed")
            val child = Files.createDirectories(root.resolve("child"))

            FsWatchers.create().use { watcher ->
                val parent = watcher.watch(root, recursive = false)
                val nested = watcher.watch(child, recursive = true)
                assertTrue(parent.active)
                assertTrue(nested.active)
                assertEquals(setOf(parent.source, nested.source), watcher.registrations)
            }
        }

    @Test
    fun recursiveParentAndDirectChildNonRecursiveRegistrationsCanCoexist() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overlap-mixed-inverse")
            val child = Files.createDirectories(root.resolve("child"))

            FsWatchers.create().use { watcher ->
                val parent = watcher.watch(root, recursive = true)
                val nested = watcher.watch(child, recursive = false)
                assertTrue(parent.active)
                assertTrue(nested.active)
                assertEquals(setOf(parent.source, nested.source), watcher.registrations)
            }
        }

    @Test
    fun nonRecursiveParentAndDirectChildRegistrationsCanCoexist() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-non-recursive-direct-child-overlap")
            val child = Files.createDirectories(root.resolve("child"))

            FsWatchers.create().use { watcher ->
                val parent = watcher.watch(root, recursive = false)
                val nested = watcher.watch(child, recursive = false)
                assertTrue(parent.active)
                assertTrue(nested.active)
                assertEquals(setOf(parent.source, nested.source), watcher.registrations)
            }
        }

    @Test
    fun nonRecursiveParentAndDeepGrandchildAreAllowedWhenCoverageDoesNotOverlap() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-non-overlap-allowed")
            Files.createDirectories(root.resolve("child"))
            val grandchild = Files.createDirectories(root.resolve("child/grandchild"))

            FsWatchers.create().use { watcher ->
                val parent = watcher.watch(root, recursive = false)
                val nested = watcher.watch(grandchild, recursive = true)
                nested.close()
                parent.close()
            }
        }

    @Test
    fun symlinkRootAndCanonicalRootRegistrationsCanCoexistWhenSupported() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-symlink-overlap-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-link")

            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            try {
                FsWatchers.create(FsWatcherConfig(followSymlinks = true)).use { watcher ->
                    val symlinkRegistration = watcher.watch(symlinkRoot, recursive = true)
                    val canonicalRegistration = watcher.watch(canonicalRoot, recursive = true)
                    assertTrue(symlinkRegistration.active)
                    assertTrue(canonicalRegistration.active)
                    assertEquals(
                        setOf(symlinkRegistration.source, canonicalRegistration.source),
                        watcher.registrations,
                    )
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursivelyForEventFlowTest(canonicalRoot)
            }
        }

    @Test
    fun droppedEventsStillSurfaceWatcherLevelOverflowUnderBackpressure() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-overflow-backpressure")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val blockerReleased = CountDownLatch(1)
                val blockerObservedFirstEvent = CountDownLatch(1)
                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())

                val blockingCollector =
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect {
                            seen += it
                            blockerObservedFirstEvent.countDown()
                            blockerReleased.await()
                        }
                    }

                NativeFsWatcherBridge.onNativeEvent(
                    watcher.nativeHandle,
                    registrationId,
                    registrationId,
                    EVENT_KIND_CREATED,
                    root.resolve("seed.txt").toString(),
                    null,
                    false,
                    0,
                )
                assertTrue(
                    blockerObservedFirstEvent.awaitWithTimeout(),
                    "blocking collector never observed the seed event",
                )

                repeat(65) { index ->
                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        registrationId,
                        registrationId,
                        EVENT_KIND_CREATED,
                        root.resolve("buffer-$index.txt").toString(),
                        null,
                        false,
                        0,
                    )
                }

                blockerReleased.countDown()

                withTimeout(5_000) {
                    while (true) {
                        val snapshot = synchronized(seen) { seen.toList() }
                        if (snapshot.any { it is FsWatchEvent.Overflow && it.source == null }) {
                            break
                        }
                        delay(25)
                    }
                }

                blockingCollector.cancel()
            }
        }

    @Test
    fun smallEventBufferStillEscalatesToWatcherOverflow() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-small-event-buffer-overflow")
            val watcher =
                FsWatchers.create(FsWatcherConfig(eventBufferCapacity = 1)) as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val collectorReleased = CountDownLatch(1)
                val collectorObservedSeed = CountDownLatch(1)
                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())

                val blockingCollector =
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect {
                            seen += it
                            collectorObservedSeed.countDown()
                            collectorReleased.await()
                        }
                    }

                assertTrue(
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = registrationId,
                        eventKind = EVENT_KIND_CREATED,
                        path = root.resolve("seed.txt"),
                        isDirectory = 0,
                    ),
                )
                assertTrue(
                    collectorObservedSeed.awaitWithTimeout(),
                    "blocking collector never observed the seed event",
                )

                repeat(3) { index ->
                    assertTrue(
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = registrationId,
                            eventKind = EVENT_KIND_CREATED,
                            path = root.resolve("burst-$index.txt"),
                            isDirectory = 0,
                        ),
                    )
                }

                collectorReleased.countDown()

                withTimeout(5_000) {
                    while (true) {
                        val snapshot = synchronized(seen) { seen.toList() }
                        if (snapshot.any { it is FsWatchEvent.Overflow && it.source == null }) {
                            break
                        }
                        delay(25)
                    }
                }

                blockingCollector.cancel()
            }
        }

    @Test
    fun smallErrorBufferStillEscalatesToWatcherOverflow() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-small-error-buffer-overflow")
            val watcher =
                FsWatchers.create(FsWatcherConfig(errorBufferCapacity = 1)) as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val errorCollectorReleased = CountDownLatch(1)
                val errorCollectorObservedSeed = CountDownLatch(1)
                val errorsSeen = java.util.Collections.synchronizedList(mutableListOf<FsWatchError>())
                val eventsSeen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())

                val blockingErrorCollector =
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        watcher.errors.collect { error ->
                            errorsSeen += error
                            errorCollectorObservedSeed.countDown()
                            errorCollectorReleased.await()
                        }
                    }
                val eventCollector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { event ->
                            eventsSeen += event
                        }
                    }

                assertTrue(
                    NativeFsWatcherBridge.debugEmitPathlessErrorForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = registrationId,
                        message = "seed error",
                        recoverable = true,
                    ),
                )
                assertTrue(
                    errorCollectorObservedSeed.awaitWithTimeout(),
                    "blocking collector never observed the seed error",
                )

                repeat(3) { index ->
                    assertTrue(
                        NativeFsWatcherBridge.debugEmitPathlessErrorForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = registrationId,
                            message = "burst error $index",
                            recoverable = true,
                        ),
                    )
                }

                errorCollectorReleased.countDown()

                withTimeout(5_000) {
                    while (true) {
                        val snapshot = synchronized(eventsSeen) { eventsSeen.toList() }
                        if (snapshot.any { it is FsWatchEvent.Overflow && it.source == null }) {
                            break
                        }
                        delay(25)
                    }
                }

                blockingErrorCollector.cancel()
                eventCollector.cancel()
            }
        }

    @Test
    fun nativePathMatchingAcceptsOriginalLexicalRootsAndRemapsToOriginalShape() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = Files.createTempDirectory("fs-watcher-lexical-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-link")
            try {
                Files.deleteIfExists(symlinkRoot)
                Files.createSymbolicLink(symlinkRoot, canonicalRoot)
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }

            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(symlinkRoot)
                val lexicalChild = symlinkRoot.resolve("alpha.txt")
                val maybeEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            watcher.events.first { it is FsWatchEvent.Created && it.path == lexicalChild }
                        }
                    }

                val emitted =
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = watcher.registrationIdFor(registration.source)!!,
                        eventKind = EVENT_KIND_CREATED,
                        path = lexicalChild,
                        isDirectory = 0,
                    )

                assertTrue(emitted)
                assertEquals(
                    FsWatchEvent.Created(
                        path = lexicalChild,
                        source = registration.source,
                        isDirectory = false,
                        needsRescan = false,
                    ),
                    maybeEvent.await(),
                )
            }
        }

    @Test
    fun duplicateWatchWaitsForPendingNativeRegistrationAndFailsCleanly() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-pending-duplicate-watch")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val callCount = AtomicInteger(0)
            val nativeWatchEntered = CountDownLatch(1)
            val releaseNativeWatch = CountDownLatch(1)
            val duplicateStarted = CountDownLatch(1)

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, _ ->
                assertEquals(root.toString(), path)
                callCount.incrementAndGet()
                nativeWatchEntered.countDown()
                releaseNativeWatch.await()
                false
            }

            watcher.use {
                try {
                    val firstAttempt =
                        async(Dispatchers.Default) {
                            assertFailsWith<FsWatchException> {
                                watcher.watch(root)
                            }
                        }

                    assertTrue(nativeWatchEntered.awaitWithTimeout(), "first watch never reached nativeWatch")

                    val secondAttempt =
                        async(Dispatchers.Default) {
                            duplicateStarted.countDown()
                            assertFailsWith<FsWatchException> {
                                watcher.watch(root)
                            }
                        }

                    try {
                        assertTrue(duplicateStarted.awaitWithTimeout(), "duplicate watch did not start")
                        delay(150)
                        assertFalse(secondAttempt.isCompleted, "duplicate watch returned before native setup resolved")
                    } finally {
                        releaseNativeWatch.countDown()
                    }

                    firstAttempt.await()
                    secondAttempt.await()

                    assertEquals(1, callCount.get())
                    assertTrue(watcher.registrations.isEmpty())
                } finally {
                    NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
                }
            }
        }

    @Test
    fun debugMovedProbeRequiresBothEndpoints() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-debug-moved-shape")
            val from = root.resolve("before.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root, recursive = true)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                val emitted = AtomicReference<NativeFsWatchEventPayload?>()

                NativeFsWatcherBridge.setCallbackObserverForTesting(
                    watcher.nativeHandle,
                    object : NativeFsWatcherCallbackObserver {
                        override fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {
                            emitted.set(payload)
                        }

                        override fun onRejectedEvent(payload: NativeFsWatchEventPayload) = Unit
                    },
                )

                try {
                    val accepted =
                        NativeFsWatcherBridge.debugEmitPathEventForTesting(
                            watcherHandle = watcher.nativeHandle,
                            originNativeRegistrationId = registrationId,
                            eventKind = EVENT_KIND_MOVED,
                            path = from,
                            secondaryPath = null,
                            isDirectory = 0,
                        )

                    assertFalse(accepted)
                    assertNull(emitted.get())
                } finally {
                    NativeFsWatcherBridge.setCallbackObserverForTesting(watcher.nativeHandle, null)
                }
            }
        }

    @Test
    fun staleMovedDebugProbeAfterUnwatchIsDropped() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-stale-debug-moved")
            val from = root.resolve("before.txt")
            val to = root.resolve("after.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher

            watcher.use {
                val registration = watcher.watch(root, recursive = true)
                val registrationId = watcher.registrationIdFor(registration.source)!!
                registration.close()

                val accepted =
                    NativeFsWatcherBridge.debugEmitPathEventForTesting(
                        watcherHandle = watcher.nativeHandle,
                        originNativeRegistrationId = registrationId,
                        eventKind = EVENT_KIND_MOVED,
                        path = from,
                        secondaryPath = to,
                        isDirectory = 0,
                    )

                assertFalse(accepted)
            }
        }

    @Test
    fun pendingChildThenParentBroaderCoverageResolvesToSingleSurvivingOrigin() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-pending-child-then-parent")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val nativeWatchCallCount = AtomicInteger(0)
            val childWatchEntered = CountDownLatch(1)
            val releaseChildWatch = CountDownLatch(1)
            val observedRegistrationIds = java.util.Collections.synchronizedList(mutableListOf<Long>())

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, registrationId, path, recursive, name ->
                nativeWatchCallCount.incrementAndGet()
                observedRegistrationIds += registrationId
                if (path == child.toString() && recursive && name == "child") {
                    childWatchEntered.countDown()
                    releaseChildWatch.await()
                }
                true
            }

            watcher.use {
                try {
                    val childAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(child, recursive = true, name = "child")
                        }

                    assertTrue(childWatchEntered.awaitWithTimeout(), "child watch never reached nativeWatch")

                    val parentAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(root, recursive = true, name = "parent")
                        }

                    delay(150)
                    assertFalse(
                        parentAttempt.isCompleted,
                        "broader parent watch should not complete before child pending resolves",
                    )

                    releaseChildWatch.countDown()

                    val nested = childAttempt.await()
                    val parent = withTimeout(5_000) { parentAttempt.await() }
                    val parentRegistrationId = watcher.registrationIdFor(parent.source)!!
                    val nestedRegistrationId = watcher.registrationIdFor(nested.source)!!
                    val originalChildRegistrationId =
                        synchronized(observedRegistrationIds) {
                            observedRegistrationIds.first()
                        }

                    assertEquals(2, nativeWatchCallCount.get())
                    assertEquals(parentRegistrationId, nestedRegistrationId)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { event ->
                                if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                    seen += event
                                }
                            }
                        }

                    try {
                        NativeFsWatcherBridge.onNativeEvent(
                            watcher.nativeHandle,
                            originalChildRegistrationId,
                            originalChildRegistrationId,
                            EVENT_KIND_CREATED,
                            eventPath.toString(),
                            null,
                            false,
                            0,
                        )
                        NativeFsWatcherBridge.onNativeEvent(
                            watcher.nativeHandle,
                            parentRegistrationId,
                            parentRegistrationId,
                            EVENT_KIND_CREATED,
                            eventPath.toString(),
                            null,
                            false,
                            0,
                        )

                        awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                        awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                        delay(150)
                        assertEquals(
                            1,
                            synchronized(seen) {
                                seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, parent.source)
                            },
                        )
                        assertEquals(
                            1,
                            synchronized(seen) {
                                seen.countEventTuple(EventFlowEventKind.CREATED, eventPath, nested.source)
                            },
                        )
                    } finally {
                        collector.cancel()
                    }
                } finally {
                    releaseChildWatch.countDown()
                    NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
                }
            }
        }

    @Test
    fun pendingParentThenChildNarrowerCoverageWaitsForBroaderOwner() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-pending-parent-then-child")
            val child = Files.createDirectories(root.resolve("child"))
            val eventPath = child.resolve("nested/file.txt")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val nativeWatchCallCount = AtomicInteger(0)
            val parentWatchEntered = CountDownLatch(1)
            val releaseParentWatch = CountDownLatch(1)
            val childWaiterEntered = CountDownLatch(1)

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, recursive, name ->
                nativeWatchCallCount.incrementAndGet()
                if (path == root.toString() && recursive && name == "parent") {
                    parentWatchEntered.countDown()
                    releaseParentWatch.await()
                }
                true
            }

            watcher.use {
                try {
                    watcher.pendingRegistrationEnteredHookForTesting = {
                        childWaiterEntered.countDown()
                    }
                    val parentAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(root, recursive = true, name = "parent")
                        }

                    assertTrue(parentWatchEntered.awaitWithTimeout(), "parent watch never reached nativeWatch")

                    val childAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(child, recursive = true, name = "child")
                        }

                    assertTrue(childWaiterEntered.awaitWithTimeout(), "narrower child watch never entered pending wait")
                    assertFalse(childAttempt.isCompleted, "narrower child watch should wait for broader pending parent")

                    releaseParentWatch.countDown()

                    val parent = parentAttempt.await()
                    val nested = withTimeout(5_000) { childAttempt.await() }
                    val parentRegistrationId = watcher.registrationIdFor(parent.source)!!

                    assertEquals(1, nativeWatchCallCount.get())
                    assertEquals(parentRegistrationId, watcher.registrationIdFor(nested.source))

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { event ->
                                if (event.matchesPathAndKind(EventFlowEventKind.CREATED, eventPath)) {
                                    seen += event
                                }
                            }
                        }

                    try {
                        NativeFsWatcherBridge.onNativeEvent(
                            watcher.nativeHandle,
                            parentRegistrationId,
                            parentRegistrationId,
                            EVENT_KIND_CREATED,
                            eventPath.toString(),
                            null,
                            false,
                            0,
                        )
                        awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, parent.source)
                        awaitAtLeastOneEventTuple(seen, EventFlowEventKind.CREATED, eventPath, nested.source)
                    } finally {
                        collector.cancel()
                    }
                } finally {
                    watcher.pendingRegistrationEnteredHookForTesting = null
                    releaseParentWatch.countDown()
                    NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
                }
            }
        }

    @Test
    fun watcherCloseFailsPendingDuplicateWaitersPromptly() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-close-pending-duplicate")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val nativeWatchEntered = CountDownLatch(1)
            val duplicateStarted = CountDownLatch(1)
            val releaseNativeWatch = CountDownLatch(1)

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, _ ->
                assertEquals(root.toString(), path)
                nativeWatchEntered.countDown()
                releaseNativeWatch.await()
                true
            }

            try {
                val firstAttempt =
                    async(Dispatchers.Default) {
                        assertFailsWith<FsWatchException> {
                            watcher.watch(root)
                        }
                    }

                assertTrue(nativeWatchEntered.awaitWithTimeout(), "first watch never reached nativeWatch")

                val duplicateAttempt =
                    async(Dispatchers.Default) {
                        duplicateStarted.countDown()
                        assertFailsWith<FsWatchException> {
                            watcher.watch(root)
                        }
                    }

                try {
                    assertTrue(duplicateStarted.awaitWithTimeout(), "duplicate watch did not start")
                    delay(150)
                    assertFalse(duplicateAttempt.isCompleted, "duplicate watch returned before watcher.close()")

                    watcher.close()

                    val duplicateFailure =
                        withTimeout(2_000) {
                            duplicateAttempt.await()
                        }
                    assertEquals(
                        "Watcher closed while registrations were still pending",
                        duplicateFailure.message,
                    )

                    assertFalse(firstAttempt.isCompleted, "first watch should still wait for native setup to unwind")

                    releaseNativeWatch.countDown()

                    val firstFailure =
                        withTimeout(2_000) {
                            firstAttempt.await()
                        }
                    assertEquals(
                        "Watcher closed while registering path: $root",
                        firstFailure.message,
                    )
                } finally {
                    releaseNativeWatch.countDown()
                }
            } finally {
                NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
            }
        }

    @Test
    fun pendingSamePathSameRecursiveWaiterReacquiresAfterOriginalLogicalOwnerCloses() {
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = Files.createTempDirectory("fs-watcher-pending-alias-lifecycle")
            val watcher = FsWatchers.create() as NativeBackedFsWatcher
            val nativeWatchCallCount = AtomicInteger(0)
            val nativeWatchEntered = CountDownLatch(1)
            val pendingWaiterEntered = CountDownLatch(1)
            val releaseNativeWatch = CountDownLatch(1)
            val waiterResolved = CountDownLatch(1)
            val allowWaiterToReturn = CountDownLatch(1)
            val eventPath = root.resolve("beta.txt")

            NativeFsWatcherBridge.setNativeWatchInterceptorForTesting { _, _, path, _, name ->
                assertEquals(root.toString(), path)
                when (name) {
                    "alpha" -> {
                        nativeWatchCallCount.incrementAndGet()
                        nativeWatchEntered.countDown()
                        releaseNativeWatch.await()
                        true
                    }
                    "beta" -> {
                        nativeWatchCallCount.incrementAndGet()
                        true
                    }
                    else -> false
                }
            }

            watcher.pendingRegistrationEnteredHookForTesting = {
                pendingWaiterEntered.countDown()
            }
            watcher.pendingRegistrationResolvedHookForTesting = {
                waiterResolved.countDown()
                allowWaiterToReturn.await()
            }

            watcher.use {
                try {
                    val firstAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(root, name = "alpha")
                        }

                    assertTrue(nativeWatchEntered.awaitWithTimeout(), "first watch never reached nativeWatch")

                    val secondAttempt =
                        async(Dispatchers.Default) {
                            watcher.watch(root, name = "alpha")
                        }

                    assertTrue(pendingWaiterEntered.awaitWithTimeout(), "waiter never entered pending state")
                    releaseNativeWatch.countDown()

                    val first = firstAttempt.await()
                    assertTrue(waiterResolved.awaitWithTimeout(), "waiter never observed pending resolution")

                    first.close()
                    allowWaiterToReturn.countDown()

                    val second =
                        withTimeout(5_000) {
                            secondAttempt.await()
                        }

                    val maybeEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(500) {
                                watcher.events.first {
                                    it is FsWatchEvent.Modified &&
                                        it.path == eventPath &&
                                        it.source == second.source
                                }
                            }
                        }

                    val secondRegistrationId = watcher.registrationIdFor(second.source)
                    assertNotNull(secondRegistrationId)

                    NativeFsWatcherBridge.onNativeEvent(
                        watcher.nativeHandle,
                        secondRegistrationId,
                        secondRegistrationId,
                        EVENT_KIND_MODIFIED,
                        eventPath.toString(),
                        null,
                        false,
                        0,
                    )

                    assertEquals(2, nativeWatchCallCount.get())
                    assertEquals(
                        FsWatchEvent.Modified(
                            path = eventPath,
                            source = second.source,
                            isDirectory = false,
                            needsRescan = false,
                        ),
                        maybeEvent.await(),
                    )
                    assertTrue(second.active)
                } finally {
                    watcher.pendingRegistrationEnteredHookForTesting = null
                    watcher.pendingRegistrationResolvedHookForTesting = null
                    NativeFsWatcherBridge.setNativeWatchInterceptorForTesting(null)
                }
            }
        }
    }
}

private fun deleteRecursivelyForEventFlowTest(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun createRealTempDirectoryForEventFlowTest(prefix: String): Path =
    Files.createTempDirectory(prefix).toRealPath()

private fun hasObservedCoreEvents(
    seen: List<FsWatchEvent>,
    target: Path,
    requireModified: Boolean,
): Boolean {
    val createdSeen = seen.any { it is FsWatchEvent.Created && it.path == target }
    val modifiedSeen = seen.any { it is FsWatchEvent.Modified && it.path == target }
    val removedSeen = seen.any { it is FsWatchEvent.Removed && it.path == target }

    return createdSeen && removedSeen && (!requireModified || modifiedSeen)
}

private fun isWindowsHost(): Boolean = System.getProperty("os.name").startsWith("Windows")

private fun CountDownLatch.awaitWithTimeout(timeoutMillis: Long = 5_000): Boolean =
    await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)

private enum class EventFlowEventKind {
    CREATED,
    MODIFIED,
    REMOVED,
}

private suspend fun awaitMatchingCreatedEvents(
    seen: MutableList<FsWatchEvent.Created>,
    expectedPath: Path,
    expectedSources: Set<FsWatchSource>,
): List<FsWatchEvent.Created> =
    awaitMatchingEvents(seen) { events ->
        (
            events
                .filter { it.path == expectedPath && it.source in expectedSources }
                .takeIf { matches -> matches.mapNotNull { it.source }.toSet() == expectedSources }
        )
    }

private suspend fun awaitMatchingModifiedEvents(
    seen: MutableList<FsWatchEvent.Modified>,
    expectedPath: Path,
    expectedSources: Set<FsWatchSource>,
): List<FsWatchEvent.Modified> =
    awaitMatchingEvents(seen) { events ->
        (
            events
                .filter { it.path == expectedPath && it.source in expectedSources }
                .takeIf { matches -> matches.mapNotNull { it.source }.toSet() == expectedSources }
        )
    }

private suspend fun awaitMatchingErrors(
    seen: MutableList<FsWatchError>,
    expectedMessage: String,
    expectedSources: Set<FsWatchSource>,
): List<FsWatchError> =
    awaitMatchingEvents(seen) { errors ->
        (
            errors
                .filter { it.message == expectedMessage && it.source in expectedSources }
                .takeIf { matches -> matches.mapNotNull { it.source }.toSet() == expectedSources }
        )
    }

private suspend fun <T> awaitMatchingEvents(
    seen: MutableList<T>,
    matcher: (List<T>) -> List<T>?,
): List<T> {
    while (true) {
        val snapshot = synchronized(seen) { seen.toList() }
        matcher(snapshot)?.let { return it }
        delay(25)
    }
}

private fun List<FsWatchEvent>.countEventTuple(
    kind: EventFlowEventKind,
    path: Path,
    source: FsWatchSource,
): Int = count { it.matchesTuple(kind = kind, path = path, source = source) }

private fun List<FsWatchEvent>.countEventsAtPath(
    kind: EventFlowEventKind,
    path: Path,
): Int = count { it.matchesPathAndKind(kind = kind, path = path) }

private fun List<FsWatchEvent>.sourcesForPath(
    kind: EventFlowEventKind,
    path: Path,
): List<FsWatchSource> =
    mapNotNull { event ->
        when {
            !event.matchesPathAndKind(kind = kind, path = path) -> null
            event is FsWatchEvent.Created -> event.source
            event is FsWatchEvent.Modified -> event.source
            event is FsWatchEvent.Removed -> event.source
            else -> null
        }
    }

private fun FsWatchEvent.matchesTuple(
    kind: EventFlowEventKind,
    path: Path,
    source: FsWatchSource,
): Boolean =
    when (kind) {
        EventFlowEventKind.CREATED -> this is FsWatchEvent.Created && this.path == path && this.source == source
        EventFlowEventKind.MODIFIED -> this is FsWatchEvent.Modified && this.path == path && this.source == source
        EventFlowEventKind.REMOVED -> this is FsWatchEvent.Removed && this.path == path && this.source == source
    }

private fun FsWatchEvent.matchesPathAndKind(
    kind: EventFlowEventKind,
    path: Path,
): Boolean =
    when (kind) {
        EventFlowEventKind.CREATED -> this is FsWatchEvent.Created && this.path == path
        EventFlowEventKind.MODIFIED -> this is FsWatchEvent.Modified && this.path == path
        EventFlowEventKind.REMOVED -> this is FsWatchEvent.Removed && this.path == path
    }

private suspend fun awaitEventTupleCount(
    seen: List<FsWatchEvent>,
    kind: EventFlowEventKind,
    path: Path,
    source: FsWatchSource,
    expectedCount: Int,
    timeoutMillis: Long = 5_000,
) {
    withTimeout(timeoutMillis) {
        while (true) {
            when (
                val currentCount =
                    synchronized(seen) {
                        seen.countEventTuple(kind, path, source)
                    }
            ) {
                expectedCount -> return@withTimeout
                in (expectedCount + 1)..Int.MAX_VALUE ->
                    throw AssertionError(
                        "Expected $expectedCount events for $kind $path from $source but observed $currentCount",
                    )
            }
            delay(25)
        }
    }
}

private suspend fun awaitAtLeastOneEventTuple(
    seen: List<FsWatchEvent>,
    kind: EventFlowEventKind,
    path: Path,
    source: FsWatchSource,
    timeoutMillis: Long = 5_000,
) {
    withTimeout(timeoutMillis) {
        while (true) {
            if (synchronized(seen) { seen.countEventTuple(kind, path, source) } >= 1) {
                return@withTimeout
            }
            delay(25)
        }
    }
}

private suspend fun awaitEventTupleWithin(
    seen: List<FsWatchEvent>,
    kind: EventFlowEventKind,
    path: Path,
    source: FsWatchSource,
    timeoutMillis: Long = 500,
): FsWatchEvent? =
    withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val match = synchronized(seen) { seen.firstOrNull { it.matchesTuple(kind, path, source) } }
            if (match != null) return@withTimeoutOrNull match
            delay(25)
        }
        error("unreachable")
    }

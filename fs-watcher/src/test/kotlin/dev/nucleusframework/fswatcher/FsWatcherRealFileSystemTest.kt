package dev.nucleusframework.fswatcher

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Real-FS coverage intentionally stays in one fixture so shared temp-dir/event helpers remain local.
@Suppress("LargeClass")
class FsWatcherRealFileSystemTest {
    @Test
    fun recursiveWatchSeesFileEventsAcrossFourLevels() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-recursive-files")
            try {
                val level0 = root.resolve("a.txt")
                val level1Dir = Files.createDirectories(root.resolve("dir1"))
                val level1 = level1Dir.resolve("b.txt")
                val level2Dir = Files.createDirectories(level1Dir.resolve("dir2"))
                val level2 = level2Dir.resolve("c.txt")
                val level3Dir = Files.createDirectories(level2Dir.resolve("dir3"))
                val level3 = level3Dir.resolve("d.txt")

                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        watcher.watch(root, recursive = true)

                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.writeString(level0, "zero")
                            Files.writeString(level0, "zero-two")
                            Files.writeString(level1, "one")
                            Files.writeString(level2, "two")
                            Files.writeString(level3, "three")

                            awaitEvents {
                                listOf(level0, level1, level2, level3).all { path ->
                                    seen.any { it is FsWatchEvent.Created && it.path == path }
                                }
                            }

                            Files.delete(level3)
                            Files.delete(level2)
                            Files.delete(level1)
                            Files.delete(level0)

                            awaitEvents {
                                listOf(level0, level1, level2, level3).all { path ->
                                    seen.any { it is FsWatchEvent.Removed && it.path == path }
                                }
                            }

                            listOf(level0, level1, level2, level3).forEach { path ->
                                assertTrue(seen.any { it is FsWatchEvent.Created && it.path == path })
                                assertTrue(seen.any { it is FsWatchEvent.Removed && it.path == path })
                                val maybeModified =
                                    seen.filterIsInstance<FsWatchEvent.Modified>().firstOrNull { it.path == path }
                                if (maybeModified != null) {
                                    assertTrue(maybeModified.isDirectory == false || maybeModified.isDirectory == null)
                                }
                            }
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun recursiveWatchSeesDirectoryEventsAcrossFourLevels() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-recursive-dirs")
            try {
                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        watcher.watch(root, recursive = true)

                        val dir1 = root.resolve("dir1")
                        val dir2 = dir1.resolve("dir2")
                        val dir3 = dir2.resolve("dir3")
                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.createDirectory(dir1)
                            awaitEvents {
                                seen.any { it is FsWatchEvent.Created && it.path == dir1 && it.isDirectory == true }
                            }

                            Files.createDirectory(dir2)
                            awaitEvents {
                                seen.any { it is FsWatchEvent.Created && it.path == dir2 && it.isDirectory == true }
                            }

                            Files.createDirectory(dir3)
                            awaitEvents {
                                seen.any { it is FsWatchEvent.Created && it.path == dir3 && it.isDirectory == true }
                            }

                            Files.delete(dir3)
                            Files.delete(dir2)
                            Files.delete(dir1)

                            awaitEvents {
                                listOf(dir1, dir2, dir3).all { path ->
                                    seen.any {
                                        it is FsWatchEvent.Removed &&
                                            it.path == path &&
                                            (it.isDirectory == true || it.isDirectory == null)
                                    }
                                }
                            }
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun defaultDebouncedWatcherDeliversCoreRealFileEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-default-debounced")
            val target = root.resolve("debounced.txt")

            try {
                Files.writeString(target, "before-watch")
                FsWatchers.create().use { watcher ->
                    watcher.watch(root, recursive = true)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.delete(target)

                        awaitEvents {
                            seen.anyCoreEventFor(target)
                        }

                        assertTrue(seen.anyCoreEventFor(target))
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun defaultDebouncedWatcherTreatsRealRenameAsHostSensitiveObservation() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-debounced-rename")
            val from = root.resolve("before.txt")
            val to = root.resolve("after.txt")

            try {
                Files.writeString(from, "before-rename")

                FsWatchers.create().use { watcher ->
                    val registration = watcher.watch(root, recursive = true)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.move(from, to)

                        awaitEvents {
                            synchronized(seen) {
                                seen.any { event ->
                                    event.matchesSource(registration.source) &&
                                        (event.matchesPath(from) || event.matchesPath(to))
                                }
                            }
                        }

                        val moved =
                            synchronized(seen) {
                                seen.filterIsInstance<FsWatchEvent.Moved>().firstOrNull {
                                    it.source == registration.source
                                }
                            }
                        val removedFrom =
                            synchronized(seen) {
                                seen.filterIsInstance<FsWatchEvent.Removed>().firstOrNull {
                                    it.path == from && it.source == registration.source
                                }
                            }
                        val createdTo =
                            synchronized(seen) {
                                seen.filterIsInstance<FsWatchEvent.Created>().firstOrNull {
                                    it.path == to && it.source == registration.source
                                }
                            }
                        val observedRenameLikeEvent =
                            synchronized(seen) {
                                seen.firstOrNull { event ->
                                    event.matchesSource(registration.source) &&
                                        (event.matchesPath(from) || event.matchesPath(to))
                                }
                            }

                        if (moved != null) {
                            assertEquals(from, moved.from)
                            assertEquals(to, moved.to)
                        } else {
                            if (removedFrom != null || createdTo != null) {
                                assertTrue(removedFrom != null || createdTo != null)
                            } else {
                                assertNotNull(observedRenameLikeEvent)
                            }
                        }
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun rawDeliveryModeStillDeliversCoreRealFileEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-raw-opt-out")
            val target = root.resolve("raw.txt")

            try {
                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        watcher.watch(root, recursive = true)

                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.writeString(target, "one")
                            awaitEvents {
                                seen.anyCoreEventFor(target)
                            }

                            Files.delete(target)
                            awaitEvents {
                                seen.anyRemoved(path = target)
                            }

                            assertTrue(seen.anyCoreEventFor(target))
                            assertTrue(seen.anyRemoved(path = target))
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun pollingBackendObservesFileLifecycleOnRealFilesystem() =
        runBlocking {
            val config =
                FsWatcherConfig(
                    backend = FsWatchBackendStrategy.Polling(interval = Duration.ofMillis(50)),
                    deliveryMode = FsWatchDeliveryMode.Raw,
                )
            if (!FsWatchers.isSupported(config)) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-polling")
            val target = root.resolve("polling.txt")

            try {
                FsWatchers.create(config).use { watcher ->
                    watcher.watch(root, recursive = true)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(target, "one")
                        awaitEvents(timeoutMillis = 10_000) {
                            seen.anyCreated(path = target)
                        }
                        delay(200)
                        val eventCountAfterFirstWrite =
                            synchronized(seen) {
                                seen.count { event ->
                                    event.matchesPath(target) && event !is FsWatchEvent.Removed
                                }
                            }

                        delay(1_100)
                        Files.writeString(target, "two-updated")
                        awaitEvents(timeoutMillis = 10_000) {
                            synchronized(seen) {
                                seen.count { event ->
                                    event.matchesPath(target) && event !is FsWatchEvent.Removed
                                } > eventCountAfterFirstWrite
                            }
                        }

                        Files.delete(target)
                        awaitEvents(timeoutMillis = 10_000) {
                            seen.anyRemoved(path = target)
                        }

                        assertTrue(seen.anyCreated(path = target))
                        assertTrue(seen.anyRemoved(path = target))
                        assertTrue(
                            synchronized(seen) {
                                seen.count { event ->
                                    event.matchesPath(target) && event !is FsWatchEvent.Removed
                                } > eventCountAfterFirstWrite
                            },
                        )
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun nonRecursiveWatchSeesDirectChildrenButNotNestedDescendants() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-non-recursive")
            try {
                val directFile = root.resolve("direct.txt")
                val directDir = root.resolve("dir1")
                val nestedDir = directDir.resolve("dir2")
                val nestedFile = nestedDir.resolve("nested.txt")
                val deepDir = nestedDir.resolve("dir3")
                val deepFile = deepDir.resolve("deep.txt")

                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        watcher.watch(root, recursive = false)

                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.writeString(directFile, "direct")
                            Files.delete(directFile)
                            Files.createDirectory(directDir)

                            awaitEvents {
                                seen.anyCreated(path = directFile) &&
                                    seen.anyRemoved(path = directFile) &&
                                    seen.any {
                                        it is FsWatchEvent.Created &&
                                            it.path == directDir &&
                                            it.isDirectory == true
                                    }
                            }

                            val nestedDirEvent =
                                async(start = CoroutineStart.UNDISPATCHED) {
                                    assertNoMatchingEventWithin(watcher = watcher, path = nestedDir)
                                }
                            val nestedFileEvent =
                                async(start = CoroutineStart.UNDISPATCHED) {
                                    assertNoMatchingEventWithin(watcher = watcher, path = nestedFile)
                                }
                            val deepDirEvent =
                                async(start = CoroutineStart.UNDISPATCHED) {
                                    assertNoMatchingEventWithin(watcher = watcher, path = deepDir)
                                }
                            val deepFileEvent =
                                async(start = CoroutineStart.UNDISPATCHED) {
                                    assertNoMatchingEventWithin(watcher = watcher, path = deepFile)
                                }

                            Files.createDirectories(nestedDir)
                            Files.writeString(nestedFile, "nested")
                            Files.createDirectories(deepDir)
                            Files.writeString(deepFile, "deep")

                            assertNull(nestedDirEvent.await())
                            assertNull(nestedFileEvent.await())
                            assertNull(deepDirEvent.await())
                            assertNull(deepFileEvent.await())

                            val directFileModified =
                                seen.filterIsInstance<FsWatchEvent.Modified>().firstOrNull { it.path == directFile }
                            if (directFileModified != null) {
                                assertTrue(
                                    directFileModified.isDirectory == false ||
                                        directFileModified.isDirectory == null,
                                )
                            }
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun samePathSameRecursiveSharedWatchSurvivesRealFileChanges() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-shared")
            val target = root.resolve("survivor.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val first = watcher.watch(root, recursive = true)
                    val second = watcher.watch(root, recursive = true)
                    first.close()

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(target, "one")
                        Files.writeString(target, "two")

                        awaitEvents {
                            seen.hasEventFromSource(target, second.source)
                        }

                        assertTrue(seen.hasEventFromSource(target, second.source))
                        assertTrue(second.active)
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun samePathDifferentNamesSharedWatchSurvivesDeepRealFileChanges() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-shared-names")
            val nested = Files.createDirectories(root.resolve("dir1/dir2/dir3"))
            val target = nested.resolve("deep.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val first = watcher.watch(root, recursive = true, name = "alpha")
                    val second = watcher.watch(root, recursive = true, name = "beta")
                    first.close()

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(target, "one")
                        Files.writeString(target, "two")

                        awaitEvents {
                            seen.hasEventFromSource(target, second.source)
                        }

                        assertTrue(seen.hasEventFromSource(target, second.source))
                        assertFalse(seen.hasEventFromSource(target, first.source))
                        assertTrue(second.active)
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun samePathSameRecursiveDifferentNamesSharedWatchFanOutsRealEventsToBothSources() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-shared-fanout")
            val target = root.resolve("fanout.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val first = watcher.watch(root, recursive = true, name = "alpha")
                    val second = watcher.watch(root, recursive = true, name = "beta")

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(target, "one")

                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target, first.source) &&
                                seen.hasEventTuple(RealFsEventKind.CREATED, target, second.source)
                        }
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun overlappingRecursiveParentAndChildBothObserveRealNestedChanges() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-overlapping-recursive")
            val child = Files.createDirectories(root.resolve("child"))
            val target = child.resolve("nested/deep.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val parent = watcher.watch(root, recursive = true, name = "parent")
                    val nested = watcher.watch(child, recursive = true, name = "child")

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.createDirectories(target.parent)
                        // Linux/inotify may surface deep file events only after the new nested directory is observed.
                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target.parent, parent.source) &&
                                seen.hasEventTuple(RealFsEventKind.CREATED, target.parent, nested.source)
                        }
                        Files.writeString(target, "one")

                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target, parent.source) &&
                                seen.hasEventTuple(RealFsEventKind.CREATED, target, nested.source)
                        }

                        assertTrue(seen.hasEventTuple(RealFsEventKind.CREATED, target, parent.source))
                        assertTrue(seen.hasEventTuple(RealFsEventKind.CREATED, target, nested.source))
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun samePathMixedRecursiveDirectChildEventReachesBothRegistrations() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-mixed-direct")
            val target = root.resolve("child.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val recursive = watcher.watch(root, recursive = true, name = "recursive")
                    val nonRecursive = watcher.watch(root, recursive = false, name = "flat")

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(target, "one")

                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target, recursive.source) &&
                                seen.hasEventTuple(RealFsEventKind.CREATED, target, nonRecursive.source)
                        }

                        assertTrue(seen.hasEventTuple(RealFsEventKind.CREATED, target, recursive.source))
                        assertTrue(seen.hasEventTuple(RealFsEventKind.CREATED, target, nonRecursive.source))
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun samePathMixedRecursiveDeepEventOnlyReachesRecursiveRegistration() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-mixed-deep")
            val target = root.resolve("child/deep.txt")

            try {
                FsWatchers.create().use { watcher ->
                    val recursive = watcher.watch(root, recursive = true, name = "recursive")
                    val nonRecursive = watcher.watch(root, recursive = false, name = "flat")

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.createDirectories(target.parent)
                        // Waiting for the directory create avoids racing the recursive watch installation on Linux.
                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target.parent, recursive.source)
                        }
                        Files.writeString(target, "one")

                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, target, recursive.source)
                        }

                        assertNull(
                            awaitEventTupleWithin(
                                seen = seen,
                                kind = RealFsEventKind.CREATED,
                                path = target,
                                source = nonRecursive.source,
                            ),
                        )
                        assertTrue(seen.hasEventTuple(RealFsEventKind.CREATED, target, recursive.source))
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun uncanonicalizedTempDirectoryRootDeliversRealFileEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            // macOS temp directories live under the symlinked /var -> /private/var, so
            // Files.createTempDirectory() on its own hands watch() a non-canonical root spelling.
            val root = Files.createTempDirectory("fs-watcher-real-fs-uncanonical-root")
            val target = root.resolve("alpha.txt")

            try {
                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        val registration = watcher.watch(root, recursive = true)

                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.writeString(target, "one")

                            awaitEvents {
                                seen.hasEventFromSource(target, registration.source)
                            }

                            assertTrue(seen.hasEventFromSource(target, registration.source))
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun rootReachedThroughSymlinkedParentDeliversEventsWithRegisteredPathForm() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalParent = createRealTempDirectory("fs-watcher-real-fs-symlinked-parent")
            val symlinkedParent = canonicalParent.parent.resolve("${canonicalParent.fileName}-parent-link")

            try {
                try {
                    Files.deleteIfExists(symlinkedParent)
                    Files.createSymbolicLink(symlinkedParent, canonicalParent)
                } catch (_: UnsupportedOperationException) {
                    return@runBlocking
                } catch (_: java.nio.file.FileSystemException) {
                    return@runBlocking
                }

                val canonicalRoot = Files.createDirectory(canonicalParent.resolve("watched"))
                val lexicalRoot = symlinkedParent.resolve("watched")
                // Only the parent is a symlink: the watched root itself is a plain directory,
                // so delivery must not depend on followSymlinks.
                assertFalse(Files.isSymbolicLink(lexicalRoot))

                val lexicalTarget = lexicalRoot.resolve("beta.txt")
                FsWatchers
                    .create(
                        FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw),
                    ).use { watcher ->
                        val registration = watcher.watch(lexicalRoot, recursive = true)

                        val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { seen += it }
                            }

                        try {
                            Files.writeString(canonicalRoot.resolve("beta.txt"), "one")

                            awaitEvents {
                                seen.hasEventFromSource(lexicalTarget, registration.source)
                            }

                            assertTrue(seen.hasEventFromSource(lexicalTarget, registration.source))
                        } finally {
                            collector.cancelAndJoin()
                        }
                    }
            } finally {
                Files.deleteIfExists(symlinkedParent)
                deleteRecursively(canonicalParent)
            }
        }

    @Test
    fun canonicalAndSymlinkRegistrationsObserveSameRealChangeWithDistinctLexicalPaths() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = createRealTempDirectory("fs-watcher-real-fs-canonical-symlink")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-link")

            try {
                try {
                    Files.deleteIfExists(symlinkRoot)
                    Files.createSymbolicLink(symlinkRoot, canonicalRoot)
                } catch (_: UnsupportedOperationException) {
                    return@runBlocking
                } catch (_: java.nio.file.FileSystemException) {
                    return@runBlocking
                }

                FsWatchers.create(FsWatcherConfig(followSymlinks = true)).use { watcher ->
                    val canonical = watcher.watch(canonicalRoot, recursive = true, name = "canonical")
                    val symlink = watcher.watch(symlinkRoot, recursive = true, name = "alias")
                    val canonicalPath = canonicalRoot.resolve("alpha.txt")
                    val symlinkPath = symlinkRoot.resolve("alpha.txt")

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(canonicalPath, "one")

                        awaitEvents {
                            seen.hasEventTuple(RealFsEventKind.CREATED, canonicalPath, canonical.source) &&
                                seen.hasEventTuple(RealFsEventKind.CREATED, symlinkPath, symlink.source)
                        }
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursively(canonicalRoot)
            }
        }

    @Test
    fun symlinkRootRealFileEventsRemapToLexicalSourcePath() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val canonicalRoot = createRealTempDirectory("fs-watcher-real-fs-symlink-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-link")
            try {
                try {
                    Files.deleteIfExists(symlinkRoot)
                    Files.createSymbolicLink(symlinkRoot, canonicalRoot)
                } catch (_: UnsupportedOperationException) {
                    return@runBlocking
                } catch (_: java.nio.file.FileSystemException) {
                    return@runBlocking
                }

                val lexicalChild = symlinkRoot.resolve("alpha.txt")
                FsWatchers.create(FsWatcherConfig(followSymlinks = true)).use { watcher ->
                    watcher.watch(symlinkRoot, recursive = true)

                    val event =
                        withTimeout(10_000) {
                            val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                            val collector =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { seen += it }
                                }
                            try {
                                Files.writeString(canonicalRoot.resolve("alpha.txt"), "one")
                                awaitEvents {
                                    seen.any { it is FsWatchEvent.Created && it.path == lexicalChild }
                                }
                                seen.filterIsInstance<FsWatchEvent.Created>().first { it.path == lexicalChild }
                            } finally {
                                collector.cancelAndJoin()
                            }
                        }

                    assertEquals(lexicalChild, event.path)
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursively(canonicalRoot)
            }
        }

    @Test
    fun symlinkRootResolvedFileEventsDoNotRemapWhenFollowSymlinksDisabled() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking
            // Linux and Windows report this real-fs symlink case differently
            // from the lexical-path behavior asserted here.
            if (isLinuxHost() || isWindowsHost()) return@runBlocking

            val canonicalRoot = createRealTempDirectory("fs-watcher-real-fs-no-follow-target")
            val symlinkRoot = canonicalRoot.parent.resolve("${canonicalRoot.fileName}-link")
            try {
                try {
                    Files.deleteIfExists(symlinkRoot)
                    Files.createSymbolicLink(symlinkRoot, canonicalRoot)
                } catch (_: UnsupportedOperationException) {
                    return@runBlocking
                } catch (_: java.nio.file.FileSystemException) {
                    return@runBlocking
                }

                val lexicalChild = symlinkRoot.resolve("alpha.txt")
                FsWatchers.create(FsWatcherConfig(followSymlinks = false)).use { watcher ->
                    watcher.watch(symlinkRoot, recursive = true)

                    val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            watcher.events.collect { seen += it }
                        }

                    try {
                        Files.writeString(canonicalRoot.resolve("alpha.txt"), "one")
                        delay(400)

                        assertFalse(seen.any { it is FsWatchEvent.Created && it.path == lexicalChild })
                    } finally {
                        collector.cancelAndJoin()
                    }
                }
            } finally {
                Files.deleteIfExists(symlinkRoot)
                deleteRecursively(canonicalRoot)
            }
        }

    @Test
    fun watchFailsForMissingRoot() {
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val missingRoot = createRealTempDirectory("fs-watcher-real-fs-missing-root")
            deleteRecursively(missingRoot)

            FsWatchers.create().use { watcher ->
                assertFailsWith<FsWatchException> {
                    watcher.watch(missingRoot, recursive = true)
                }
            }
        }
    }

    @Test
    fun pollingWatchFailsForMissingRoot() {
        runBlocking {
            val config =
                FsWatcherConfig(
                    backend = FsWatchBackendStrategy.Polling(interval = Duration.ofMillis(50)),
                    deliveryMode = FsWatchDeliveryMode.Raw,
                )
            if (!FsWatchers.isSupported(config)) return@runBlocking

            val missingRoot = createRealTempDirectory("fs-watcher-real-fs-polling-missing-root")
            deleteRecursively(missingRoot)

            FsWatchers.create(config).use { watcher ->
                assertFailsWith<FsWatchException> {
                    watcher.watch(missingRoot, recursive = true)
                }
            }
        }
    }

    @Test
    fun deletingWatchedRootDoesNotReviveEventsAfterClose() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-delete-root")
            val watcher = FsWatchers.create()
            try {
                val registration = watcher.watch(root, recursive = true)
                val seen = java.util.Collections.synchronizedList(mutableListOf<FsWatchEvent>())
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        watcher.events.collect { seen += it }
                    }

                try {
                    val beforeDelete = root.resolve("before-delete.txt")
                    Files.writeString(beforeDelete, "one")
                    awaitEvents {
                        seen.hasEventFromSource(beforeDelete, registration.source)
                    }

                    val deleted = tryDeleteRecursively(root)
                    watcher.close()

                    if (!deleted) return@runBlocking

                    val recreatedRoot = Files.createDirectories(root.resolve("rebuilt/tree"))
                    val recreatedFile = recreatedRoot.resolve("after-close.txt")
                    val lateEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(750) {
                                watcher.events.first { event ->
                                    when (event) {
                                        is FsWatchEvent.Created ->
                                            event.path == recreatedFile && event.source == registration.source
                                        is FsWatchEvent.Modified ->
                                            event.path == recreatedFile && event.source == registration.source
                                        is FsWatchEvent.Removed ->
                                            event.path == recreatedFile && event.source == registration.source
                                        else -> false
                                    }
                                }
                            }
                        }

                    Files.writeString(recreatedFile, "two")
                    assertNull(lateEvent.await())
                } finally {
                    collector.cancelAndJoin()
                }
            } finally {
                watcher.close()
                deleteRecursively(root)
            }
        }

    @Test
    fun closeStopsSubsequentRealFileEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-close")
            val target = root.resolve("closed.txt")
            val watcher = FsWatchers.create()
            try {
                val lateEvent =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(500) {
                            watcher.events.first { event ->
                                when (event) {
                                    is FsWatchEvent.Created -> event.path == target
                                    is FsWatchEvent.Modified -> event.path == target
                                    is FsWatchEvent.Removed -> event.path == target
                                    else -> false
                                }
                            }
                        }
                    }

                watcher.watch(root, recursive = true)
                watcher.close()
                Files.writeString(target, "one")

                assertNull(lateEvent.await())
            } finally {
                watcher.close()
                deleteRecursively(root)
            }
        }

    @Test
    fun closeDropsPendingDebouncedFlushFromRealFileChanges() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-close-debounced-flush")
            val target = root.resolve("pending-close.txt")
            val watcher =
                FsWatchers.create(
                    FsWatcherConfig(
                        deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(400)),
                    ),
                )

            try {
                watcher.watch(root, recursive = true)
                Files.writeString(target, "one")
                watcher.close()

                val lateEvent =
                    withTimeoutOrNull(900) {
                        watcher.events.first { event -> event.matchesPath(target) }
                    }

                assertNull(lateEvent)
            } finally {
                watcher.close()
                deleteRecursively(root)
            }
        }

    @Test
    fun unwatchStopsSubsequentRealFileEvents() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-unwatch")
            val target = root.resolve("unwatched.txt")
            try {
                FsWatchers.create().use { watcher ->
                    val registration = watcher.watch(root, recursive = true)
                    val lateEvent =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeoutOrNull(500) {
                                watcher.events.first { event ->
                                    when (event) {
                                        is FsWatchEvent.Created ->
                                            event.path == target && event.source == registration.source
                                        is FsWatchEvent.Modified ->
                                            event.path == target && event.source == registration.source
                                        is FsWatchEvent.Removed ->
                                            event.path == target && event.source == registration.source
                                        else -> false
                                    }
                                }
                            }
                        }

                    registration.close()
                    Files.writeString(target, "one")

                    assertNull(lateEvent.await())
                }
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun unwatchDropsPendingDebouncedFlushFromRealFileChanges() =
        runBlocking {
            if (!FsWatchers.isSupported()) return@runBlocking

            val root = createRealTempDirectory("fs-watcher-real-fs-unwatch-debounced-flush")
            val target = root.resolve("pending-unwatch.txt")
            try {
                FsWatchers
                    .create(
                        FsWatcherConfig(
                            deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(400)),
                        ),
                    ).use { watcher ->
                        val registration = watcher.watch(root, recursive = true)
                        Files.writeString(target, "one")
                        registration.close()

                        val lateEvent =
                            withTimeoutOrNull(900) {
                                watcher.events.first { event ->
                                    event.matchesPath(target) && event.matchesSource(registration.source)
                                }
                            }

                        assertNull(lateEvent)
                    }
            } finally {
                deleteRecursively(root)
            }
        }
}

private suspend fun awaitEvents(
    timeoutMillis: Long = 10_000,
    pollIntervalMillis: Long = 25,
    predicate: () -> Boolean,
) {
    withTimeout(timeoutMillis) {
        while (!predicate()) {
            delay(pollIntervalMillis)
        }
    }
}

private suspend fun assertNoMatchingEventWithin(
    watcher: FsWatcher,
    path: Path,
    timeoutMillis: Long = 500,
): FsWatchEvent? =
    withTimeoutOrNull(timeoutMillis) {
        watcher.events.first { event ->
            when (event) {
                is FsWatchEvent.Created -> event.path == path
                is FsWatchEvent.Modified -> event.path == path
                is FsWatchEvent.Removed -> event.path == path
                else -> false
            }
        }
    }

private fun List<FsWatchEvent>.anyCreated(path: Path): Boolean =
    any { event -> event is FsWatchEvent.Created && event.path == path }

private fun List<FsWatchEvent>.anyRemoved(path: Path): Boolean =
    any { event -> event is FsWatchEvent.Removed && event.path == path }

private fun List<FsWatchEvent>.anyCoreEventFor(path: Path): Boolean =
    any { event ->
        when (event) {
            is FsWatchEvent.Created -> event.path == path
            is FsWatchEvent.Modified -> event.path == path
            is FsWatchEvent.Removed -> event.path == path
            else -> false
        }
    }

private fun createRealTempDirectory(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()

private fun isLinuxHost(): Boolean = System.getProperty("os.name").startsWith("Linux")

private fun isWindowsHost(): Boolean = System.getProperty("os.name").startsWith("Windows")

private enum class RealFsEventKind {
    CREATED,
    MODIFIED,
    REMOVED,
}

private fun List<FsWatchEvent>.hasEventTuple(
    kind: RealFsEventKind,
    path: Path,
    source: FsWatchSource,
): Boolean = any { event -> event.matchesTuple(kind = kind, path = path, source = source) }

private fun List<FsWatchEvent>.hasEventFromSource(
    path: Path,
    source: FsWatchSource,
): Boolean =
    any { event ->
        when (event) {
            is FsWatchEvent.Created -> event.path == path && event.source == source
            is FsWatchEvent.Modified -> event.path == path && event.source == source
            is FsWatchEvent.Removed -> event.path == path && event.source == source
            is FsWatchEvent.Moved -> (event.from == path || event.to == path) && event.source == source
            is FsWatchEvent.Overflow -> false
            is FsWatchEvent.Other -> path in event.paths && event.source == source
        }
    }

private fun FsWatchEvent.matchesTuple(
    kind: RealFsEventKind,
    path: Path,
    source: FsWatchSource,
): Boolean =
    when (kind) {
        RealFsEventKind.CREATED -> this is FsWatchEvent.Created && this.path == path && this.source == source
        RealFsEventKind.MODIFIED -> this is FsWatchEvent.Modified && this.path == path && this.source == source
        RealFsEventKind.REMOVED -> this is FsWatchEvent.Removed && this.path == path && this.source == source
    }

private fun FsWatchEvent.matchesPath(path: Path): Boolean =
    when (this) {
        is FsWatchEvent.Created -> this.path == path
        is FsWatchEvent.Modified -> this.path == path
        is FsWatchEvent.Removed -> this.path == path
        is FsWatchEvent.Moved -> this.from == path || this.to == path
        is FsWatchEvent.Overflow -> false
        is FsWatchEvent.Other -> path in this.paths
    }

private fun FsWatchEvent.matchesSource(source: FsWatchSource): Boolean =
    when (this) {
        is FsWatchEvent.Created -> this.source == source
        is FsWatchEvent.Modified -> this.source == source
        is FsWatchEvent.Removed -> this.source == source
        is FsWatchEvent.Moved -> this.source == source
        is FsWatchEvent.Overflow -> false
        is FsWatchEvent.Other -> this.source == source
    }

private suspend fun awaitEventTupleWithin(
    seen: List<FsWatchEvent>,
    kind: RealFsEventKind,
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

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return

    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun tryDeleteRecursively(root: Path): Boolean =
    try {
        deleteRecursively(root)
        !Files.exists(root)
    } catch (_: Exception) {
        false
    }

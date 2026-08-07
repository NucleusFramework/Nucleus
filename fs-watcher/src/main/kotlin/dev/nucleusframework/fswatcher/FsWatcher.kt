package dev.nucleusframework.fswatcher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

public sealed interface FsWatchBackendStrategy {
    public data object Auto : FsWatchBackendStrategy

    public data object NativeOnly : FsWatchBackendStrategy

    // Polling backend for environments where native notifications are unavailable or undesirable.
    public data class Polling(
        val interval: Duration = Duration.ofSeconds(2),
        val compareContents: Boolean = false,
    ) : FsWatchBackendStrategy
}

private const val DEFAULT_DEBOUNCE_WINDOW_MILLIS = 150L
private val DEFAULT_DEBOUNCE_WINDOW: Duration = Duration.ofMillis(DEFAULT_DEBOUNCE_WINDOW_MILLIS)

public sealed interface FsWatchDeliveryMode {
    public data object Raw : FsWatchDeliveryMode

    public data class Debounced(
        val window: Duration = DEFAULT_DEBOUNCE_WINDOW,
    ) : FsWatchDeliveryMode {
        init {
            require(!window.isZero && !window.isNegative) { "debounce window must be > 0" }
            require(window.toMillis() > 0) {
                "debounce window must be at least 1ms after millisecond conversion"
            }
        }
    }
}

public data class FsWatcherConfig(
    /**
     * Whether the backend descends into symlinks it meets inside a watched tree, and whether a
     * watch root that *is itself* a symlink reports its target's events under the root's own path.
     *
     * This does not affect roots merely reached *through* a symlink — a root such as
     * `/var/folders/…/T/app` on macOS is delivered either way, under the spelling it was
     * registered with.
     */
    val followSymlinks: Boolean = false,
    val backend: FsWatchBackendStrategy = FsWatchBackendStrategy.Auto,
    val eventBufferCapacity: Int = 64,
    val errorBufferCapacity: Int = 32,
    val deliveryMode: FsWatchDeliveryMode = FsWatchDeliveryMode.Debounced(),
) {
    init {
        require(eventBufferCapacity > 0) { "eventBufferCapacity must be > 0" }
        require(errorBufferCapacity > 0) { "errorBufferCapacity must be > 0" }
    }
}

public data class FsWatchError(
    val message: String,
    val source: FsWatchSource? = null,
    val recoverable: Boolean = false,
    val cause: Throwable? = null,
)

public class FsWatchException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public interface FsWatchRegistration : AutoCloseable {
    public val source: FsWatchSource
    public val active: Boolean

    override fun close()
}

public interface FsWatcher : AutoCloseable {
    public val registrations: Set<FsWatchSource>
    public val events: Flow<FsWatchEvent>
    public val errors: Flow<FsWatchError>

    /**
     * Watches [path], optionally [recursive]ly, under an optional [name] used to tell registrations
     * of the same root apart.
     *
     * [path] needs no canonicalization: delivered [FsWatchEvent] paths are rooted at the spelling
     * passed here, whatever form the platform backend reports internally. Registering the same
     * directory under two spellings does yield two independent registrations and two native
     * watches, so pick one form per root if that matters.
     *
     * @throws FsWatchException if the root cannot be watched.
     */
    public fun watch(
        path: Path,
        recursive: Boolean = true,
        name: String? = null,
    ): FsWatchRegistration

    override fun close()
}

public object FsWatchers {
    public fun create(config: FsWatcherConfig = FsWatcherConfig()): FsWatcher {
        if (!isSupportedForCreate(config)) {
            val message =
                "fs-watcher backend is not available for config: $config"
            throw FsWatchException(message)
        }
        val createParameters = config.nativeCreateParameters()
        val watcherHandle =
            NativeFsWatcherBridge.createWatcher(
                followSymlinks = config.followSymlinks,
                backendMode = createParameters.backendMode,
                deliveryMode = createParameters.deliveryMode,
                debounceWindowMillis = createParameters.debounceWindowMillis,
                pollIntervalMillis = createParameters.pollIntervalMillis,
                compareContents = createParameters.compareContents,
            )
        if (watcherHandle == 0L) {
            throw FsWatchException("Failed to create native fs-watcher for config: $config")
        }
        return NativeBackedFsWatcher(config, watcherHandle)
    }

    public fun isSupported(config: FsWatcherConfig = FsWatcherConfig()): Boolean {
        if (!NativeFsWatcherBridge.isLoaded) return false
        return when (config.backend) {
            FsWatchBackendStrategy.Auto,
            FsWatchBackendStrategy.NativeOnly,
            -> NativeFsWatcherBridge.nativeIsSupported()
            is FsWatchBackendStrategy.Polling -> {
                val createParameters = config.nativeCreateParameters()
                if (!hasNativeCreateInterceptorForTesting()) {
                    NativeFsWatcherBridge.nativeIsSupported()
                } else {
                    val watcherHandle =
                        NativeFsWatcherBridge.createWatcher(
                            followSymlinks = config.followSymlinks,
                            backendMode = createParameters.backendMode,
                            deliveryMode = createParameters.deliveryMode,
                            debounceWindowMillis = createParameters.debounceWindowMillis,
                            pollIntervalMillis = createParameters.pollIntervalMillis,
                            compareContents = createParameters.compareContents,
                        )
                    if (watcherHandle == 0L) {
                        false
                    } else {
                        NativeFsWatcherBridge.nativeClose(watcherHandle)
                        true
                    }
                }
            }
        }
    }
}

private fun isSupportedForCreate(config: FsWatcherConfig): Boolean {
    if (!NativeFsWatcherBridge.isLoaded) return false
    return when (config.backend) {
        FsWatchBackendStrategy.Auto,
        FsWatchBackendStrategy.NativeOnly,
        -> NativeFsWatcherBridge.nativeIsSupported()
        is FsWatchBackendStrategy.Polling -> {
            config.nativeCreateParameters()
            NativeFsWatcherBridge.nativeIsSupported()
        }
    }
}

private fun hasNativeCreateInterceptorForTesting(): Boolean =
    runCatching {
        val field = NativeFsWatcherBridge::class.java.getDeclaredField("nativeCreateInterceptor")
        field.isAccessible = true
        field.get(NativeFsWatcherBridge) != null
    }.getOrDefault(false)

private data class NativeCreateParameters(
    val backendMode: Int,
    val deliveryMode: Int,
    val debounceWindowMillis: Long,
    val pollIntervalMillis: Long,
    val compareContents: Boolean,
)

private fun FsWatcherConfig.nativeCreateParameters(): NativeCreateParameters {
    val (nativeDeliveryMode, debounceWindowMillis) =
        when (val deliveryMode = deliveryMode) {
            FsWatchDeliveryMode.Raw -> DELIVERY_MODE_RAW to 0L
            is FsWatchDeliveryMode.Debounced -> {
                val debounceMillis = deliveryMode.window.toMillis()
                require(debounceMillis > 0) {
                    "debounce window must be at least 1ms after millisecond conversion"
                }
                DELIVERY_MODE_DEBOUNCED to debounceMillis
            }
        }

    return when (val backend = backend) {
        FsWatchBackendStrategy.Auto,
        FsWatchBackendStrategy.NativeOnly,
        ->
            NativeCreateParameters(
                backendMode = BACKEND_MODE_NATIVE,
                deliveryMode = nativeDeliveryMode,
                debounceWindowMillis = debounceWindowMillis,
                pollIntervalMillis = 0L,
                compareContents = false,
            )
        is FsWatchBackendStrategy.Polling -> {
            val pollIntervalMillis = backend.interval.toMillis()
            require(pollIntervalMillis > 0) {
                "polling interval must be at least 1ms after millisecond conversion"
            }
            NativeCreateParameters(
                backendMode = BACKEND_MODE_POLLING,
                deliveryMode = nativeDeliveryMode,
                debounceWindowMillis = debounceWindowMillis,
                pollIntervalMillis = pollIntervalMillis,
                compareContents = backend.compareContents,
            )
        }
    }
}

private data class NativeWatchKey(
    val root: Path,
    val recursive: Boolean,
)

private data class LogicalMatch(
    val source: FsWatchSource,
    val originalRoot: Path,
    val matchedRoot: Path,
)

private sealed interface RoutedFsWatchEvent {
    data class Deliver(
        val event: FsWatchEvent,
    ) : RoutedFsWatchEvent

    data object Overflow : RoutedFsWatchEvent
}

private data class RoutedFsWatchError(
    val error: FsWatchError,
)

@Suppress("LargeClass")
internal class NativeBackedFsWatcher(
    private val config: FsWatcherConfig,
    private val watcherHandle: Long,
) : FsWatcher,
    NativeFsWatcherSink {
    private val stateLock = Any()
    private val logicalRegistrationsBySource = LinkedHashMap<FsWatchSource, LogicalRegistrationState>()
    private val nativeInstallationsByWatchKey = LinkedHashMap<NativeWatchKey, NativeWatchInstallation>()
    private val nativeInstallationsById = LinkedHashMap<Long, NativeWatchInstallation>()
    private val activeRegistrations = LinkedHashSet<NativeFsWatchRegistration>()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventFlow =
        MutableSharedFlow<FsWatchEvent>(extraBufferCapacity = config.eventBufferCapacity)
    private val errorFlow =
        MutableSharedFlow<FsWatchError>(extraBufferCapacity = config.errorBufferCapacity)

    @Volatile
    private var closed = false
    private var watcherOverflowPending = false

    init {
        NativeFsWatcherBridge.registerSink(watcherHandle, this)
    }

    internal var pendingRegistrationEnteredHookForTesting: (() -> Unit)? = null
    internal var pendingRegistrationResolvedHookForTesting: (() -> Unit)? = null

    internal val nativeHandle: Long
        get() = watcherHandle

    internal fun registrationIdFor(source: FsWatchSource): Long? =
        synchronized(stateLock) {
            logicalRegistrationsBySource[source]?.nativeInstallation?.nativeRegistrationId
        }

    override val registrations: Set<FsWatchSource>
        get() =
            synchronized(stateLock) {
                logicalRegistrationsBySource.keys.toCollection(linkedSetOf())
            }
    override val events: Flow<FsWatchEvent> = eventFlow
    override val errors: Flow<FsWatchError> = errorFlow

    override fun watch(
        path: Path,
        recursive: Boolean,
        name: String?,
    ): FsWatchRegistration {
        check(!closed) { "Watcher is already closed" }
        val source = FsWatchSource(path, recursive, name)
        while (true) {
            when (
                val acquisition =
                    synchronized(stateLock) {
                        check(!closed) { "Watcher is already closed" }
                        planWatchAcquisitionLocked(source)
                    }
            ) {
                is WatchAcquisitionStep.CreateNativeWatch ->
                    return createNativeWatchRegistration(
                        source = source,
                        nativeInstallation = acquisition.nativeInstallation,
                    )
                is WatchAcquisitionStep.ReturnRegistration -> return acquisition.registration
                is WatchAcquisitionStep.WaitForPending -> {
                    if (closed) {
                        throw FsWatchException("Watcher closed while registering path: $path")
                    }
                    val resolved =
                        awaitPendingRegistration(acquisition.nativeInstallation)
                    if (!resolved && !acquisition.throwOnFailure) {
                        continue
                    }
                    if (!resolved) {
                        throw acquisition.nativeInstallation.failure ?: FsWatchException("Failed to watch path: $path")
                    }
                }
            }
        }
    }

    override fun emitEvent(payload: NativeFsWatchEventPayload) {
        var emitOverflow = false
        synchronized(stateLock) {
            if (closed) return

            if (payload.eventKind == EVENT_KIND_OVERFLOW) {
                if (!eventFlow.tryEmit(FsWatchEvent.Overflow(source = null))) {
                    emitOverflow = requestWatcherOverflowLocked()
                }
                return@synchronized
            }

            val path = payload.path ?: return
            val nativeInstallation =
                nativeInstallationForPathScopedCallbackLocked(
                    payload.registrationId,
                    payload.originNativeRegistrationId,
                ) ?: return
            routeEventPayload(
                payload = payload,
                nativeInstallation = nativeInstallation,
                matches = logicalMatchesForInstallationLocked(nativeInstallation, path),
            ).forEach { routedEvent ->
                when (routedEvent) {
                    is RoutedFsWatchEvent.Deliver -> {
                        if (!eventFlow.tryEmit(routedEvent.event)) {
                            emitOverflow = requestWatcherOverflowLocked() || emitOverflow
                        }
                    }
                    RoutedFsWatchEvent.Overflow -> {
                        emitOverflow = requestWatcherOverflowLocked() || emitOverflow
                    }
                }
            }
        }
        if (emitOverflow) {
            emitWatcherOverflowAsync()
        }
    }

    override fun emitError(payload: NativeFsWatchErrorPayload) {
        var emitOverflow = false
        synchronized(stateLock) {
            if (closed) return

            if (payload.registrationId == WATCHER_LEVEL_REGISTRATION_ID && payload.path == null) {
                if (
                    !errorFlow.tryEmit(
                        FsWatchError(
                            message = payload.message,
                            source = null,
                            recoverable = payload.recoverable,
                            cause = null,
                        ),
                    )
                ) {
                    emitOverflow = requestWatcherOverflowLocked()
                }
                return@synchronized
            }

            val sources =
                when {
                    payload.path == null ->
                        logicalSourcesForNativeInstallationLocked(payload.registrationId)
                            ?.map { source ->
                                RoutedFsWatchError(
                                    FsWatchError(
                                        message = payload.message,
                                        source = source,
                                        recoverable = payload.recoverable,
                                        cause = null,
                                    ),
                                )
                            }
                    else -> {
                        val nativeInstallation =
                            nativeInstallationForPathScopedCallbackLocked(
                                payload.registrationId,
                                payload.originNativeRegistrationId,
                            ) ?: return@synchronized
                        routePathScopedErrorPayload(
                            payload = payload,
                            matches = logicalMatchesForInstallationLocked(nativeInstallation, payload.path),
                        )
                    }
                } ?: return

            sources.forEach { routedError ->
                if (
                    !errorFlow.tryEmit(routedError.error)
                ) {
                    emitOverflow = requestWatcherOverflowLocked() || emitOverflow
                }
            }
        }
        if (emitOverflow) {
            emitWatcherOverflowAsync()
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            failPendingRegistrationsLocked(
                FsWatchException("Watcher closed while registrations were still pending"),
            )
            activeRegistrations.toList().forEach { it.deactivate() }
            activeRegistrations.clear()
            notifyStateLockWaiters()
            logicalRegistrationsBySource.clear()
            nativeInstallationsByWatchKey.clear()
            nativeInstallationsById.clear()
        }

        callbackScope.cancel()
        NativeFsWatcherBridge.unregisterSink(watcherHandle)
        NativeFsWatcherBridge.nativeClose(watcherHandle)
    }

    private fun requestWatcherOverflowLocked(): Boolean {
        if (closed || watcherOverflowPending) return false
        watcherOverflowPending = true
        return true
    }

    private fun nativeInstallationForPathScopedCallbackLocked(
        registrationId: Long,
        originNativeRegistrationId: Long?,
    ): NativeWatchInstallation? =
        when (registrationId) {
            WATCHER_LEVEL_REGISTRATION_ID -> originNativeRegistrationId?.let(nativeInstallationsById::get)
            else -> nativeInstallationsById[registrationId]
        }

    private fun logicalSourcesForNativeInstallationLocked(registrationId: Long): List<FsWatchSource>? =
        nativeInstallationsById[registrationId]
            ?.logicalSourcesLocked()
            ?.takeIf { it.isNotEmpty() }

    private fun logicalMatchesForInstallationLocked(
        nativeInstallation: NativeWatchInstallation,
        path: Path,
    ): List<LogicalMatch> =
        nativeInstallation.logicalSourcesLocked().mapNotNull { source ->
            val logical = logicalRegistrationsBySource[source] ?: return@mapNotNull null
            logicalMatchForSource(logical, source, path)
        }

    private fun routeEventPayload(
        payload: NativeFsWatchEventPayload,
        nativeInstallation: NativeWatchInstallation,
        matches: List<LogicalMatch>,
    ): List<RoutedFsWatchEvent> {
        if (payload.eventKind == EVENT_KIND_MOVED) {
            return routeMovedEventPayload(payload, nativeInstallation)
        }
        if (matches.isEmpty()) return emptyList()
        val routedEvents =
            matches.mapNotNull { match ->
                val remappedPath =
                    remapPathForMatch(
                        payload.path ?: return@mapNotNull null,
                        match,
                    ) ?: return@mapNotNull null
                buildEventForMatch(payload, match.source, remappedPath)?.let(RoutedFsWatchEvent::Deliver)
            }
        return if (routedEvents.isEmpty() && payload.needsRescan) {
            listOf(RoutedFsWatchEvent.Overflow)
        } else {
            routedEvents
        }
    }

    private fun routeMovedEventPayload(
        payload: NativeFsWatchEventPayload,
        nativeInstallation: NativeWatchInstallation,
    ): List<RoutedFsWatchEvent> {
        val fromPath = payload.path ?: return emptyList()
        val toPath = payload.secondaryPath ?: return emptyList()

        val routedEvents =
            nativeInstallation.logicalSourcesLocked().mapNotNull { source ->
                val logical = logicalRegistrationsBySource[source] ?: return@mapNotNull null
                val fromMatch = logicalMatchForSource(logical, source, fromPath)
                val toMatch = logicalMatchForSource(logical, source, toPath)
                if (fromMatch == null && toMatch == null) return@mapNotNull null

                val remappedFrom = remapMovedEndpoint(fromPath, fromMatch)
                val remappedTo = remapMovedEndpoint(toPath, toMatch)

                RoutedFsWatchEvent.Deliver(
                    FsWatchEvent.Moved(
                        from = remappedFrom ?: fromPath,
                        to = remappedTo ?: toPath,
                        source = source,
                        isDirectory = payload.isDirectory,
                        needsRescan = payload.needsRescan,
                    ),
                )
            }

        return if (routedEvents.isEmpty() && payload.needsRescan) {
            listOf(RoutedFsWatchEvent.Overflow)
        } else {
            routedEvents
        }
    }

    private fun routePathScopedErrorPayload(
        payload: NativeFsWatchErrorPayload,
        matches: List<LogicalMatch>,
    ): List<RoutedFsWatchError> =
        matches.mapNotNull { match ->
            remapPathForMatch(payload.path ?: return@mapNotNull null, match)?.let {
                RoutedFsWatchError(
                    FsWatchError(
                        message = payload.message,
                        source = match.source,
                        recoverable = payload.recoverable,
                        cause = null,
                    ),
                )
            }
        }

    private fun buildEventForMatch(
        payload: NativeFsWatchEventPayload,
        source: FsWatchSource,
        remappedPath: Path,
    ): FsWatchEvent? =
        when (payload.eventKind) {
            EVENT_KIND_CREATED ->
                FsWatchEvent.Created(
                    remappedPath,
                    source,
                    payload.isDirectory,
                    payload.needsRescan,
                )
            EVENT_KIND_MODIFIED ->
                FsWatchEvent.Modified(
                    remappedPath,
                    source,
                    payload.isDirectory,
                    payload.needsRescan,
                )
            EVENT_KIND_REMOVED ->
                FsWatchEvent.Removed(
                    remappedPath,
                    source,
                    payload.isDirectory,
                    payload.needsRescan,
                )
            else -> null
        }

    private fun remapPathForMatch(
        path: Path,
        match: LogicalMatch,
    ): Path? {
        if (path == match.matchedRoot) return match.originalRoot

        val relativeSuffix =
            try {
                match.matchedRoot.relativize(path).normalize()
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (relativeSuffix.isAbsolute) return null
        if (relativeSuffix.startsWith(Path.of(".."))) return null
        return match.originalRoot.resolve(relativeSuffix)
    }

    private fun logicalMatchForSource(
        logical: LogicalRegistrationState,
        source: FsWatchSource,
        path: Path,
    ): LogicalMatch? {
        // When the root is not a symlink itself, its resolved form is merely a second spelling of
        // the same directory — macOS /var -> /private/var, so every Files.createTempDirectory()
        // result — and FSEvents only ever reports that canonical spelling. Matching it is not
        // symlink following, so it must not depend on followSymlinks. Resolving a root that *is* a
        // symlink does follow one, and stays opt-in.
        val resolvedRootMatchable = !logical.rootIsSymbolicLink || config.followSymlinks
        val matchedRoot =
            when {
                coversPath(logical.originalRoot, source.recursive, path) -> logical.originalRoot
                resolvedRootMatchable && coversPath(logical.resolvedRoot, source.recursive, path) ->
                    logical.resolvedRoot
                else -> return null
            }
        return LogicalMatch(
            source = source,
            originalRoot = logical.originalRoot,
            matchedRoot = matchedRoot,
        )
    }

    private fun remapMovedEndpoint(
        path: Path,
        match: LogicalMatch?,
    ): Path? = match?.let { remapPathForMatch(path, it) }

    private fun coversPath(
        root: Path,
        recursive: Boolean,
        path: Path,
    ): Boolean =
        if (recursive) {
            path == root || path.startsWith(root)
        } else {
            path == root || path.parent == root
        }

    private fun failPendingRegistrationsLocked(closeFailure: FsWatchException) {
        nativeInstallationsByWatchKey.values
            .filter { it.state == SharedRegistrationState.PENDING }
            .forEach { it.failLocked(closeFailure) }
    }

    private fun emitWatcherOverflowAsync() {
        callbackScope.launch {
            try {
                eventFlow.emit(FsWatchEvent.Overflow(source = null))
            } finally {
                synchronized(stateLock) {
                    watcherOverflowPending = false
                }
            }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun notifyStateLockWaiters() {
        (stateLock as java.lang.Object).notifyAll()
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitOnStateLock() {
        (stateLock as java.lang.Object).wait()
    }

    private fun planWatchAcquisitionLocked(source: FsWatchSource): WatchAcquisitionStep {
        logicalRegistrationsBySource[source]?.nativeInstallation?.let { nativeInstallation ->
            return when (nativeInstallation.state) {
                SharedRegistrationState.PENDING ->
                    WatchAcquisitionStep.WaitForPending(
                        nativeInstallation,
                        throwOnFailure = true,
                    )
                SharedRegistrationState.ACTIVE ->
                    WatchAcquisitionStep.ReturnRegistration(
                        createLogicalRegistrationLocked(nativeInstallation, source),
                    )
                SharedRegistrationState.FAILED ->
                    throw nativeInstallation.failure ?: FsWatchException("Failed to watch path: ${source.root}")
            }
        }

        nativeInstallationsByWatchKey[NativeWatchKey(source.root, source.recursive)]?.let { nativeInstallation ->
            return when (nativeInstallation.state) {
                SharedRegistrationState.PENDING ->
                    WatchAcquisitionStep.WaitForPending(
                        nativeInstallation,
                        throwOnFailure = true,
                    )
                SharedRegistrationState.ACTIVE ->
                    WatchAcquisitionStep.ReturnRegistration(
                        createLogicalRegistrationLocked(nativeInstallation, source),
                    )
                SharedRegistrationState.FAILED ->
                    throw nativeInstallation.failure ?: FsWatchException("Failed to watch path: ${source.root}")
            }
        }

        selectCoveringInstallationLocked(source, SharedRegistrationState.ACTIVE)?.let { nativeInstallation ->
            return WatchAcquisitionStep.ReturnRegistration(createLogicalRegistrationLocked(nativeInstallation, source))
        }

        selectCoveringInstallationLocked(source, SharedRegistrationState.PENDING)?.let { nativeInstallation ->
            return WatchAcquisitionStep.WaitForPending(nativeInstallation, throwOnFailure = false)
        }

        selectInstallationsCoveredBy(source, SharedRegistrationState.PENDING)
            .firstOrNull()
            ?.let { nativeInstallation ->
                return WatchAcquisitionStep.WaitForPending(
                    nativeInstallation,
                    throwOnFailure = false,
                )
            }

        val nativeRegistrationId = nextNativeFsWatcherRegistrationId()
        val nativeInstallation =
            NativeWatchInstallation(
                nativeRegistrationId = nativeRegistrationId,
                nativeWatchKey = NativeWatchKey(source.root, source.recursive),
            )
        nativeInstallationsByWatchKey[nativeInstallation.nativeWatchKey] = nativeInstallation
        nativeInstallationsById[nativeRegistrationId] = nativeInstallation
        return WatchAcquisitionStep.CreateNativeWatch(nativeInstallation)
    }

    private fun createLogicalRegistrationLocked(
        nativeInstallation: NativeWatchInstallation,
        source: FsWatchSource,
    ): NativeFsWatchRegistration {
        nativeInstallation.acquireRegistrationLocked(source)
        return NativeFsWatchRegistration(source).also { activeRegistrations += it }
    }

    private fun NativeWatchInstallation.covers(source: FsWatchSource): Boolean =
        coversSource(
            coveringRoot = nativeWatchKey.root,
            coveringRecursive = nativeWatchKey.recursive,
            targetRoot = source.root,
            targetRecursive = source.recursive,
        )

    private fun selectCoveringInstallationLocked(
        source: FsWatchSource,
        state: SharedRegistrationState,
    ): NativeWatchInstallation? =
        nativeInstallationsById.values
            .asSequence()
            .filter { it.state == state && it.covers(source) }
            .sortedWith(nativeInstallationCoverageOrder)
            .firstOrNull()

    private fun selectInstallationsCoveredBy(
        source: FsWatchSource,
        state: SharedRegistrationState,
    ): List<NativeWatchInstallation> =
        nativeInstallationsById.values
            .asSequence()
            .filter { it.state == state }
            .filter { installation ->
                installation.nativeRegistrationId !=
                    logicalRegistrationsBySource[source]?.nativeInstallation?.nativeRegistrationId &&
                    coversSource(
                        coveringRoot = source.root,
                        coveringRecursive = source.recursive,
                        targetRoot = installation.nativeWatchKey.root,
                        targetRecursive = installation.nativeWatchKey.recursive,
                    )
            }.sortedWith(
                compareByDescending<NativeWatchInstallation> { it.nativeWatchKey.root.nameCount }
                    .thenBy { it.nativeRegistrationId },
            ).toList()

    private fun coversSource(
        coveringRoot: Path,
        coveringRecursive: Boolean,
        targetRoot: Path,
        targetRecursive: Boolean,
    ): Boolean {
        if (coveringRoot == targetRoot) {
            return coveringRecursive || !targetRecursive
        }
        if (!coveringRecursive) return false
        return targetRoot.startsWith(coveringRoot)
    }

    private fun createNativeWatchRegistration(
        source: FsWatchSource,
        nativeInstallation: NativeWatchInstallation,
    ): FsWatchRegistration {
        val watchSucceeded =
            NativeFsWatcherBridge.watchRegistration(
                watcherHandle = watcherHandle,
                registrationId = nativeInstallation.nativeRegistrationId,
                path = source.root.toString(),
                recursive = source.recursive,
                name = source.name,
            )

        var registration: NativeFsWatchRegistration? = null
        var failure: FsWatchException? = null
        var closedDuringNativeWatch = false
        var nativeRegistrationIdsToUnwatch = emptyList<Long>()
        synchronized(stateLock) {
            when {
                !watchSucceeded -> {
                    failure = FsWatchException("Failed to watch path: ${source.root}")
                    nativeInstallation.failLocked(failure)
                }
                closed -> {
                    failure = FsWatchException("Watcher closed while registering path: ${source.root}")
                    nativeInstallation.failLocked(failure)
                    closedDuringNativeWatch = true
                }
                else -> {
                    nativeInstallation.markActiveLocked()
                    registration = createLogicalRegistrationLocked(nativeInstallation, source)
                    nativeRegistrationIdsToUnwatch = migrateCoveredInstallationsLocked(nativeInstallation)
                }
            }
            notifyStateLockWaiters()
        }

        if (closedDuringNativeWatch) {
            NativeFsWatcherBridge.unwatchRegistration(watcherHandle, nativeInstallation.nativeRegistrationId)
        }
        nativeRegistrationIdsToUnwatch.forEach { nativeRegistrationId ->
            NativeFsWatcherBridge.unwatchRegistration(watcherHandle, nativeRegistrationId)
        }

        if (failure != null) {
            throw failure
        }
        return registration ?: throw FsWatchException("Failed to watch path: ${source.root}")
    }

    private fun awaitPendingRegistration(nativeInstallation: NativeWatchInstallation): Boolean {
        pendingRegistrationEnteredHookForTesting?.invoke()
        var pendingRegistrationResolvedHook: (() -> Unit)? = null
        synchronized(stateLock) {
            while (nativeInstallation.state == SharedRegistrationState.PENDING) {
                waitOnStateLock()
            }

            if (nativeInstallation.state == SharedRegistrationState.FAILED) {
                return false
            }

            pendingRegistrationResolvedHook = pendingRegistrationResolvedHookForTesting
        }

        pendingRegistrationResolvedHook?.invoke()
        return true
    }

    private data class LogicalRegistrationState(
        var nativeInstallation: NativeWatchInstallation,
        val originalRoot: Path,
        val resolvedRoot: Path,
        val rootIsSymbolicLink: Boolean,
        var refCount: Int,
    )

    private inner class NativeWatchInstallation(
        val nativeRegistrationId: Long,
        val nativeWatchKey: NativeWatchKey,
        var state: SharedRegistrationState = SharedRegistrationState.PENDING,
        var failure: FsWatchException? = null,
    ) {
        private val sourceRefCounts = LinkedHashMap<FsWatchSource, Int>()
        private val orderedSources = mutableListOf<FsWatchSource>()
        private var activeRefCount: Int = 0

        fun acquireRegistrationLocked(source: FsWatchSource) {
            activeRefCount += 1
            val logicalRegistration = logicalRegistrationsBySource[source]
            if (logicalRegistration == null) {
                logicalRegistrationsBySource[source] =
                    LogicalRegistrationState(
                        nativeInstallation = this,
                        originalRoot = source.root,
                        resolvedRoot = resolveRootPath(source.root),
                        rootIsSymbolicLink = Files.isSymbolicLink(source.root),
                        refCount = 1,
                    )
            } else {
                check(logicalRegistration.nativeInstallation == this)
                logicalRegistration.refCount += 1
            }
            val currentSourceRefCount = sourceRefCounts[source] ?: 0
            if (currentSourceRefCount == 0) {
                insertOrderedSourceLocked(source)
            }
            sourceRefCounts[source] = currentSourceRefCount + 1
        }

        fun absorbRegistrationLocked(
            source: FsWatchSource,
            refCount: Int,
        ) {
            check(refCount > 0)
            val logicalRegistration =
                logicalRegistrationsBySource[source]
                    ?: throw FsWatchException("Missing logical registration for migrated source: $source")
            logicalRegistration.nativeInstallation = this
            activeRefCount += refCount
            val currentSourceRefCount = sourceRefCounts[source] ?: 0
            if (currentSourceRefCount == 0) {
                insertOrderedSourceLocked(source)
            }
            sourceRefCounts[source] = currentSourceRefCount + refCount
        }

        fun logicalSourcesLocked(): List<FsWatchSource> = orderedSources.toList()

        fun drainLogicalRefCountsLocked(): List<Pair<FsWatchSource, Int>> {
            val drained = sourceRefCounts.entries.map { it.key to it.value }
            sourceRefCounts.clear()
            orderedSources.clear()
            activeRefCount = 0
            return drained
        }

        fun releaseRegistrationLocked(source: FsWatchSource): Int {
            activeRefCount -= 1
            val logicalRegistration = logicalRegistrationsBySource[source]
            if (logicalRegistration == null || logicalRegistration.refCount <= 1) {
                logicalRegistrationsBySource.remove(source)
            } else {
                logicalRegistration.refCount -= 1
            }
            val currentSourceRefCount = sourceRefCounts[source]
            if (currentSourceRefCount == null || currentSourceRefCount <= 1) {
                sourceRefCounts.remove(source)
                orderedSources.remove(source)
            } else {
                sourceRefCounts[source] = currentSourceRefCount - 1
            }
            return activeRefCount
        }

        fun markActiveLocked() {
            check(state == SharedRegistrationState.PENDING)
            state = SharedRegistrationState.ACTIVE
            check(activeRefCount == 0)
        }

        fun failLocked(watchFailure: FsWatchException) {
            state = SharedRegistrationState.FAILED
            failure = watchFailure
            sourceRefCounts.keys.toList().forEach { source ->
                logicalRegistrationsBySource.remove(source)?.also { logicalRegistration ->
                    check(logicalRegistration.nativeInstallation == this)
                }
            }
            sourceRefCounts.clear()
            orderedSources.clear()
            activeRefCount = 0
            nativeInstallationsByWatchKey.remove(nativeWatchKey, this)
            nativeInstallationsById.remove(nativeRegistrationId, this)
        }

        private fun insertOrderedSourceLocked(source: FsWatchSource) {
            if (orderedSources.contains(source)) return
            val sourceDepth = source.root.nameCount
            val insertAt =
                orderedSources.indexOfFirst { existing ->
                    existing.root.nameCount < sourceDepth
                }
            if (insertAt >= 0) {
                orderedSources.add(insertAt, source)
            } else {
                orderedSources.add(source)
            }
        }
    }

    private inner class NativeFsWatchRegistration(
        override val source: FsWatchSource,
    ) : FsWatchRegistration {
        @Volatile
        override var active: Boolean = true
            private set

        override fun close() {
            val nativeRegistrationIdToUnwatch =
                synchronized(stateLock) {
                    if (!active || closed) {
                        null
                    } else {
                        removeFromSharedStateLocked()
                    }
                }

            if (nativeRegistrationIdToUnwatch != null) {
                NativeFsWatcherBridge.unwatchRegistration(watcherHandle, nativeRegistrationIdToUnwatch)
            }
        }

        fun deactivate() {
            active = false
        }

        fun removeFromSharedStateLocked(): Long? {
            if (!active) return null

            active = false
            activeRegistrations.remove(this)

            val nativeInstallation = logicalRegistrationsBySource[source]?.nativeInstallation ?: return null
            val remainingRefs = nativeInstallation.releaseRegistrationLocked(source)
            if (remainingRefs > 0) return null

            nativeInstallationsByWatchKey.remove(nativeInstallation.nativeWatchKey)
            nativeInstallationsById.remove(nativeInstallation.nativeRegistrationId)
            return nativeInstallation.nativeRegistrationId
        }
    }

    private sealed interface WatchAcquisitionStep {
        data class CreateNativeWatch(
            val nativeInstallation: NativeWatchInstallation,
        ) : WatchAcquisitionStep

        data class WaitForPending(
            val nativeInstallation: NativeWatchInstallation,
            val throwOnFailure: Boolean,
        ) : WatchAcquisitionStep

        data class ReturnRegistration(
            val registration: NativeFsWatchRegistration,
        ) : WatchAcquisitionStep
    }

    private enum class SharedRegistrationState {
        PENDING,
        ACTIVE,
        FAILED,
    }

    private fun migrateCoveredInstallationsLocked(destination: NativeWatchInstallation): List<Long> {
        val destinationSource =
            FsWatchSource(
                destination.nativeWatchKey.root,
                destination.nativeWatchKey.recursive,
            )
        val coveredInstallations =
            selectInstallationsCoveredBy(destinationSource, SharedRegistrationState.ACTIVE).filterNot {
                it.nativeRegistrationId == destination.nativeRegistrationId
            }
        if (coveredInstallations.isEmpty()) return emptyList()

        coveredInstallations.forEach { coveredInstallation ->
            coveredInstallation.drainLogicalRefCountsLocked().forEach { (source, refCount) ->
                destination.absorbRegistrationLocked(source, refCount)
            }
            nativeInstallationsByWatchKey.remove(coveredInstallation.nativeWatchKey, coveredInstallation)
            nativeInstallationsById.remove(coveredInstallation.nativeRegistrationId, coveredInstallation)
        }
        return coveredInstallations.map { it.nativeRegistrationId }
    }

    private companion object {
        val nativeInstallationCoverageOrder: Comparator<NativeWatchInstallation> =
            compareBy<NativeWatchInstallation>(
                { it.nativeWatchKey.root.nameCount },
                { if (it.nativeWatchKey.recursive) 0 else 1 },
                { it.nativeRegistrationId },
            )
    }
}

private fun resolveRootPath(path: Path): Path =
    try {
        path.toRealPath()
    } catch (_: Exception) {
        path
    }

package dev.nucleusframework.store.microsoft.internal

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.Platform
import java.util.logging.Level
import java.util.logging.Logger

/** The asynchronous, JSON-in/JSON-out channel to a native store backend. */
internal interface NativeTransport {
    val isAvailable: Boolean

    /** Routes responses and events to [receiver]; must be called before any [request]. */
    fun attach(receiver: NativeReceiver)

    /** Starts the out-of-band listener; `false` when the platform cannot listen. */
    fun startUpdates(): Boolean

    /** Starts request [id]; the answer arrives once through [NativeReceiver.onResponse]. */
    fun request(
        id: Long,
        method: String,
        args: String,
    )

    /** Cancels request [id]; it is still answered (with a `cancelled` error). */
    fun cancel(id: Long)
}

/** Receives what a [NativeTransport] sends back, from any thread. */
internal interface NativeReceiver {
    fun onResponse(
        id: Long,
        envelope: String,
    )

    fun onEvent(json: String)
}

private const val LIBRARY_NAME = "nucleus_msstore"

/**
 * JNI binding of `nucleus_msstore`, the Rust `Windows.Services.Store` bridge: four exported functions,
 * two callbacks.
 */
internal object NativeStoreBridge : NativeTransport {
    private val logger = Logger.getLogger(NativeStoreBridge::class.java.name)

    private val loaded: Boolean by lazy {
        when (Platform.Current) {
            Platform.Windows ->
                runCatching { NativeLibraryLoader.load(LIBRARY_NAME, NativeStoreBridge::class.java) }
                    .onFailure { logger.log(Level.WARNING, "Failed to load $LIBRARY_NAME", it) }
                    .getOrDefault(false)
            else -> false
        }
    }

    @Volatile
    private var receiver: NativeReceiver? = null

    override val isAvailable: Boolean
        get() = loaded

    override fun attach(receiver: NativeReceiver) {
        this.receiver = receiver
    }

    override fun startUpdates(): Boolean = loaded && nativeInit()

    override fun request(
        id: Long,
        method: String,
        args: String,
    ) {
        nativeRequest(id, method, args)
    }

    override fun cancel(id: Long) {
        if (loaded) nativeCancel(id)
    }

    /** Requests the native side still tracks; for tests. */
    fun inFlight(): Int = if (loaded) nativeInFlight() else 0

    @JvmStatic
    private external fun nativeInit(): Boolean

    @JvmStatic
    private external fun nativeRequest(
        id: Long,
        method: String,
        args: String,
    )

    @JvmStatic
    private external fun nativeCancel(id: Long)

    @JvmStatic
    private external fun nativeInFlight(): Int

    /** Called by native code, on a native worker thread. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "unused")
    fun onResponse(
        id: Long,
        envelope: String,
    ) {
        try {
            receiver?.onResponse(id, envelope)
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "In-app purchase response handler failed", t)
        }
    }

    /** Called by native code, on a native thread. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "unused")
    fun onEvent(json: String) {
        try {
            receiver?.onEvent(json)
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "In-app purchase event handler failed", t)
        }
    }
}

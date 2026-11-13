package dev.nucleusframework.store.mac.internal

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.core.runtime.Platform
import java.util.logging.Level
import java.util.logging.Logger

/** The asynchronous, JSON-in/JSON-out channel to the native StoreKit bridge. */
internal interface NativeTransport {
    val isAvailable: Boolean

    /** Routes responses and events to [receiver]; must be called before any [request]. */
    fun attach(receiver: NativeReceiver)

    /** Starts the out-of-band transaction listener; `false` when it cannot run (not an app). */
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

private const val LIBRARY_NAME = "nucleus_macstore"

/** JNI binding of `nucleus_macstore`, the Swift StoreKit 2 bridge. */
internal object NativeMacStoreBridge : NativeTransport {
    private val logger = Logger.getLogger(NativeMacStoreBridge::class.java.name)

    private val loaded: Boolean by lazy {
        when (Platform.Current) {
            Platform.MacOS ->
                runCatching { NativeLibraryLoader.load(LIBRARY_NAME, NativeMacStoreBridge::class.java) }
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

    override fun startUpdates(): Boolean = loaded && nativeStartUpdates()

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
    private external fun nativeRequest(
        id: Long,
        method: String,
        args: String,
    )

    @JvmStatic
    private external fun nativeCancel(id: Long)

    @JvmStatic
    private external fun nativeStartUpdates(): Boolean

    @JvmStatic
    private external fun nativeInFlight(): Int

    /** Called by native code, on a StoreKit worker thread. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "unused")
    fun onResponse(
        id: Long,
        envelope: String,
    ) {
        try {
            receiver?.onResponse(id, envelope)
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "Mac App Store response handler failed", t)
        }
    }

    /** Called by native code, on a StoreKit worker thread. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "unused")
    fun onEvent(json: String) {
        try {
            receiver?.onEvent(json)
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "Mac App Store event handler failed", t)
        }
    }
}

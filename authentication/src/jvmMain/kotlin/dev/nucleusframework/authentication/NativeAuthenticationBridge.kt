package dev.nucleusframework.authentication

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val LIBRARY_NAME = "nucleus_authentication"

/**
 * JNI surface of `nucleus_authentication`. Requests are identified by ids the Kotlin side
 * allocates, so an outcome that races a cancellation finds nothing registered and is
 * dropped here rather than in native code.
 */
internal object NativeAuthenticationBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeAuthenticationBridge::class.java)
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, (Result<Unit>) -> Unit>()

    val isLoaded: Boolean
        get() = loaded

    /** Blocks on Windows (WinRT) and Linux (D-Bus): call it off the UI thread. */
    fun availability(policy: AuthenticationPolicy): AuthenticationAvailability {
        val biometry = IntArray(1)
        val message = arrayOfNulls<String>(1)
        val code = nativeAvailability(policy.ordinal, biometry, message)
        return AuthenticationAvailability(
            biometry = BiometryType.entries.getOrElse(biometry[0]) { BiometryType.Unknown },
            error = if (code == 0) null else errorOf(code),
            message = message[0],
        )
    }

    suspend fun authenticate(
        prompt: AuthenticationPrompt,
        policy: AuthenticationPolicy,
        parentHandle: Long,
    ): Unit =
        suspendCancellableCoroutine { continuation ->
            val id = nextId.incrementAndGet()
            pending[id] = continuation::resumeWith
            val message = arrayOfNulls<String>(1)
            val code =
                nativeAuthenticate(
                    requestId = id,
                    policy = policy.ordinal,
                    reason = prompt.reason,
                    title = prompt.title,
                    cancelLabel = prompt.cancelLabel,
                    linuxActionId = prompt.linuxActionId,
                    parentHandle = parentHandle,
                    message = message,
                )
            if (code != 0) {
                pending.remove(id)
                continuation.resumeWithException(AuthenticationException(errorOf(code), message[0]))
                return@suspendCancellableCoroutine
            }
            // Installed once the prompt exists: a caller already cancelled dismisses it at once.
            continuation.invokeOnCancellation {
                if (pending.remove(id) != null) nativeCancel(id)
            }
        }

    /** Called from native code, on the platform's thread, once per started request. */
    @JvmStatic
    fun onResult(
        requestId: Long,
        code: Int,
        message: String?,
    ) {
        val resume = pending.remove(requestId) ?: return
        resume(
            if (code == 0) {
                Result.success(Unit)
            } else {
                Result.failure(AuthenticationException(errorOf(code), message))
            },
        )
    }

    /** The native codes are the [AuthenticationError] ordinals plus one, `0` being success. */
    private fun errorOf(code: Int): AuthenticationError =
        AuthenticationError.entries.getOrElse(code - 1) { AuthenticationError.Platform }

    @JvmStatic
    private external fun nativeAvailability(
        policy: Int,
        biometry: IntArray,
        message: Array<String?>,
    ): Int

    @Suppress("LongParameterList")
    @JvmStatic
    private external fun nativeAuthenticate(
        requestId: Long,
        policy: Int,
        reason: String,
        title: String?,
        cancelLabel: String?,
        linuxActionId: String?,
        parentHandle: Long,
        message: Array<String?>,
    ): Int

    @JvmStatic
    private external fun nativeCancel(requestId: Long)
}

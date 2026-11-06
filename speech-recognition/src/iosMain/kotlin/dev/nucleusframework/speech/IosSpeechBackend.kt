@file:OptIn(ExperimentalForeignApi::class)

package dev.nucleusframework.speech

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

internal actual fun platformSpeechBackend(): SpeechBackend = IosSpeechBackend

internal actual fun reportListenerFailure(failure: Throwable) {
    println("[NucleusSpeech] Speech recognition listener threw: ${failure.stackTraceToString()}")
}

/** SFSpeechRecognizer + AVAudioEngine. Session state lives on the main queue. */
internal object IosSpeechBackend : SpeechBackend {
    // Main queue only.
    val sessions = mutableMapOf<Long, IosSpeechSession>()

    override val isSupported: Boolean = true

    override val engineName: String = "apple-sfspeech"

    override fun start(
        id: Long,
        options: SpeechRecognitionOptions,
    ) {
        onMain {
            val session = IosSpeechSession(id, options)
            sessions[id] = session
            session.authorize()
        }
    }

    override fun stop(
        id: Long,
        cancel: Boolean,
    ) {
        onMain { sessions[id]?.stop(cancel) }
    }
}

internal fun onMain(action: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), action)
}

/** A main-queue action that can still be called off before it runs. */
internal class Delayed {
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}

internal fun after(
    seconds: Double,
    action: () -> Unit,
): Delayed {
    val handle = Delayed()
    val delay = (seconds * NSEC_PER_SEC.toDouble()).toLong()
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delay), dispatch_get_main_queue()) {
        if (!handle.cancelled) action()
    }
    return handle
}

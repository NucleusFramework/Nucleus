@file:OptIn(ExperimentalForeignApi::class)

package dev.nucleusframework.speech

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.sizeOf
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSLock
import platform.posix.memcpy

// Audio kept while a recognizer finalizes an utterance, before giving up on it.
private const val MAX_PENDING_SECONDS = 4.0

/**
 * Where the audio tap's buffers go. The engine keeps recording while a recognizer finalizes an
 * utterance; only that short interval needs copies, otherwise buffers go straight to the
 * request. Called from the audio thread and the main queue, so everything is under [lock], and
 * no engine operation or UI callback runs while it is held.
 */
internal class AudioCapture {
    private val lock = NSLock()
    private var appendToRequest: ((AVAudioPCMBuffer) -> Unit)? = null
    private var requestGeneration: Long? = null
    private val pending = mutableListOf<AVAudioPCMBuffer>()
    private var pendingSeconds = 0.0
    private var active = false
    private var overflowed = false

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun prepareRequest(generation: Long): Unit =
        locked {
            active = true
            requestGeneration = generation
            appendToRequest = null
        }

    /** Binds the request of [generation], unless its task already finished and detached it. */
    fun beginRequest(
        generation: Long,
        append: (AVAudioPCMBuffer) -> Unit,
    ): Unit =
        locked {
            if (requestGeneration != generation) return
            active = true
            appendToRequest = append
            pending.forEach(append)
            pending.clear()
            pendingSeconds = 0.0
        }

    /** Detaches the current request, or only the one of [generation] when given. */
    fun endRequest(generation: Long? = null): Unit =
        locked {
            if (generation != null && requestGeneration != generation) return
            requestGeneration = null
            appendToRequest = null
        }

    /**
     * Returns `false` once, on overflow: further buffers are dropped until the main queue
     * handles that terminal error and stops capture.
     */
    fun append(buffer: AVAudioPCMBuffer): Boolean =
        locked {
            if (!active || overflowed) return true
            appendToRequest?.let {
                it(buffer)
                return true
            }
            val seconds = buffer.frameLength.toDouble() / buffer.format.sampleRate
            val copy =
                if (seconds.isFinite() &&
                    pendingSeconds + seconds <= MAX_PENDING_SECONDS
                ) {
                    copyOf(buffer)
                } else {
                    null
                }
            if (copy == null) {
                overflowed = true
                pending.clear()
                pendingSeconds = 0.0
                return false
            }
            pending += copy
            pendingSeconds += seconds
            true
        }

    val hasPendingAudio: Boolean
        get() = locked { pending.isNotEmpty() }

    fun stop(discardPending: Boolean = true): Unit =
        locked {
            active = false
            requestGeneration = null
            appendToRequest = null
            if (discardPending) {
                pending.clear()
                pendingSeconds = 0.0
            }
        }
}

/** A copy of a float PCM buffer (the input node's format), or null if it cannot be made. */
private fun copyOf(buffer: AVAudioPCMBuffer): AVAudioPCMBuffer? {
    val frames = buffer.frameLength
    val copy = AVAudioPCMBuffer(pCMFormat = buffer.format, frameCapacity = frames)
    copy.frameLength = frames
    val source = buffer.floatChannelData ?: return null
    val target = copy.floatChannelData ?: return null
    val format = buffer.format
    val planes = if (format.interleaved) 1 else format.channelCount.toInt()
    val samples = if (format.interleaved) frames.toLong() * format.channelCount.toLong() else frames.toLong()
    for (plane in 0 until planes) {
        memcpy(target[plane], source[plane], (samples * sizeOf<FloatVar>()).toULong())
    }
    return copy
}

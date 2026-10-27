package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/**
 * Runs a callback at the next DWM composition, off the event loop (#484).
 *
 * The pacer of a frame held inside the Windows modal size/move loop, where
 * neither the swap interval nor the loop paces anything: `DwmFlush` blocks
 * until DWM's next composition, so the frame it wakes starts on the
 * display's beat. A JVM timer would wake at the OS timer's granularity
 * instead (15.6 ms by default), capping an animation at ~64 fps on any
 * display. Should `DwmFlush` keep returning before [wake]'s deadline
 * (composition off, or failing) the thread parks until the deadline, so a
 * held frame can never spin.
 */
internal object WindowsCompositionWaker {
    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Nucleus-Tao-CompositionWaker").apply { isDaemon = true }
        }

    /** Runs [callback] on this waker's thread at the first composition at or after [dueNanos] ([System.nanoTime]). */
    fun wake(
        dueNanos: Long,
        callback: () -> Unit,
    ) {
        executor.execute {
            // The deadline is under a display period away: one composition, two
            // when the first one comes before it.
            var flushes = 0
            while (System.nanoTime() < dueNanos && flushes < MAX_FLUSHES && flush()) flushes++
            val left = dueNanos - System.nanoTime()
            if (left > 0L) LockSupport.parkNanos(left)
            callback()
        }
    }

    private fun flush(): Boolean = NativeTaoWindowsDecoBridge.isLoaded && NativeTaoWindowsDecoBridge.nativeDwmFlush()

    private const val MAX_FLUSHES = 2
}

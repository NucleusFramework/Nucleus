package dev.nucleusframework.energymanager

import java.util.concurrent.atomic.AtomicBoolean

/**
 * An acquired [EnergyManager] awake request.
 *
 * Closing the handle drops this request. The process stays awake while any
 * other handle, or an unmatched [EnergyManager.keepAwake] call, is still
 * active. [close] is idempotent and thread-safe.
 */
public class AwakeHandle internal constructor(
    /** Mode this handle requested. */
    public val mode: AwakeMode,
    private val onClose: (AwakeHandle) -> Unit,
) : AutoCloseable {
    private val active = AtomicBoolean(true)

    /** `false` after [close]. */
    public val isActive: Boolean
        get() = active.get()

    /** Drops this request. Idempotent. */
    override fun close() {
        if (active.compareAndSet(true, false)) {
            onClose(this)
        }
    }

    internal fun markInactive() {
        active.set(false)
    }
}

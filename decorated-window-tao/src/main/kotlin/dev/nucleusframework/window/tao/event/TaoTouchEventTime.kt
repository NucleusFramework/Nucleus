package dev.nucleusframework.window.tao.event

/** Maps GDK's 32-bit millisecond clock to the JVM event clock without retiming queued samples. */
internal class TaoTouchEventTime {
    private var lastTimestamp: Int? = null
    private var lastDeliveryTimeMillis = 0L
    private var eventTimeMillis = 0L

    fun toMillis(
        timestampMillis: Long,
        deliveryTimeMillis: Long,
    ): Long {
        // GDK_CURRENT_TIME means the event has no timestamp. Re-anchor after
        // falling back so the next real sample cannot resume an older timeline.
        if (timestampMillis == 0L) {
            lastTimestamp = null
            return deliveryTimeMillis
        }

        val timestamp = timestampMillis.toInt()
        val previous = lastTimestamp
        eventTimeMillis =
            if (previous == null || deliveryTimeMillis - lastDeliveryTimeMillis > Int.MAX_VALUE) {
                // GDK's origin is platform-defined. Also re-anchor after an
                // idle interval too long to distinguish wraparound from reversal.
                deliveryTimeMillis
            } else {
                // Int subtraction wraps, preserving short intervals across both
                // the signed boundary and GDK's unsigned 32-bit wraparound.
                eventTimeMillis + (timestamp - previous).toLong()
            }
        lastTimestamp = timestamp
        lastDeliveryTimeMillis = deliveryTimeMillis
        return eventTimeMillis
    }
}

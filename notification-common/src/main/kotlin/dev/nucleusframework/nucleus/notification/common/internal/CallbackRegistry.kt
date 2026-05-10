package dev.nucleusframework.nucleus.notification.common.internal

import java.util.concurrent.ConcurrentHashMap

internal object CallbackRegistry {
    private val registry = ConcurrentHashMap<String, NotificationCallbacks>()

    fun register(
        platformId: String,
        callbacks: NotificationCallbacks,
    ) {
        registry[platformId] = callbacks
    }

    fun get(platformId: String): NotificationCallbacks? = registry[platformId]

    fun remove(platformId: String): NotificationCallbacks? = registry.remove(platformId)
}

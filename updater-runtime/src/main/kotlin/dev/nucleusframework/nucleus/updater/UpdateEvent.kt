package dev.nucleusframework.nucleus.updater

/**
 * Represents a completed update detected at application startup.
 * Returned by [NucleusUpdater.consumeUpdateEvent] on the first launch after an update.
 */
data class UpdateEvent(
    val previousVersion: String,
    val newVersion: String,
    val updateLevel: UpdateLevel,
)

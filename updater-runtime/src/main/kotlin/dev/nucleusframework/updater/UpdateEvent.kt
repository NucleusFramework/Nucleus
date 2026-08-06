package dev.nucleusframework.updater

/**
 * Represents a completed update detected at application startup.
 * Returned by [NucleusUpdater.consumeUpdateEvent] on the first launch after an update.
 */
public data class UpdateEvent(
    val previousVersion: String,
    val newVersion: String,
    val updateLevel: UpdateLevel,
)

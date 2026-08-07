package dev.nucleusframework.updater

import dev.nucleusframework.updater.exception.UpdateException

public sealed class UpdateResult {
    public data class Available(
        val info: UpdateInfo,
        val level: UpdateLevel,
    ) : UpdateResult()

    public data object NotAvailable : UpdateResult()

    public data class Error(
        val exception: UpdateException,
    ) : UpdateResult()
}

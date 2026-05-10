package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.updater.exception.UpdateException

sealed class UpdateResult {
    data class Available(
        val info: UpdateInfo,
        val level: UpdateLevel,
    ) : UpdateResult()

    data object NotAvailable : UpdateResult()

    data class Error(
        val exception: UpdateException,
    ) : UpdateResult()
}

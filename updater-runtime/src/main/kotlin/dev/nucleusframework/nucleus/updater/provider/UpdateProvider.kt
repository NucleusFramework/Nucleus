package dev.nucleusframework.nucleus.updater.provider

import dev.nucleusframework.nucleus.core.runtime.Platform

interface UpdateProvider {
    fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String

    fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String

    fun authHeaders(): Map<String, String> = emptyMap()
}

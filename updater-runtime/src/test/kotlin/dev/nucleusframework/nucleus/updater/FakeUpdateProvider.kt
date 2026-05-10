package dev.nucleusframework.nucleus.updater

import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.updater.provider.UpdateProvider

class FakeUpdateProvider : UpdateProvider {
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "https://example.com/updates/$channel"

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "https://example.com/downloads/$version/$fileName"
}

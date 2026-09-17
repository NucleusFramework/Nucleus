package dev.nucleusframework.updater.delta

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.provider.UpdateProvider

/**
 * Provider for the tests that build an [dev.nucleusframework.updater.UpdateInfo] themselves: the
 * artifact URLs are already absolute, so nothing has to be resolved.
 */
internal class FakeUpdateProviderForDelta : UpdateProvider {
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = error("not used: the update info is supplied directly")

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = fileName
}

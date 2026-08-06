package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform

public class GenericProvider(
    public val baseUrl: String,
) : UpdateProvider {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val suffix = platformSuffix(platform)
        val fileName = if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
        return "$normalizedBaseUrl/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$normalizedBaseUrl/$fileName"

    private fun platformSuffix(platform: Platform): String =
        when (platform) {
            Platform.Windows -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux"
            Platform.Unknown -> ""
        }
}

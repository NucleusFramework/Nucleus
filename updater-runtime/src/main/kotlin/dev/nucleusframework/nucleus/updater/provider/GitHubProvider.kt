package dev.nucleusframework.nucleus.updater.provider

import dev.nucleusframework.nucleus.core.runtime.Platform

class GitHubProvider(
    val owner: String,
    val repo: String,
    val token: String? = null,
) : UpdateProvider {
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val suffix = platformSuffix(platform)
        val fileName = if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
        return "https://github.com/$owner/$repo/releases/latest/download/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "https://github.com/$owner/$repo/releases/download/v$version/$fileName"

    override fun authHeaders(): Map<String, String> =
        if (token != null) {
            mapOf("Authorization" to "token $token")
        } else {
            emptyMap()
        }

    private fun platformSuffix(platform: Platform): String =
        when (platform) {
            Platform.Windows -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux"
            Platform.Unknown -> ""
        }
}

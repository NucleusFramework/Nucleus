package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.Version

/**
 * Which release a client takes, by electron-updater's rules (`GitHubProvider.getLatestVersion`).
 * Shared by [GitHubProvider], over the releases feed, and [PrivateGitHubProvider], over the REST API.
 */
internal object GitHubReleaseChannels {
    private const val ALPHA = "alpha"
    private const val BETA = "beta"

    /**
     * The channel the client is on: [channel], or for Nucleus' `latest` (electron-updater's unset
     * channel) the first pre-release identifier of [currentVersion]; `null` when that is a release.
     */
    fun clientChannel(
        channel: String,
        currentVersion: String?,
    ): String? =
        if (GitHubReleases.isStable(channel)) currentVersion?.let(GitHubReleases::prereleaseChannel) else channel

    /**
     * The tag among [tags] that a client on [clientChannel] updates to: the highest version it is
     * eligible for. Tags that are not SemVer versions are skipped, and channels are compared exactly
     * (`Beta` is not `beta`):
     * - `alpha`: tags on `alpha`, `beta` or no channel (a release);
     * - `beta`: tags on `beta` or no channel, never `alpha`;
     * - any other channel: tags on exactly that channel;
     * - none (a release with pre-releases allowed): every tag.
     *
     * electron-updater takes the first eligible tag in the releases feed instead, which GitHub
     * orders by release date: a stable hotfix published after a beta hides that beta from beta
     * clients until another beta ships.
     */
    fun selectTag(
        tags: List<String>,
        clientChannel: String?,
    ): String? =
        tags
            .filter(GitHubReleases::isReleaseTag)
            .filter { clientChannel == null || follows(clientChannel, GitHubReleases.prereleaseChannel(it)) }
            .maxByOrNull { Version.fromString(it.removePrefix("v")) }

    /** The manifest [tag] is read from: its own channel's for a pre-release, [channel]'s otherwise. */
    fun manifestFileName(
        tag: String,
        channel: String,
        platform: Platform,
    ): String = GitHubReleases.metadataFileName(GitHubReleases.prereleaseChannel(tag) ?: channel, platform)

    /** The release manifest, read instead when the tag publishes none for its channel. */
    fun latestFileName(platform: Platform): String =
        GitHubReleases.metadataFileName(GitHubReleases.LATEST_CHANNEL, platform)

    private fun follows(
        client: String,
        tagChannel: String?,
    ): Boolean =
        when {
            tagChannel == client -> true
            client == ALPHA -> tagChannel == null || tagChannel == BETA
            client == BETA -> tagChannel == null
            else -> false
        }
}

package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import java.io.File

/**
 * Reads updates from a directory on this machine — typically the packaging output of the next
 * version (`build/compose/binaries/main/nsis`), which already holds the artifact, its block map and
 * the `<channel>[-mac|-linux].yml` manifest the Nucleus plugin writes next to it.
 *
 * Meant for testing an update end to end without publishing it anywhere, the way Squirrel and
 * Velopack read a local release directory. Everything but the transport is the production path:
 * the manifest is parsed, the artifact selected and its SHA-512 verified, the installer run.
 * Differential downloads need HTTP range requests, so a local feed always downloads (copies) the
 * whole artifact; serve the directory over loopback HTTP (`./gradlew serveUpdateFeed`) to exercise
 * them too.
 *
 * An installed app can be pointed at a directory without changing its code: see
 * [dev.nucleusframework.updater.UpdaterConfig.allowLaunchOverrides].
 */
public class LocalFileProvider(
    directory: File,
) : UpdateProvider {
    /** The feed directory, made absolute. */
    public val directory: File = directory.absoluteFile.normalize()

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val suffix =
            when (platform) {
                Platform.MacOS -> "-mac"
                Platform.Linux -> "-linux"
                Platform.Windows, Platform.Unknown -> ""
            }
        return fileUrl("$channel$suffix.yml")
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = fileUrl(fileName)

    /**
     * Resolves [fileName] inside [directory]: a manifest naming `../elsewhere` must not reach
     * outside the feed.
     */
    private fun fileUrl(fileName: String): String {
        val file = File(directory, fileName).normalize()
        require(file.toPath().startsWith(directory.toPath())) {
            "Update file '$fileName' resolves outside the feed directory $directory"
        }
        return file.toURI().toString()
    }
}

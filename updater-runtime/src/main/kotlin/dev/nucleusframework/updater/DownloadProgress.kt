package dev.nucleusframework.updater

import java.io.File

/**
 * Progress of an update download.
 *
 * [bytesDownloaded] and [totalBytes] count the bytes actually transferred, which for a differential
 * download ([isDifferential]) is only the changed part of the artifact — so [percent] stays a
 * faithful measure of the wait, and [totalBytes] is then smaller than the artifact itself.
 */
public data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Double,
    val file: File? = null,
    val isDifferential: Boolean = false,
)

package dev.nucleusframework.nucleus.updater

import java.io.File

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Double,
    val file: File? = null,
)

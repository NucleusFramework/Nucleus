package dev.nucleusframework.updater

public data class UpdateInfo(
    val version: String,
    val releaseDate: String,
    val files: List<UpdateFile>,
    val currentFile: UpdateFile,
)

public data class UpdateFile(
    val url: String,
    val sha512: String,
    val size: Long,
    val blockMapSize: Long? = null,
    val fileName: String,
)

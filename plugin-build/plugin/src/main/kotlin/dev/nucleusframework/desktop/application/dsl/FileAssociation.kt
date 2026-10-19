package dev.nucleusframework.desktop.application.dsl

import java.io.File
import java.io.Serializable

@Suppress("SerialVersionUIDInSerializableClass") // Gradle plugin data class, serialVersionUID not needed
internal data class FileAssociation(
    val mimeType: String,
    val extension: String,
    val description: String,
    val iconFile: File?,
) : Serializable

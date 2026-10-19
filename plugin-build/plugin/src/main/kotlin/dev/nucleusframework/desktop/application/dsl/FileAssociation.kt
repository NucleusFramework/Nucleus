package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.io.Serializable

@Suppress("SerialVersionUIDInSerializableClass") // Gradle plugin data class, serialVersionUID not needed
internal data class FileAssociation(
    @get:Input val mimeType: String,
    @get:Input val extension: String,
    @get:Input val description: String,
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: File?,
) : Serializable

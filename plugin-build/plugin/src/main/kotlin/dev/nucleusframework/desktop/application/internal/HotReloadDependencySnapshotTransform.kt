package dev.nucleusframework.desktop.application.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File

@CacheableTransform
internal abstract class HotReloadDependencySnapshotTransform :
    TransformAction<HotReloadDependencySnapshotTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input
        val includedBuildDirectories: ListProperty<String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val isIncludedBuildArtifact =
            parameters.includedBuildDirectories.get().any { directory ->
                input.toPath().startsWith(File(directory).toPath())
            }

        if (isIncludedBuildArtifact) {
            input.copyTo(target = outputs.file(input.name))
        } else {
            outputs.file(inputArtifact)
        }
    }
}

private val HOT_RELOAD_DEPENDENCY_SNAPSHOT: Attribute<Boolean> =
    Attribute.of("dev.nucleusframework.hot-reload-dependency-snapshot", Boolean::class.javaObjectType)

internal fun configureHotReloadDependencySnapshots(project: Project) {
    val includedBuildDirectories = project.gradle.includedBuilds.map { it.projectDir.absolutePath }
    if (includedBuildDirectories.isEmpty()) {
        return
    }

    project.dependencies.registerTransform(HotReloadDependencySnapshotTransform::class.java) { spec ->
        spec.from.attribute(HOT_RELOAD_DEPENDENCY_SNAPSHOT, false)
        spec.to.attribute(HOT_RELOAD_DEPENDENCY_SNAPSHOT, true)
        spec.parameters.includedBuildDirectories.set(includedBuildDirectories)
    }

    project.dependencies.artifactTypes
        .maybeCreate("jar")
        .attributes
        .attribute(HOT_RELOAD_DEPENDENCY_SNAPSHOT, false)

    val jarLibraryElements = project.objects.named(LibraryElements::class.java, LibraryElements.JAR)
    project.configurations.configureEach { configuration ->
        if (configuration.name.contains("HotReload", ignoreCase = true) &&
            configuration.name.endsWith("RuntimeClasspath", ignoreCase = true)
        ) {
            configuration.attributes.attribute(HOT_RELOAD_DEPENDENCY_SNAPSHOT, true)
            configuration.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, jarLibraryElements)
        }
    }
}

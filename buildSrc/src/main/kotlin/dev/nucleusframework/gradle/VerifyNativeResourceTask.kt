package dev.nucleusframework.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Fails when the host's native library is missing from the module resources.
 *
 * A task type rather than a `doLast { }` in the build script: a script lambda lives in a
 * script-local class loader that the configuration cache cannot encode once a continuous build
 * (`-t`, which Compose Hot Reload's `--auto` runs) reconfigures, and that build then fails.
 */
@DisableCachingByDefault(because = "Only checks that a file exists")
abstract class VerifyNativeResourceTask : DefaultTask() {
    /** Absolute path of the library the host build must have produced. */
    @get:Input
    abstract val expectedArtifactPath: Property<String>

    @TaskAction
    fun verify() {
        val expectedArtifact = File(expectedArtifactPath.get())
        if (!expectedArtifact.exists()) {
            throw GradleException("Expected native artifact is missing: $expectedArtifact")
        }
    }
}

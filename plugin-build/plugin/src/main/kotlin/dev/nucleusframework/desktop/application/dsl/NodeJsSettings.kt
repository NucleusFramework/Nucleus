package dev.nucleusframework.desktop.application.dsl

import dev.nucleusframework.internal.utils.notNullProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Node.js acquisition for the electron-builder packaging pipeline.
 *
 * Every installer format goes through electron-builder, which the plugin provisions with
 * `npm ci` against a lock file it embeds — so packaging needs a Node.js. By default the plugin
 * downloads one from `nodejs.org` on first use and caches it under
 * `<gradle-user-home>/nucleus/nodejs`: nothing has to be installed on the build machine, and
 * every machine packages with the same Node.js.
 *
 * Overrides, in order of precedence:
 * 1. the `compose.electronBuilder.nodePath` Gradle property (the node binary, or its directory),
 * 2. a `NUCLEUS_NODE_HOME` environment variable pointing at an installation,
 * 3. this block,
 * 4. `node` on `PATH`, used when [autoDownload] is `false` or the download fails.
 *
 * Floating versions are sticky once downloaded; delete the corresponding directory under
 * [installDir] to pick up a newer release.
 */
abstract class NodeJsSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /**
         * Download and cache Node.js automatically. Defaults to `true`; `false` falls back to the
         * `node` and `npm` found on `PATH`.
         */
        val autoDownload: Property<Boolean> = objects.notNullProperty(true)

        /**
         * Node.js version: a major line tracking its newest release (`"22"`, the default), the
         * newest LTS (`"lts"`), or a pinned release (`"22.11.0"`).
         */
        val version: Property<String> = objects.notNullProperty("22")

        /** Where downloaded Node.js installations are cached. Defaults to `<gradle-user-home>/nucleus/nodejs`. */
        val installDir: DirectoryProperty = objects.directoryProperty()
    }

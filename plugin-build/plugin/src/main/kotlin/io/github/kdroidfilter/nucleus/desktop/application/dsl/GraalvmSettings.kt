package io.github.kdroidfilter.nucleus.desktop.application.dsl

import io.github.kdroidfilter.nucleus.internal.utils.new
import io.github.kdroidfilter.nucleus.internal.utils.notNullProperty
import io.github.kdroidfilter.nucleus.internal.utils.nullableProperty
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.jvm.toolchain.JvmVendorSpec
import javax.inject.Inject

abstract class GraalvmSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val isEnabled: Property<Boolean> = objects.notNullProperty(false)

        @Suppress("MagicNumber")
        val javaLanguageVersion: Property<Int> = objects.notNullProperty(25)
        val jvmVendor: Property<JvmVendorSpec> = objects.nullableProperty()
        val imageName: Property<String> = objects.nullableProperty()
        val march: Property<String> = objects.notNullProperty("native")
        val buildArgs: ListProperty<String> = objects.listProperty(String::class.java)
        val nativeImageConfigBaseDir: DirectoryProperty = objects.directoryProperty()
        val macOS: GraalvmMacOSSettings = objects.new()
        val metadataRepository: MetadataRepositorySettings = objects.new()
        val optimization: GraalvmOptimizationSettings = objects.new()
        val upx: UpxSettings = objects.new()

        fun macOS(fn: Action<GraalvmMacOSSettings>) {
            fn.execute(macOS)
        }

        fun metadataRepository(fn: Action<MetadataRepositorySettings>) {
            fn.execute(metadataRepository)
        }

        fun optimization(fn: Action<GraalvmOptimizationSettings>) {
            fn.execute(optimization)
        }

        fun upx(fn: Action<UpxSettings>) {
            fn.execute(upx)
        }
    }

abstract class GraalvmMacOSSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val cStubsSrc: RegularFileProperty = objects.fileProperty()
        val minimumSystemVersion: Property<String> = objects.notNullProperty("12.0")
    }

/**
 * Settings for the Oracle GraalVM Reachability Metadata Repository.
 * When enabled, metadata from the repository is automatically resolved
 * for runtime classpath dependencies and passed to native-image.
 *
 * @see <a href="https://github.com/oracle/graalvm-reachability-metadata">oracle/graalvm-reachability-metadata</a>
 */
abstract class MetadataRepositorySettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Whether to use the Oracle metadata repository. Defaults to true. */
        val enabled: Property<Boolean> = objects.notNullProperty(true)

        /** Version of the metadata repository artifact. */
        val version: Property<String> = objects.notNullProperty("0.10.6")

        /** Module coordinates (group:artifact) to exclude from repository resolution. */
        val excludedModules: SetProperty<String> =
            objects.setProperty(String::class.java)

        /**
         * Override the metadata version used for specific modules.
         * Key: "group:artifact", value: metadata directory version in the repository.
         */
        val moduleToConfigVersion: MapProperty<String, String> =
            objects.mapProperty(String::class.java, String::class.java)
    }

abstract class GraalvmOptimizationSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Injects `-Os` to optimize for binary size. */
        val optimizeForSize: Property<Boolean> = objects.notNullProperty(false)

        /** Injects `-H:+TrackPrimitiveValues -H:+UsePredicates` (experimental). */
        val skipFlow: Property<Boolean> = objects.notNullProperty(false)
    }

abstract class UpxSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Enable UPX compression of the native binary after compilation. */
        val isEnabled: Property<Boolean> = objects.notNullProperty(false)

        /** Path to the UPX executable. Resolved from PATH if not set. */
        val executablePath: Property<String> = objects.nullableProperty()

        /** Compression level (1-9, or 10 for --best). Defaults to 7. */
        @Suppress("MagicNumber")
        val compressionLevel: Property<Int> = objects.notNullProperty(7)
    }

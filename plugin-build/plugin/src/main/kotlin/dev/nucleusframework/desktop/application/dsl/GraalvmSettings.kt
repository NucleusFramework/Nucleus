package dev.nucleusframework.desktop.application.dsl

import dev.nucleusframework.internal.utils.new
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
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
        // Portable ISA baseline by default: the produced binary is meant to be distributed, so it
        // must run on any x86-64 CPU. "native" optimizes for the build machine only and crashes on
        // older CPUs ("does not support all of the following CPU features"). Override for local perf.
        val march: Property<String> = objects.notNullProperty("compatibility")
        val buildArgs: ListProperty<String> = objects.listProperty(String::class.java)
        val nativeImageConfigBaseDir: DirectoryProperty = objects.directoryProperty()
        val macOS: GraalvmMacOSSettings = objects.new()
        val windows: GraalvmWindowsSettings = objects.new()
        val metadataRepository: MetadataRepositorySettings = objects.new()

        fun macOS(fn: Action<GraalvmMacOSSettings>) {
            fn.execute(macOS)
        }

        fun windows(fn: Action<GraalvmWindowsSettings>) {
            fn.execute(windows)
        }

        fun metadataRepository(fn: Action<MetadataRepositorySettings>) {
            fn.execute(metadataRepository)
        }
    }

abstract class GraalvmMacOSSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val cStubsSrc: RegularFileProperty = objects.fileProperty()
        val minimumSystemVersion: Property<String> = objects.notNullProperty("12.0")
        val macOsSdkVersion: Property<String> = objects.notNullProperty("26.0")
    }

/**
 * Windows-specific settings for GraalVM native images.
 */
abstract class GraalvmWindowsSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /**
         * Whether to bundle the MSVC C/C++ runtime DLLs (vcruntime140.dll, vcruntime140_1.dll,
         * msvcp140.dll) next to the produced native executable.
         *
         * GraalVM native images on Windows are dynamically linked against the Visual C++
         * runtime, which is **not** part of a clean Windows install (it ships with the
         * "Visual C++ Redistributable"). Without these DLLs end users get
         * `VCRUNTIME140.dll not found` and the app fails to start. Bundling them next to the
         * `.exe` lets the app run with no admin install and no external prerequisite.
         *
         * Defaults to `true`.
         */
        val bundleCRuntime: Property<Boolean> = objects.notNullProperty(true)

        /**
         * The DLL file names copied next to the executable when [bundleCRuntime] is enabled.
         * Only files that actually exist in [sourceDir] are copied; missing ones are reported
         * as a warning at packaging time.
         */
        val dlls: ListProperty<String> =
            objects
                .listProperty(String::class.java)
                .convention(listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"))

        /**
         * Directory the runtime DLLs are copied from. Defaults to the GraalVM toolchain's
         * `bin` directory, which ships these DLLs. Override it to point at the MSVC
         * redistributable directory (e.g. `VC\Redist\MSVC\<version>\x64\Microsoft.VC143.CRT`)
         * if one of the requested DLLs is not present in the toolchain.
         */
        val sourceDir: DirectoryProperty = objects.directoryProperty()
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

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
        // Target CPU instruction set (`-march`). Leave unset for the per-platform default:
        // [NativeImageMarch.COMPATIBILITY] (portable baseline for distributed binaries) everywhere
        // except macOS on Apple Silicon, which defaults to [NativeImageMarch.NATIVE] (its armv8-a
        // baseline is present on every supported Mac, so there is no portability cost). Set to
        // [NativeImageMarch.NATIVE] to tune for the build machine (crashes on older/different CPUs).
        val march: Property<NativeImageMarch> = objects.nullableProperty()

        // Optimization level for native-image (the `-O*` flag). Leave unset to keep native-image's
        // own default (`-O2`). Use [NativeImageOptimization.SIZE] to shrink Compose images (~20–30%),
        // [NativeImageOptimization.LEVEL_3] for peak runtime performance (Oracle GraalVM only), or
        // [NativeImageOptimization.QUICK_BUILD] for fast local iteration. Any `-O*` passed explicitly
        // via [buildArgs] still takes precedence (native-image honors the last `-O*` flag).
        val optimization: Property<NativeImageOptimization> = objects.nullableProperty()

        // Embed every JDK charset in the image (`-H:+AddAllCharsets`). native-image otherwise ships
        // only a minimal set (US-ASCII, ISO-8859-1, UTF-8, UTF-16 + platform default); any other
        // charset requested via `Charset.forName(...)` throws UnsupportedCharsetException at runtime.
        // Enable only if the app decodes bytes in a legacy encoding (e.g. windows-1255, ISO-8859-8,
        // Shift_JIS) — it is NOT needed to display or type non-Latin text, which is Unicode-internal.
        // Costs a few MB (mostly CJK tables). Off by default to match GraalVM's default.
        val allCharsets: Property<Boolean> = objects.notNullProperty(false)

        // Oracle GraalVM applies a Machine-Learning-inferred PGO profile by default at `-O2` when no
        // real profile (`--pgo`) is supplied — the build log then reports `PGO: ML-inferred`. It is a
        // static, pre-trained branch-frequency guess (no instrumentation, no profiling run) and is
        // generally a small win, but it is Oracle-specific and non-deterministic across GraalVM
        // versions. Set to `false` to opt out (`-H:-MLProfileInference`), yielding `PGO: off`. Only
        // effective at optimization levels that run the ML pass (i.e. `-O2`); ignored under `-Os`.
        // Defaults to `true` to match Oracle GraalVM's out-of-the-box behavior.
        val mlProfileInference: Property<Boolean> = objects.notNullProperty(true)

        val buildArgs: ListProperty<String> = objects.listProperty(String::class.java)
        val nativeImageConfigBaseDir: DirectoryProperty = objects.directoryProperty()
        val macOS: GraalvmMacOSSettings = objects.new()
        val windows: GraalvmWindowsSettings = objects.new()
        val metadataRepository: MetadataRepositorySettings = objects.new()
        val pgo: GraalvmPgoSettings = objects.new()

        fun macOS(fn: Action<GraalvmMacOSSettings>) {
            fn.execute(macOS)
        }

        fun windows(fn: Action<GraalvmWindowsSettings>) {
            fn.execute(windows)
        }

        fun metadataRepository(fn: Action<MetadataRepositorySettings>) {
            fn.execute(metadataRepository)
        }

        fun pgo(fn: Action<GraalvmPgoSettings>) {
            fn.execute(pgo)
        }
    }

/**
 * Profile-Guided Optimization settings (Oracle GraalVM only).
 *
 * Workflow:
 * 1. `./gradlew runWithPgoInstrument` — builds an instrumented native image, packages and runs
 *    it. Exercise the app's hot paths, then quit: the profile is recorded to [profile].
 * 2. Rebuild (`nativeImageCompile` / `packageGraalvmNative` / …) — the recorded profile is
 *    picked up automatically (`--pgo=<profile>`), replacing Oracle's default ML-inferred one.
 *
 * A recorded profile is meant to be committed alongside the project so CI release builds
 * benefit from it. Delete the file (or pass `-Pnucleus.graalvm.pgo=off`) to build without it.
 *
 * On community toolchains (GraalVM CE, Liberica NIK, Mandrel) `--pgo` is not available:
 * a recorded profile is then ignored with a warning instead of failing the build, so the
 * same repository builds everywhere. Instrumentation, however, fails fast with a clear
 * message since it cannot produce a profile without Oracle GraalVM.
 */
abstract class GraalvmPgoSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Automatically apply [profile] when the file exists. Defaults to `true`. */
        val enabled: Property<Boolean> = objects.notNullProperty(true)

        /**
         * Location of the recorded profile. Defaults to `graalvm/pgo/default.iprof`
         * in the project directory (next to the native-image config dir).
         */
        val profile: RegularFileProperty = objects.fileProperty()
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

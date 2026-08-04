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

        // Gradle toolchain spec used only when toolchain.autoDownload is disabled; the
        // auto-downloaded toolchain is selected via toolchain { channel / version } instead.
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

        // Automatically register the project's own resources (its source-set resource directories
        // and those of `project(...)` module dependencies) for inclusion in the native image.
        // native-image only embeds resources it is told about, and dynamic
        // `getResourceAsStream(path)` calls cannot be resolved statically — so without this, assets
        // loaded by a computed path (e.g. markdown files listed in an index) are silently dropped
        // from the binary. This mirrors the JVM distribution, where every resource ships in the uber
        // JAR. Third-party JAR resources are NOT included (they are covered by the L1/L2 metadata and
        // static analysis). Defaults to `true`; set to `false` to manage resource globs manually.
        val autoIncludeResources: Property<Boolean> = objects.notNullProperty(true)

        // Symbol obfuscation baked into the native image (`-H:AdvancedObfuscation=`, Oracle GraalVM
        // only, experimental). Renames module/package/class/method/field/source names to opaque
        // identifiers inside the binary — in stack traces, heap dumps, and everything read via
        // metadata (`Class#getName()`, `strings`) — hardening the app against reverse engineering.
        // Unlike ProGuard, this operates on the compiled image and is JNI/reflection-safe by design:
        // names registered under `reflection` in the reachability metadata are left intact, so JNI
        // callbacks and reflective lookups keep working. JDK/SubstrateVM code is never obfuscated.
        // Costs 20–50% longer builds (two-phase), no runtime cost. Ignored on non-Oracle toolchains
        // (a warning is logged). Export the original→obfuscated mapping for deobfuscating production
        // stack traces by adding `-H:AdvancedObfuscation=export-mapping` via [buildArgs]. Off by default.
        val advancedObfuscation: Property<Boolean> = objects.notNullProperty(false)

        // Default runtime maximum heap baked into the image. native-image's Serial GC otherwise
        // defaults the max heap to 80% of physical RAM — far above a desktop app's norm (HotSpot
        // defaults to ~25% via MaxRAMPercentage, which Compose Desktop apps already run under on the
        // JVM). [maxHeapSizePercent] bakes `-R:MaximumHeapSizePercent` (default 25, JVM parity);
        // [maxHeapSize] bakes an absolute `-R:MaxHeapSize` (e.g. "2g", "512m") and takes precedence
        // when set. Both are only defaults — overridable at runtime via `-Xmx` /
        // `-XX:MaximumHeapSizePercent`. Set percent to 80 to restore native-image's own default.
        val maxHeapSize: Property<String> = objects.nullableProperty()
        val maxHeapSizePercent: Property<Int> = objects.notNullProperty(25)

        // Garbage collector baked into the image (`--gc=`). Unlike the JVM, the collector is fixed
        // at build time. Leave unset to keep native-image's default (Serial GC, the right fit for a
        // desktop app's small heap). [NativeImageGarbageCollector.G1] is for heaps that outgrow it,
        // and is Oracle GraalVM + Linux only — elsewhere it degrades to a warning instead of
        // failing the build. [maxHeapSizePercent] follows the selected collector: it is baked as
        // `-R:MaximumHeapSizePercent` under Serial/Epsilon and as `-R:MaxRAMPercentage` under G1,
        // which does not know the former option.
        val garbageCollector: Property<NativeImageGarbageCollector> = objects.nullableProperty()

        val buildArgs: ListProperty<String> = objects.listProperty(String::class.java)
        val nativeImageConfigBaseDir: DirectoryProperty = objects.directoryProperty()
        val toolchain: GraalvmToolchainSettings = objects.new()
        val macOS: GraalvmMacOSSettings = objects.new()
        val windows: GraalvmWindowsSettings = objects.new()
        val metadataRepository: MetadataRepositorySettings = objects.new()
        val pgo: GraalvmPgoSettings = objects.new()
        val layers: GraalvmLayerSettings = objects.new()

        fun toolchain(fn: Action<GraalvmToolchainSettings>) {
            fn.execute(toolchain)
        }

        fun layers(fn: Action<GraalvmLayerSettings>) {
            fn.execute(layers)
        }

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
 * GraalVM JDK toolchain acquisition.
 *
 * By default the plugin downloads **GraalVM Community Edition** on first use and caches it
 * under `<gradle-user-home>/nucleus/graalvm` — no locally installed GraalVM is required.
 * Community builds come from the `graalvm/graalvm-ce-builds` GitHub releases; setting
 * [distribution] to [GraalvmDistribution.ORACLE] switches to Oracle GraalVM instead
 * (innovation releases from `gds.oracle.com`, LTS and pinned releases from
 * `download.oracle.com`) and logs a licensing warning. On Intel macs, which both
 * distributions stopped shipping after 25.0.1, the plugin falls back to BellSoft Liberica
 * NIK (resolved through the BellSoft discovery API).
 *
 * A `GRAALVM_HOME` environment variable pointing at a valid GraalVM installation always
 * wins over the download — useful on CI where `setup-graalvm` already provisioned one.
 * Set [autoDownload] to `false` to resolve through the regular Gradle toolchain machinery
 * instead ([GraalvmSettings.javaLanguageVersion] / [GraalvmSettings.jvmVendor]); note that
 * [distribution] still declares intent in that case, since it also gates the Oracle-only
 * tasks (`runWithPgoInstrument`).
 *
 * "latest" versions ("25", "25i1") are sticky once downloaded; delete the corresponding
 * directory under [installDir] to pick up a newer build.
 */
abstract class GraalvmToolchainSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Download and cache the GraalVM JDK automatically. Defaults to `true`. */
        val autoDownload: Property<Boolean> = objects.notNullProperty(true)

        /**
         * Which GraalVM build to use. Defaults to [GraalvmDistribution.COMMUNITY] (GPLv2 with
         * the Classpath Exception, no restriction on redistributing it inside a paid app).
         * [GraalvmDistribution.ORACLE] unlocks PGO, `-O3` and advanced obfuscation but is
         * governed by the GraalVM Free Terms and Conditions; selecting it logs a warning.
         */
        val distribution: Property<GraalvmDistribution> =
            objects.notNullProperty(GraalvmDistribution.COMMUNITY)

        /** Release channel used when [version] is not set. Defaults to [GraalvmChannel.INNOVATION]. */
        val channel: Property<GraalvmChannel> = objects.notNullProperty(GraalvmChannel.INNOVATION)

        /**
         * Explicit GraalVM version, overriding [channel]: an innovation release
         * (`"25i1"`), a feature version tracking the latest CPU (`"25"`), or a pinned
         * patch release (`"25.0.1"`).
         */
        val version: Property<String> = objects.nullableProperty()

        /**
         * Use Liberica NIK on Intel macs, where Oracle GraalVM is no longer shipped.
         * Defaults to `true`.
         */
        val macosIntelFallback: Property<Boolean> = objects.notNullProperty(true)

        /** Where downloaded toolchains are cached. Defaults to `<gradle-user-home>/nucleus/graalvm`. */
        val installDir: DirectoryProperty = objects.directoryProperty()
    }

/**
 * Profile-Guided Optimization settings (Oracle GraalVM only).
 *
 * Requires `graalvm { toolchain { distribution = GraalvmDistribution.ORACLE } }`: under the
 * default [GraalvmDistribution.COMMUNITY] toolchain the `runWithPgoInstrument` task is not
 * registered at all, and a recorded [profile] is ignored with a warning.
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
        val version: Property<String> = objects.notNullProperty("1.1.4")

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

/**
 * Splits the native image into a reusable base layer and a thin application layer.
 *
 * A monolithic native image is one blob whose layout `native-image` reshuffles globally, so a
 * one-line source change invalidates nearly all of its blocks and an auto-update has to refetch the
 * whole executable. With layers the AOT-compiled JDK lives in a shared library the application links
 * against, and a release that only changes application code ships the same library byte for byte —
 * so a differential update reuses it in full instead of downloading it again.
 *
 * The trade is disk for traffic: the base layer is not pruned against what the application actually
 * uses, so the first download grows while every later one shrinks.
 *
 * **Not usable for a shipping application as of GraalVM 25.2.** Kept behind this flag because the
 * scaffolding is correct and the payoff is measured (2.3x less traffic per update, break-even after
 * ~1.2 updates on a macOS ZIP of `nucleus-demo`), but three things stand in the way:
 *
 *  - **Reflection metadata versus base-layer types.** The application layer fails with "This type is
 *    incomplete and should not be used", thrown from `BaseLayerType.getStaticFields` under
 *    `ReflectionDataBuilder.checkHidingFields`: registering a type for reflection makes the builder
 *    walk its subtypes and read their static fields, and a subtype that lives in the base layer is
 *    only a stub in the application layer. There is no option that disables that walk, and the
 *    metadata that triggers it is the metadata every real application needs. The number of errors
 *    reported scales with `-H:NumberOfThreads` (the walk runs on a parallel executor), which makes it
 *    look intermittent; the failure itself is not.
 *  - **Text fields need an AWT resource bundle.** Constructing a `java.awt.Cursor` — what
 *    `PointerIcon` does for any text field — reads `sun.awt.resources.awtosx`. A base-layer build
 *    reaches no AWT code on its own, so native-image never registers those bundles the way it does
 *    for a monolithic image; [resourceBundles] registers them explicitly, in the layer that owns
 *    `java.desktop`. That is the only place it works — registering them from the application layer
 *    leaves the runtime lookup failing — but it is also what makes the reflection defect above fire,
 *    so the two cannot currently be satisfied at once.
 *
 * Past that, the application layer stops on a second wall: "The core type
 * `com.oracle.svm.core.jdk.AtomicFieldUpdaterAccessCheck` was not seen as reachable the initial layer.
 * It is illegal for core types to become reachable in subsequent layers." A base layer holding only
 * JDK modules analyses no application code, so it never sees the SVM internals the application will
 * reach. Putting the application's classes on the base layer's class path does make it see them — and
 * collapses the split: the base layer then analyses 28,400 types and 173,197 methods while the
 * application layer is left with 158 types. GraalVM's prescribed escape is to pin those types via
 * `LayeredCompilationBehavior` / `InitialLayerFeature`, which needs the set of SVM internals a given
 * application reaches to be known up front — the opposite of working for any application.
 *
 * Two further routes were tried and are dead ends worth not repeating. Substituting
 * `Toolkit.getProperty` to return its default (the JDK already does that for a missing bundle) has to
 * be applied in the layer that compiles `java.awt.Toolkit`, and a base layer only sees substitutions
 * if it is given a class path — at which point it absorbs the whole application and the application
 * layer has nothing left to compile ("0 types, 0 fields, and 2 methods found reachable"). Moving
 * `java.desktop` out of the base layer is not possible either: a base layer without it fails with
 * "Newly seen boot package java.awt.datatransfer".
 *  - **Requires GraalVM 25.2 or newer.** On 25.1 an application layer's JNI registrations are
 *    invisible to `FindClass`, so every native bridge breaks: the window opens and never renders.
 *
 * When the build does succeed the application runs, Compose renders and the native macOS menu bar
 * works, so only the two items above separate this from being usable.
 *
 * Only the JDK goes into the base layer. Putting classpath packages there as well would shrink the
 * application layer further, but as of GraalVM 25.2 it makes the application layer fail to compile
 * with a permanent Graal bailout ("Unstructured locking: too few monitorexits exiting frame") on
 * Kotlin's `synchronized` intrinsic — which any Compose application reaches.
 */
abstract class GraalvmLayerSettings
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Build the image as a base layer plus an application layer. Defaults to `false`. */
        val isEnabled: Property<Boolean> = objects.notNullProperty(false)

        /**
         * JDK modules the base layer holds. The default covers what a Compose Desktop application
         * reaches; `java.base` alone is not enough, and a module the application needs but the layer
         * omits fails the build with "Newly seen boot package".
         */
        val modules: ListProperty<String> =
            objects.listProperty(String::class.java).convention(
                // The order is part of the recipe, not cosmetic: it changes the produced layer (a
                // reordered list yields a differently sized library) and a layer built from another
                // order made the application layer fail with "This type is incomplete and should not
                // be used". This is the sequence that was validated end to end.
                listOf(
                    "java.base",
                    "java.datatransfer",
                    "java.desktop",
                    "java.logging",
                    "java.management",
                    "java.naming",
                    "java.prefs",
                    "java.xml",
                    "java.net.http",
                    "java.instrument",
                    "jdk.unsupported",
                ),
            )

        /**
         * Resource bundles the base layer embeds.
         *
         * The layer that owns `java.desktop` is where its localization support is built, and nothing
         * in a base-layer build reaches `java.awt.Cursor`, so native-image never registers the AWT
         * bundles on its own the way it does for a monolithic image. Without them any text field dies
         * at runtime with "Resource bundle lookup must be loaded during native image generation:
         * class sun.awt.resources.awtosx" — `PointerIcon` constructs a `Cursor`.
         */
        val resourceBundles: ListProperty<String> =
            objects.listProperty(String::class.java).convention(
                listOf(
                    "sun.awt.resources.awt",
                    "sun.awt.resources.awtosx",
                    "sun.print.resources.serviceui",
                    "com.sun.swing.internal.plaf.basic.resources.basic",
                    "com.sun.swing.internal.plaf.metal.resources.metal",
                ),
            )

    }
